package ee.lastpagans.recruitsrtscamera.network;

import ee.lastpagans.recruitsrtscamera.server.BodyguardManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record RTSModeStatePacket(boolean active) {
    public static void encode(RTSModeStatePacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.active);
    }

    public static RTSModeStatePacket decode(FriendlyByteBuf buffer) {
        return new RTSModeStatePacket(buffer.readBoolean());
    }

    public static void handle(RTSModeStatePacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> BodyguardManager.setRtsActive(context.getSender(), packet.active));
        context.setPacketHandled(true);
    }
}
