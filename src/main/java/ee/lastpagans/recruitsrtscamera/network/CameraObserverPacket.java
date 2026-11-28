package ee.lastpagans.recruitsrtscamera.network;

import ee.lastpagans.recruitsrtscamera.server.RTSObserverManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Periodic detached-camera position for remote audio and long-distance battle VFX. */
public record CameraObserverPacket(boolean active, double x, double y, double z) {
    public static void encode(CameraObserverPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.active);
        buffer.writeDouble(packet.x);
        buffer.writeDouble(packet.y);
        buffer.writeDouble(packet.z);
    }

    public static CameraObserverPacket decode(FriendlyByteBuf buffer) {
        return new CameraObserverPacket(buffer.readBoolean(), buffer.readDouble(),
                buffer.readDouble(), buffer.readDouble());
    }

    public static void handle(CameraObserverPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> RTSObserverManager.update(context.getSender(), packet.active,
                packet.active ? new Vec3(packet.x, packet.y, packet.z) : null));
        context.setPacketHandled(true);
    }
}
