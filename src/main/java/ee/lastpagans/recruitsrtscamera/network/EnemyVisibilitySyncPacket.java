package ee.lastpagans.recruitsrtscamera.network;

import ee.lastpagans.recruitsrtscamera.client.ClientEnemyVisibilityState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Authoritative hostile formation visibility for one commander. */
public record EnemyVisibilitySyncPacket(Map<UUID, Boolean> groups) {
    public EnemyVisibilitySyncPacket { groups = Map.copyOf(groups); }

    public static void encode(EnemyVisibilitySyncPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.groups.size());
        packet.groups.forEach((id, visible) -> { buffer.writeUUID(id); buffer.writeBoolean(visible); });
    }

    public static EnemyVisibilitySyncPacket decode(FriendlyByteBuf buffer) {
        int count = Math.min(1024, Math.max(0, buffer.readVarInt()));
        Map<UUID, Boolean> groups = new HashMap<>();
        for (int i = 0; i < count; i++) groups.put(buffer.readUUID(), buffer.readBoolean());
        return new EnemyVisibilitySyncPacket(groups);
    }

    public static void handle(EnemyVisibilitySyncPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> ClientEnemyVisibilityState.apply(packet.groups));
        context.setPacketHandled(true);
    }
}
