package ee.lastpagans.recruitsrtscamera.network;

import ee.lastpagans.recruitsrtscamera.client.RTSCameraController;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Server-authoritative remaining formation route used by the commander hover preview. */
public record FormationRouteSyncPacket(UUID groupId, Vec3 center, Vec3 destination,
                                       List<Vec3> waypoints, boolean moving) {
    private static final int MAX_POINTS = 256;

    public FormationRouteSyncPacket {
        waypoints = List.copyOf(waypoints.subList(0, Math.min(MAX_POINTS, waypoints.size())));
    }

    public static void encode(FormationRouteSyncPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.groupId);
        writeVec(buffer, packet.center);
        writeVec(buffer, packet.destination);
        buffer.writeBoolean(packet.moving);
        buffer.writeVarInt(packet.waypoints.size());
        for (Vec3 point : packet.waypoints) writeVec(buffer, point);
    }

    public static FormationRouteSyncPacket decode(FriendlyByteBuf buffer) {
        UUID groupId = buffer.readUUID();
        Vec3 center = readVec(buffer);
        Vec3 destination = readVec(buffer);
        boolean moving = buffer.readBoolean();
        int declared = Math.max(0, Math.min(MAX_POINTS, buffer.readVarInt()));
        List<Vec3> points = new ArrayList<>(declared);
        for (int i = 0; i < declared; i++) points.add(readVec(buffer));
        return new FormationRouteSyncPacket(groupId, center, destination, points, moving);
    }

    public static void handle(FormationRouteSyncPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                RTSCameraController.applyFormationRouteSync(
                        packet.groupId, packet.center, packet.destination, packet.waypoints, packet.moving)));
        context.setPacketHandled(true);
    }

    private static void writeVec(FriendlyByteBuf buffer, Vec3 point) {
        buffer.writeDouble(point.x);
        buffer.writeDouble(point.y);
        buffer.writeDouble(point.z);
    }

    private static Vec3 readVec(FriendlyByteBuf buffer) {
        return new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }
}
