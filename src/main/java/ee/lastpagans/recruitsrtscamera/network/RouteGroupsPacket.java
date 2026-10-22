package ee.lastpagans.recruitsrtscamera.network;

import ee.lastpagans.recruitsrtscamera.combat.RTSGroupAttackManager;
import ee.lastpagans.recruitsrtscamera.compat.OrderRelayCompat;
import ee.lastpagans.recruitsrtscamera.config.RTSServerConfig;
import ee.lastpagans.recruitsrtscamera.server.ServerPacketGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Commit a multi-stage RTS route. */
public record RouteGroupsPacket(List<UUID> groupIds, List<BlockPos> waypoints) {
    private static final double MAX_TARGET_DISTANCE = 336.0D;

    public RouteGroupsPacket {
        int groups = Math.min(groupIds.size(), ServerGroupResolver.MAX_GROUPS_PER_ORDER);
        groupIds = List.copyOf(groupIds.subList(0, groups));
        int points = Math.min(waypoints.size(), 32);
        List<BlockPos> safe = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            safe.add(waypoints.get(i).immutable());
        }
        waypoints = List.copyOf(safe);
    }

    public static void encode(RouteGroupsPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.groupIds.size());
        for (UUID id : packet.groupIds) buffer.writeUUID(id);
        buffer.writeVarInt(packet.waypoints.size());
        for (BlockPos pos : packet.waypoints) buffer.writeBlockPos(pos);
    }

    public static RouteGroupsPacket decode(FriendlyByteBuf buffer) {
        int declaredGroups = Math.max(0, Math.min(128, buffer.readVarInt()));
        List<UUID> groups = new ArrayList<>();
        for (int i = 0; i < declaredGroups; i++) {
            UUID id = buffer.readUUID();
            if (groups.size() < ServerGroupResolver.MAX_GROUPS_PER_ORDER) groups.add(id);
        }
        int declaredPoints = Math.max(0, Math.min(64, buffer.readVarInt()));
        List<BlockPos> points = new ArrayList<>();
        for (int i = 0; i < declaredPoints; i++) {
            BlockPos pos = buffer.readBlockPos();
            if (points.size() < 32) points.add(pos);
        }
        return new RouteGroupsPacket(groups, points);
    }

    public static void handle(RouteGroupsPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> OrderRelayCompat.executeOrRelay(
                context, packet.groupIds(), "order_forward",
                () -> execute(packet, context.getSender())
        ));
        context.setPacketHandled(true);
    }

    private static void execute(RouteGroupsPacket packet, ServerPlayer sender) {
        if (!ServerPacketGuard.allow(sender) || packet.groupIds.isEmpty() || packet.waypoints.isEmpty()) {
            return;
        }
        RTSGroupAttackManager.cancelOwned(sender, packet.groupIds);
        int max = RTSServerConfig.MAX_ROUTE_WAYPOINTS.get();
        if (packet.waypoints.size() > max) {
            sender.displayClientMessage(Component.literal("Route has too many waypoints (max " + max + ")."), true);
            return;
        }
        for (BlockPos point : packet.waypoints) {
            if (!sender.serverLevel().hasChunkAt(point)) {
                sender.displayClientMessage(Component.literal("A route waypoint is not loaded."), true);
                return;
            }
            if (sender.distanceToSqr(point.getX() + 0.5D, point.getY() + 0.5D, point.getZ() + 0.5D)
                    > MAX_TARGET_DISTANCE * MAX_TARGET_DISTANCE) {
                sender.displayClientMessage(Component.literal("A route waypoint is outside tactical range."), true);
                return;
            }
        }
        if (ServerGroupResolver.resolve(sender, packet.groupIds).isEmpty()) {
            sender.displayClientMessage(Component.literal("No loaded owned recruits were found for that route."), true);
            return;
        }
        RouteManager.start(sender, packet.groupIds, packet.waypoints);
        sender.displayClientMessage(Component.literal("Route committed: " + packet.waypoints.size() + " stage(s)."), true);
    }
}
