package ee.lastpagans.recruitsrtscamera.network;

import ee.lastpagans.recruitsrtscamera.server.BodyguardManager;
import ee.lastpagans.recruitsrtscamera.server.ServerPacketGuard;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record BodyguardCommandPacket(UUID groupId) {
    public static void encode(BodyguardCommandPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.groupId != null);
        if (packet.groupId != null) buffer.writeUUID(packet.groupId);
    }

    public static BodyguardCommandPacket decode(FriendlyByteBuf buffer) {
        return new BodyguardCommandPacket(buffer.readBoolean() ? buffer.readUUID() : null);
    }

    public static void handle(BodyguardCommandPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (!ServerPacketGuard.allow(player)) return;
            boolean ok = BodyguardManager.setBodyguard(player, packet.groupId);
            if (player != null) {
                player.displayClientMessage(Component.literal(ok
                        ? (packet.groupId == null ? "General's Bodyguard cleared." : "General's Bodyguard assigned.")
                        : "Bodyguard assignment failed: stand near one owned group and select only it."), true);
            }
        });
        context.setPacketHandled(true);
    }
}
