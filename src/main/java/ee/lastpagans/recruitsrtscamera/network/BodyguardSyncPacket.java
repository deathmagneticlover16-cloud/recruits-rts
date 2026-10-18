package ee.lastpagans.recruitsrtscamera.network;

import ee.lastpagans.recruitsrtscamera.client.ClientCommanderState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record BodyguardSyncPacket(UUID groupId, boolean followPlayer) {
    public static void encode(BodyguardSyncPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.groupId != null);
        if (packet.groupId != null) buffer.writeUUID(packet.groupId);
        buffer.writeBoolean(packet.followPlayer);
    }

    public static BodyguardSyncPacket decode(FriendlyByteBuf buffer) {
        UUID group = buffer.readBoolean() ? buffer.readUUID() : null;
        return new BodyguardSyncPacket(group, buffer.readBoolean());
    }

    public static void handle(BodyguardSyncPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> ClientCommanderState.setBodyguardState(packet.groupId, packet.followPlayer));
        context.setPacketHandled(true);
    }
}
