package ee.lastpagans.recruitsrtscamera.network;

import ee.lastpagans.recruitsrtscamera.advisor.AdvisorClient;
import ee.lastpagans.recruitsrtscamera.advisor.AdvisorEvent;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record AdvisorEventPacket(AdvisorEvent event) {
    public static void encode(AdvisorEventPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.event.ordinal());
    }

    public static AdvisorEventPacket decode(FriendlyByteBuf buffer) {
        int ordinal = Math.max(0, Math.min(AdvisorEvent.values().length - 1, buffer.readVarInt()));
        return new AdvisorEventPacket(AdvisorEvent.values()[ordinal]);
    }

    public static void handle(AdvisorEventPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> AdvisorClient.offer(packet.event));
        context.setPacketHandled(true);
    }
}
