package ee.lastpagans.recruitsrtscamera.network;

import ee.lastpagans.recruitsrtscamera.combat.RTSGroupAttackManager;
import ee.lastpagans.recruitsrtscamera.compat.OrderRelayCompat;
import ee.lastpagans.recruitsrtscamera.server.ServerPacketGuard;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.util.FormationUtils;
import com.talhanation.recruits.util.RecruitCommanderUtil;
import ee.lastpagans.recruitsrtscamera.compat.SiegeWeaponsCompat;
import ee.lastpagans.recruitsrtscamera.compat.SmallShipsCompat;
import ee.lastpagans.recruitsrtscamera.formation.FormationPreservationMath;
import ee.lastpagans.recruitsrtscamera.formation.FormationService;
import ee.lastpagans.recruitsrtscamera.movement.RTSMovementBoostManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Normal RMB is a translation order, not a reform order. The selected groups keep
 * their current relative X/Z spacing and each group keeps its own current formation.
 */
public record MoveGroupsPacket(List<UUID> groupIds, BlockPos target) {
    private static final double MAX_TARGET_DISTANCE = 112.0D;

    public MoveGroupsPacket {
        int size = Math.min(groupIds.size(), ServerGroupResolver.MAX_GROUPS_PER_ORDER);
        groupIds = List.copyOf(groupIds.subList(0, size));
        target = target.immutable();
    }

    public static void encode(MoveGroupsPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.groupIds.size());
        for (UUID groupId : packet.groupIds) {
            buffer.writeUUID(groupId);
        }
        buffer.writeBlockPos(packet.target);
    }

    public static MoveGroupsPacket decode(FriendlyByteBuf buffer) {
        int declaredSize = Math.max(0, Math.min(128, buffer.readVarInt()));
        List<UUID> groups = new ArrayList<>(Math.min(declaredSize, ServerGroupResolver.MAX_GROUPS_PER_ORDER));
        for (int i = 0; i < declaredSize; i++) {
            UUID id = buffer.readUUID();
            if (groups.size() < ServerGroupResolver.MAX_GROUPS_PER_ORDER) {
                groups.add(id);
            }
        }
        return new MoveGroupsPacket(groups, buffer.readBlockPos());
    }

    public static void handle(MoveGroupsPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> OrderRelayCompat.executeOrRelay(
                context, packet.groupIds(), "order_forward",
                () -> execute(packet, context.getSender(), false)
        ));
        context.setPacketHandled(true);
    }

    static void execute(MoveGroupsPacket packet, ServerPlayer sender) {
        execute(packet, sender, false);
    }

    static void executeRouteStep(MoveGroupsPacket packet, ServerPlayer sender) {
        execute(packet, sender, true);
    }

    private static void execute(MoveGroupsPacket packet, ServerPlayer sender, boolean routeStep) {
        if (!ServerPacketGuard.allow(sender) || packet.groupIds.isEmpty()) {
            return;
        }
        if (!routeStep) {
            RouteManager.cancel(sender, packet.groupIds);
            RTSGroupAttackManager.cancelOwned(sender, packet.groupIds);
        }
        if (!sender.serverLevel().hasChunkAt(packet.target)) {
            sender.displayClientMessage(Component.literal("That destination is not loaded."), true);
            return;
        }
        if (sender.distanceToSqr(
                packet.target.getX() + 0.5D,
                packet.target.getY() + 0.5D,
                packet.target.getZ() + 0.5D
        ) > MAX_TARGET_DISTANCE * MAX_TARGET_DISTANCE) {
            sender.displayClientMessage(Component.literal("That destination is outside tactical range."), true);
            return;
        }

        Map<UUID, List<AbstractRecruitEntity>> groups = ServerGroupResolver.resolve(sender, packet.groupIds);
        if (groups.isEmpty()) {
            sender.displayClientMessage(Component.literal("No loaded owned recruits were found."), true);
            return;
        }

        BlockPos armySurface = FormationUtils.getPositionOrSurface(sender.serverLevel(), packet.target);
        Vec3 armyTarget = Vec3.atBottomCenterOf(armySurface);

        List<UUID> ids = new ArrayList<>(groups.keySet());
        List<FormationPreservationMath.Point> currentCenters = new ArrayList<>(ids.size());
        for (UUID groupId : ids) {
            Vec3 center = groupCenter(groups.get(groupId));
            currentCenters.add(new FormationPreservationMath.Point(center.x, center.z));
        }
        List<FormationPreservationMath.Point> translated = FormationPreservationMath.translateGroupCenters(
                currentCenters,
                new FormationPreservationMath.Point(armyTarget.x, armyTarget.z)
        );

        int ordered = 0;
        int preservedGroups = 0;
        int customGroups = 0;
        int artilleryGuns = 0;
        int ships = 0;

        for (int i = 0; i < ids.size(); i++) {
            UUID groupId = ids.get(i);
            List<AbstractRecruitEntity> recruits = groups.get(groupId);
            FormationPreservationMath.Point translatedCenter = translated.get(i);
            BlockPos surface = FormationUtils.getPositionOrSurface(
                    sender.serverLevel(),
                    BlockPos.containing(translatedCenter.x(), armySurface.getY(), translatedCenter.z())
            );
            Vec3 groupTarget = new Vec3(translatedCenter.x(), surface.getY(), translatedCenter.z());

            RecruitCommanderUtil.setRecruitsClearTargets(recruits);
            boolean custom = FormationService.hasCustom(sender.serverLevel(), groupId);
            float facingYaw = FormationService.currentFacing(
                    sender.serverLevel(), groupId, recruits, sender.getYRot()
            );

            // Manned siege engineers cannot be driven reliably by Better Formations.
            // Give every gun its own native Recruits move-pos instead, arranged in a
            // wide battery line around this group's translated RTS destination.
            SiegeWeaponsCompat.BatteryOrder battery = SiegeWeaponsCompat.commandBatteryLine(
                    sender, recruits, groupTarget, facingYaw, null
            );
            SmallShipsCompat.FleetOrder fleet = SmallShipsCompat.commandFleetFormation(
                    sender, recruits, groupTarget, facingYaw,
                    FormationService.activeKind(sender.serverLevel(), groupId), null
            );
            List<AbstractRecruitEntity> formationRecruits = SiegeWeaponsCompat.withoutAttachedArtillery(recruits);
            formationRecruits = SmallShipsCompat.withoutAttachedShips(formationRecruits);

            boolean preserved = false;
            if (!formationRecruits.isEmpty()) {
                if (FormationService.movePreserved(sender, groupId, formationRecruits, groupTarget)) {
                    preserved = true;
                    if (custom) {
                        customGroups++;
                    }
                } else {
                    // Better Formations remains optional. Even native fallback receives each
                    // group's translated centre, so selected groups no longer stack on one block.
                    RecruitCommanderUtil.setRecruitsMove(formationRecruits, surface);
                }
                RTSMovementBoostManager.registerFormationMove(sender, groupId, formationRecruits);
            }
            if (battery.movedAny()) {
                artilleryGuns += battery.guns();
                FormationService.rememberFacing(sender.serverLevel(), groupId, facingYaw);
                preserved = true;
            }
            if (fleet.movedAny()) {
                ships += fleet.ships();
                FormationService.rememberFacing(sender.serverLevel(), groupId, facingYaw);
                preserved = true;
            }
            if (preserved) {
                preservedGroups++;
            }
            ordered += recruits.size();
        }

        String suffix;
        if (preservedGroups == groups.size()) {
            suffix = customGroups > 0
                    ? " · army layout + custom shape preserved"
                    : " · army layout + formations preserved";
        } else if (groups.size() > 1) {
            suffix = " · group spacing preserved";
        } else {
            suffix = "";
        }
        if (artilleryGuns > 0) {
            suffix += " · " + artilleryGuns + " gun(s) in battery line";
        }
        if (ships > 0) {
            suffix += " · " + ships + " ship(s) in fleet line";
        }
        // RouteManager reuses this executor for each internal waypoint. Those are
        // continuation steps, not new player-issued orders, so keep the action bar quiet.
        if (!routeStep) {
            sender.displayClientMessage(Component.literal(
                    "Move order: " + groups.size() + " group(s), " + ordered + " recruits" + suffix
            ), true);
        }
    }

    private static Vec3 groupCenter(List<AbstractRecruitEntity> recruits) {
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        for (AbstractRecruitEntity recruit : recruits) {
            x += recruit.getX();
            y += recruit.getY();
            z += recruit.getZ();
        }
        double inv = 1.0D / Math.max(1, recruits.size());
        return new Vec3(x * inv, y * inv, z * inv);
    }
}
