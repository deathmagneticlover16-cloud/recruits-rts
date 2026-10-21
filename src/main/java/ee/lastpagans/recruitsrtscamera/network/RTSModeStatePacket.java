package ee.lastpagans.recruitsrtscamera.network;

import ee.lastpagans.recruitsrtscamera.server.BodyguardManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

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
        context.enqueueWork(() -> {
            var sender = context.getSender();
            BodyguardManager.setRtsActive(sender, packet.active);
            if (packet.active && sender != null) {
                RTSCameraNetwork.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> sender),
                        FormationStateSyncPacket.forPlayer(sender)
                );
            }
        });
        context.setPacketHandled(true);
    }
}
