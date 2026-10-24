package ee.lastpagans.recruitsrtscamera.network;

import ee.lastpagans.recruitsrtscamera.client.GroupFlagClientState;
import ee.lastpagans.recruitsrtscamera.persistence.RTSGroupFlagSavedData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public record GroupFlagStateSyncPacket(Map<UUID, String> flags, boolean replace) {
    private static final int MAX_FLAGS = 1024;

    public GroupFlagStateSyncPacket {
        flags = Map.copyOf(new LinkedHashMap<>(flags));
    }

    public static GroupFlagStateSyncPacket all(ServerPlayer player) {
        Map<UUID, String> result = new LinkedHashMap<>();
        if (player != null) RTSGroupFlagSavedData.get(player.serverLevel()).snapshot().forEach(
                (group, flag) -> result.put(group, flag.toString()));
        return new GroupFlagStateSyncPacket(result, true);
    }

    public static void encode(GroupFlagStateSyncPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.replace);
        int count = Math.min(MAX_FLAGS, packet.flags.size());
        buffer.writeVarInt(count);
        int written = 0;
        for (Map.Entry<UUID, String> entry : packet.flags.entrySet()) {
            if (written++ >= count) break;
            buffer.writeUUID(entry.getKey());
            buffer.writeUtf(entry.getValue(), 256);
        }
    }

    public static GroupFlagStateSyncPacket decode(FriendlyByteBuf buffer) {
        boolean replace = buffer.readBoolean();
        int count = Math.max(0, Math.min(MAX_FLAGS, buffer.readVarInt()));
        Map<UUID, String> flags = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) flags.put(buffer.readUUID(), buffer.readUtf(256));
        return new GroupFlagStateSyncPacket(flags, replace);
    }

    public static void handle(GroupFlagStateSyncPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> GroupFlagClientState.apply(packet.flags, packet.replace)));
        context.setPacketHandled(true);
    }
}
