package ee.lastpagans.recruitsrtscamera.network;

import ee.lastpagans.recruitsrtscamera.client.RTSCameraController;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server-authoritative real-body correction while the detached RTS camera is active. */
public record BodyguardCarryPacket(double x, double y, double z, float yaw) {
    public static void encode(BodyguardCarryPacket packet, FriendlyByteBuf buffer) {
        buffer.writeDouble(packet.x);
        buffer.writeDouble(packet.y);
        buffer.writeDouble(packet.z);
        buffer.writeFloat(packet.yaw);
    }

    public static BodyguardCarryPacket decode(FriendlyByteBuf buffer) {
        return new BodyguardCarryPacket(buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readFloat());
    }

    public static void handle(BodyguardCarryPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> RTSCameraController.applyBodyguardCarry(packet.x, packet.y, packet.z, packet.yaw));
        context.setPacketHandled(true);
    }
}
