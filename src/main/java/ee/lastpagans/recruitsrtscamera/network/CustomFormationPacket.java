package ee.lastpagans.recruitsrtscamera.network;

import ee.lastpagans.recruitsrtscamera.combat.RTSGroupAttackManager;
import ee.lastpagans.recruitsrtscamera.compat.OrderRelayCompat;
import ee.lastpagans.recruitsrtscamera.server.ServerPacketGuard;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.formation.CustomFormationExecutor;
import ee.lastpagans.recruitsrtscamera.formation.FormationService;
import ee.lastpagans.recruitsrtscamera.formation.CustomFormationMath;
import ee.lastpagans.recruitsrtscamera.movement.RTSMovementBoostManager;
import ee.lastpagans.recruitsrtscamera.config.RTSServerConfig;
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

/** Alt+RMB freehand formation order. */
public record CustomFormationPacket(List<UUID> groupIds, List<Vec3> stroke, int ranks) {
    private static final double MAX_POINT_DISTANCE = 336.0D;

    public CustomFormationPacket {
        int groupCount = Math.min(groupIds.size(), ServerGroupResolver.MAX_GROUPS_PER_ORDER);
        groupIds = List.copyOf(groupIds.subList(0, groupCount));
        int pointCount = Math.min(stroke.size(), CustomFormationMath.MAX_STROKE_POINTS);
        stroke = List.copyOf(stroke.subList(0, pointCount));
        ranks = Math.max(1, Math.min(CustomFormationMath.MAX_RANKS, ranks));
    }

    public static void encode(CustomFormationPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.groupIds.size());
        for (UUID groupId : packet.groupIds) {
            buffer.writeUUID(groupId);
        }
        buffer.writeVarInt(packet.stroke.size());
        for (Vec3 point : packet.stroke) {
            buffer.writeDouble(point.x);
            buffer.writeDouble(point.y);
            buffer.writeDouble(point.z);
        }
        buffer.writeVarInt(packet.ranks);
    }

    public static CustomFormationPacket decode(FriendlyByteBuf buffer) {
        int declaredGroups = Math.max(0, Math.min(128, buffer.readVarInt()));
        List<UUID> groups = new ArrayList<>(Math.min(declaredGroups, ServerGroupResolver.MAX_GROUPS_PER_ORDER));
        for (int i = 0; i < declaredGroups; i++) {
            UUID id = buffer.readUUID();
            if (groups.size() < ServerGroupResolver.MAX_GROUPS_PER_ORDER) {
                groups.add(id);
            }
        }

        int declaredPoints = Math.max(0, Math.min(512, buffer.readVarInt()));
        List<Vec3> points = new ArrayList<>(Math.min(declaredPoints, CustomFormationMath.MAX_STROKE_POINTS));
        for (int i = 0; i < declaredPoints; i++) {
            Vec3 point = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
            if (points.size() < CustomFormationMath.MAX_STROKE_POINTS) {
                points.add(point);
            }
        }
        return new CustomFormationPacket(groups, points, buffer.readVarInt());
    }

    public static void handle(CustomFormationPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> OrderRelayCompat.executeOrRelay(
                context, packet.groupIds(), "formation_none",
                () -> execute(packet, context.getSender())
        ));
        context.setPacketHandled(true);
    }

    private static void execute(CustomFormationPacket packet, ServerPlayer sender) {
        if (!ServerPacketGuard.allow(sender, ServerPacketGuard.Lane.CUSTOM) || packet.groupIds.isEmpty() || packet.stroke.size() < 2) {
            return;
        }
        RouteManager.cancel(sender, packet.groupIds);
        RTSGroupAttackManager.cancelOwned(sender, packet.groupIds);
        if (packet.stroke.size() > RTSServerConfig.MAX_CUSTOM_STROKE_POINTS.get()) {
            sender.displayClientMessage(Component.literal("That custom formation contains too many points."), true);
            return;
        }

        List<CustomFormationMath.Point> path = new ArrayList<>(packet.stroke.size());
        for (Vec3 point : packet.stroke) {
            if (!finite(point)) {
                return;
            }
            if (sender.distanceToSqr(point.x, point.y, point.z) > MAX_POINT_DISTANCE * MAX_POINT_DISTANCE) {
                sender.displayClientMessage(Component.literal("Part of that custom formation is outside tactical range."), true);
                return;
            }
            BlockPos block = BlockPos.containing(point);
            if (!sender.serverLevel().hasChunkAt(block)) {
                sender.displayClientMessage(Component.literal("Part of that custom formation is not loaded."), true);
                return;
            }
            path.add(new CustomFormationMath.Point(point.x, point.z));
        }

        path = CustomFormationMath.sanitize(path);
        double length = CustomFormationMath.length(path);
        if (path.size() < 2 || length < CustomFormationMath.MIN_STROKE_LENGTH) {
            sender.displayClientMessage(Component.literal("Custom formation is too short."), true);
            return;
        }
        if (length > CustomFormationMath.MAX_STROKE_LENGTH) {
            sender.displayClientMessage(Component.literal("Custom formation is too long."), true);
            return;
        }

        Map<UUID, List<AbstractRecruitEntity>> groups = ServerGroupResolver.resolve(sender, packet.groupIds);
        if (groups.isEmpty()) {
            sender.displayClientMessage(Component.literal("No loaded owned recruits were found."), true);
            return;
        }

        // Explicitly drawing a new formation replaces any remembered custom layout.
        for (UUID groupId : groups.keySet()) {
            ee.lastpagans.recruitsrtscamera.formation.FormationService.clearCustom(sender.serverLevel(), groupId);
        }

        CustomFormationExecutor.Result result = FormationService.custom(
                sender,
                groups,
                packet.stroke,
                packet.ranks
        );
        if (result.groups() <= 0) {
            sender.displayClientMessage(Component.literal("Could not construct that custom formation."), true);
            return;
        }
        for (Map.Entry<UUID, List<AbstractRecruitEntity>> entry : groups.entrySet()) {
            RTSMovementBoostManager.registerFormationMove(sender, entry.getKey(), entry.getValue());
        }
        sender.displayClientMessage(Component.literal(
                "Custom formation: " + result.groups() + " group(s), " + result.recruits()
                        + " recruits · " + result.ranks() + " rank(s) · "
                        + String.format(java.util.Locale.ROOT, "%.1f", result.strokeLength()) + " blocks"
        ), true);
    }

    private static boolean finite(Vec3 point) {
        return Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }
}
