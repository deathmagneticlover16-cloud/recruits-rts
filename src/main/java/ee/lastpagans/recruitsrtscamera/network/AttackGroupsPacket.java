package ee.lastpagans.recruitsrtscamera.network;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.world.RecruitsGroup;
import com.talhanation.recruits.world.RecruitsGroupsSaveData;
import ee.lastpagans.recruitsrtscamera.combat.RTSGroupAttackManager;
import ee.lastpagans.recruitsrtscamera.combat.TacticalCombatManager;
import ee.lastpagans.recruitsrtscamera.compat.OrderRelayCompat;
import ee.lastpagans.recruitsrtscamera.movement.RTSMovementBoostManager;
import ee.lastpagans.recruitsrtscamera.server.RTSVision;
import ee.lastpagans.recruitsrtscamera.server.ServerPacketGuard;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public record AttackGroupsPacket(List<UUID> groupIds, int targetEntityId, boolean charge) {
    private static final double MAX_TARGET_DISTANCE = 432.0D;
    public AttackGroupsPacket(List<UUID> groupIds, int targetEntityId) { this(groupIds, targetEntityId, false); }

    public AttackGroupsPacket {
        int size = Math.min(groupIds.size(), ServerGroupResolver.MAX_GROUPS_PER_ORDER);
        groupIds = List.copyOf(groupIds.subList(0, size));
    }

    public static void encode(AttackGroupsPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.groupIds.size());
        for (UUID groupId : packet.groupIds) {
            buffer.writeUUID(groupId);
        }
        buffer.writeVarInt(packet.targetEntityId);
        buffer.writeBoolean(packet.charge);
    }

    public static AttackGroupsPacket decode(FriendlyByteBuf buffer) {
        int declaredSize = Math.max(0, Math.min(128, buffer.readVarInt()));
        List<UUID> groups = new ArrayList<>(Math.min(declaredSize, ServerGroupResolver.MAX_GROUPS_PER_ORDER));
        for (int i = 0; i < declaredSize; i++) {
            UUID id = buffer.readUUID();
            if (groups.size() < ServerGroupResolver.MAX_GROUPS_PER_ORDER) {
                groups.add(id);
            }
        }
        return new AttackGroupsPacket(groups, buffer.readVarInt(), buffer.readBoolean());
    }

    public static void handle(AttackGroupsPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> OrderRelayCompat.executeOrRelay(
                context, packet.groupIds(), "order_attack",
                () -> execute(packet, context.getSender())
        ));
        context.setPacketHandled(true);
    }

    private static void execute(AttackGroupsPacket packet, ServerPlayer sender) {
        if (!ServerPacketGuard.allow(sender) || packet.groupIds.isEmpty()) {
            return;
        }
        Entity entity = sender.serverLevel().getEntity(packet.targetEntityId);
        if (!(entity instanceof LivingEntity clickedTarget) || !clickedTarget.isAlive() || clickedTarget == sender) {
            sender.displayClientMessage(Component.literal("That attack target is no longer available."), true);
            return;
        }
        if (clickedTarget.distanceToSqr(sender) > MAX_TARGET_DISTANCE * MAX_TARGET_DISTANCE) {
            sender.displayClientMessage(Component.literal("That target is outside tactical range."), true);
            return;
        }
        if (RTSVision.hiddenTargetChecksEnabled() && !RTSVision.canSee(sender, clickedTarget)) {
            sender.displayClientMessage(Component.literal("That target is hidden by fog of war."), true);
            return;
        }

        Map<UUID, List<AbstractRecruitEntity>> groups = ServerGroupResolver.resolve(sender, packet.groupIds);
        if (groups.isEmpty()) {
            return;
        }
        groups.entrySet().removeIf(entry -> entry.getValue().stream().noneMatch(
                recruit -> recruit.canAttack(clickedTarget) && !recruit.isAlliedTo(clickedTarget)));
        if (groups.isEmpty()) return;
        List<UUID> authorized = new ArrayList<>(groups.keySet());
        RouteManager.cancel(sender, authorized);
        RTSGroupAttackManager.cancel(authorized);
        RTSMovementBoostManager.cancelForcedOrders(authorized);

        UUID enemyGroupId = resolveEnemyGroupId(sender, clickedTarget);
        List<LivingEntity> enemyGroupTargets = resolveEnemyGroupTargets(sender, clickedTarget, enemyGroupId);
        boolean groupAttack = enemyGroupId != null;
        int ordered = 0;

        for (var entry : groups.entrySet()) {
            UUID groupId = entry.getKey();
            List<AbstractRecruitEntity> recruits = entry.getValue();
            if (groupAttack) {
                // 0.9.7: group-vs-group attacks are staged by RTSGroupAttackManager.
                // Melee infantry/cavalry approach coherently first; ranged-heavy groups
                // engage directly. The manager also keeps target distribution alive.
                RTSGroupAttackManager.start(sender, groupId, enemyGroupId);
                if (packet.charge) RTSGroupAttackManager.markCharge(groupId);
                ordered += recruits.size();
            } else {
                ordered += RTSGroupAttackManager.engageSingleTarget(
                        sender, groupId, recruits, clickedTarget
                );
            }
            var data = ee.lastpagans.recruitsrtscamera.persistence.RTSGroupTacticsSavedData.get(sender.serverLevel());
            data.setTimer(groupId, "chargeUntil", packet.charge
                    ? sender.serverLevel().getGameTime() + TacticalCombatManager.CHARGE_ORDER_TICKS : 0);
        }

        if (ordered == 0) {
            sender.displayClientMessage(Component.literal("Selected recruits cannot attack that target."), true);
        } else if (groupAttack) {
            sender.displayClientMessage(Component.literal(
                    (packet.charge ? "Charge enemy group: " : "Attack enemy group: ") + groups.size() + " group(s), " + ordered
                            + " recruits · coordinated approach/engage · "
                            + enemyGroupTargets.size() + " enemy troops loaded."
            ), true);
        } else {
            sender.displayClientMessage(Component.literal(
                    "Attack order: " + groups.size() + " group(s), " + ordered + " recruits."
            ), true);
        }
    }

    /**
     * Clicking one hostile recruit means "attack that recruit's group". Non-Recruits targets,
     * recruits without a valid group, or groups with no other loaded/visible members naturally
     * fall back to the original single-target behaviour.
     */
    private static UUID resolveEnemyGroupId(ServerPlayer sender, LivingEntity clickedTarget) {
        if (!(clickedTarget instanceof AbstractRecruitEntity recruit) || recruit.getGroup() == null) {
            return null;
        }
        UUID targetGroupId = recruit.getGroup();
        for (RecruitsGroup group : RecruitsGroupsSaveData.get(sender.serverLevel()).getAllGroups()) {
            if (group != null && !group.removed && targetGroupId.equals(group.getUUID())
                    && group.members != null && !group.members.isEmpty()) {
                return targetGroupId;
            }
        }
        return null;
    }

    private static List<LivingEntity> resolveEnemyGroupTargets(
            ServerPlayer sender, LivingEntity clickedTarget, UUID targetGroupId
    ) {
        if (targetGroupId == null) {
            return List.of(clickedTarget);
        }
        RecruitsGroup targetGroup = null;
        for (RecruitsGroup group : RecruitsGroupsSaveData.get(sender.serverLevel()).getAllGroups()) {
            if (group != null && !group.removed && targetGroupId.equals(group.getUUID())) {
                targetGroup = group;
                break;
            }
        }
        if (targetGroup == null || targetGroup.members == null || targetGroup.members.isEmpty()) {
            return List.of(clickedTarget);
        }

        List<LivingEntity> candidates = new ArrayList<>();
        for (UUID memberId : targetGroup.members) {
            if (memberId == null) {
                continue;
            }
            Entity member = sender.serverLevel().getEntity(memberId);
            if (!(member instanceof LivingEntity living) || !living.isAlive() || living == sender) {
                continue;
            }
            if (living.distanceToSqr(sender) > MAX_TARGET_DISTANCE * MAX_TARGET_DISTANCE) {
                continue;
            }
            if (RTSVision.hiddenTargetChecksEnabled() && !RTSVision.canSee(sender, living)) {
                continue;
            }
            candidates.add(living);
        }
        if (candidates.isEmpty()) {
            return List.of(clickedTarget);
        }
        if (!candidates.contains(clickedTarget)) {
            candidates.add(clickedTarget);
        }
        return candidates;
    }


}
