package ee.lastpagans.recruitsrtscamera.network;

import ee.lastpagans.recruitsrtscamera.server.BodyguardManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Toggle whether the assigned Bodyguard reforms around/follows the real player outside RTS view. */
public record BodyguardFollowCommandPacket(boolean enabled) {
    public static void encode(BodyguardFollowCommandPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.enabled);
    }

    public static BodyguardFollowCommandPacket decode(FriendlyByteBuf buffer) {
        return new BodyguardFollowCommandPacket(buffer.readBoolean());
    }

    public static void handle(BodyguardFollowCommandPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer player = context.getSender();
        if (player != null) {
            context.enqueueWork(() -> BodyguardManager.setFollowPlayer(player, packet.enabled));
        }
        context.setPacketHandled(true);
    }
}
