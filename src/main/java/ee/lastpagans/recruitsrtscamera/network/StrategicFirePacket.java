package ee.lastpagans.recruitsrtscamera.network;

import ee.lastpagans.recruitsrtscamera.compat.OrderRelayCompat;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.IStrategicFire;
import com.talhanation.recruits.entities.SiegeEngineerEntity;
import ee.lastpagans.recruitsrtscamera.compat.SiegeWeaponsCompat;
import ee.lastpagans.recruitsrtscamera.server.ServerPacketGuard;
import ee.lastpagans.recruitsrtscamera.server.RTSVision;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** F: designate one strategic-fire/bombardment point for the selected groups. */
public record StrategicFirePacket(List<UUID> groupIds, BlockPos target) {
    private static final double MAX_TARGET_DISTANCE = 480.0D;

    public StrategicFirePacket {
        int size = Math.min(groupIds.size(), ServerGroupResolver.MAX_GROUPS_PER_ORDER);
        groupIds = List.copyOf(groupIds.subList(0, size));
        target = target.immutable();
    }

    public static void encode(StrategicFirePacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.groupIds.size());
        for (UUID id : packet.groupIds) {
            buffer.writeUUID(id);
        }
        buffer.writeBlockPos(packet.target);
    }

    public static StrategicFirePacket decode(FriendlyByteBuf buffer) {
        int declared = Math.max(0, Math.min(128, buffer.readVarInt()));
        List<UUID> ids = new ArrayList<>(Math.min(declared, ServerGroupResolver.MAX_GROUPS_PER_ORDER));
        for (int i = 0; i < declared; i++) {
            UUID id = buffer.readUUID();
            if (ids.size() < ServerGroupResolver.MAX_GROUPS_PER_ORDER) {
                ids.add(id);
            }
        }
        return new StrategicFirePacket(ids, buffer.readBlockPos());
    }

    public static void handle(StrategicFirePacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> OrderRelayCompat.executeOrRelay(
                context, packet.groupIds(), "order_fire_conc",
                () -> execute(packet, context.getSender())
        ));
        context.setPacketHandled(true);
    }

    private static void execute(StrategicFirePacket packet, ServerPlayer sender) {
        if (!ServerPacketGuard.allow(sender) || packet.groupIds.isEmpty()) {
            return;
        }
        if (!sender.serverLevel().hasChunkAt(packet.target)) {
            sender.displayClientMessage(Component.literal("That bombardment point is not loaded."), true);
            return;
        }
        if (sender.distanceToSqr(packet.target.getX() + 0.5D, packet.target.getY() + 0.5D, packet.target.getZ() + 0.5D)
                > MAX_TARGET_DISTANCE * MAX_TARGET_DISTANCE) {
            sender.displayClientMessage(Component.literal("That bombardment point is outside tactical range."), true);
            return;
        }
        if (RTSVision.hiddenTargetChecksEnabled()
                && !RTSVision.canSee(sender, net.minecraft.world.phys.Vec3.atCenterOf(packet.target))) {
            sender.displayClientMessage(Component.literal("That bombardment point is hidden by fog of war."), true);
            return;
        }

        Map<UUID, List<AbstractRecruitEntity>> groups = ServerGroupResolver.resolve(sender, packet.groupIds);
        RouteManager.cancel(sender, new ArrayList<>(groups.keySet()));
        ee.lastpagans.recruitsrtscamera.combat.RTSGroupAttackManager.cancel(groups.keySet());
        int ranged = 0;
        int artillery = 0;
        for (List<AbstractRecruitEntity> recruits : groups.values()) {
            for (AbstractRecruitEntity recruit : recruits) {
                if (!(recruit instanceof IStrategicFire strategic)) {
                    continue;
                }
                var settings = ee.lastpagans.recruitsrtscamera.combat.TacticalCombatManager.settings(recruit);
                if (settings.holdFire() || settings.combat() == ee.lastpagans.recruitsrtscamera.combat.TacticalSettings.CombatMode.MELEE)
                    continue;
                if (recruit instanceof SiegeEngineerEntity engineer) {
                    if (SiegeWeaponsCompat.strategicFire(engineer, packet.target)) {
                        artillery++;
                        ranged++;
                    }
                    continue;
                }
                recruit.setTarget(null);
                recruit.setShouldRanged(true);
                strategic.setStrategicFirePos(packet.target);
                strategic.setShouldStrategicFire(true);
                ranged++;
            }
        }

        if (ranged == 0) {
            String extra = SiegeWeaponsCompat.isAvailable()
                    ? ""
                    : " (Siege Weapons is not installed/compatible for artillery.)";
            sender.displayClientMessage(Component.literal("No selected unit can perform strategic fire." + extra), true);
        } else {
            sender.displayClientMessage(Component.literal(
                    "Strategic fire designated: " + ranged + " ranged unit(s)"
                            + (artillery > 0 ? " · " + artillery + " artillery crew(s)" : "")
            ), true);
        }
    }
}
