package ee.lastpagans.recruitsrtscamera.network;

import ee.lastpagans.recruitsrtscamera.client.ClientMoraleItemState;
import ee.lastpagans.recruitsrtscamera.persistence.RTSMoraleItemSavedData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

public record MoraleItemsSyncPacket(List<String> items) {
    private static final int MAX_ITEMS = 512;

    public MoraleItemsSyncPacket {
        items = List.copyOf(items == null ? List.of() : items);
    }

    public static MoraleItemsSyncPacket all(ServerPlayer player) {
        List<String> ids = player == null ? List.of() : RTSMoraleItemSavedData.get(player.serverLevel())
                .snapshot().stream().map(Object::toString).toList();
        return new MoraleItemsSyncPacket(ids);
    }

    public static MoraleItemsSyncPacket of(Collection<String> items) {
        return new MoraleItemsSyncPacket(items == null ? List.of() : new ArrayList<>(items));
    }

    public static void encode(MoraleItemsSyncPacket packet, FriendlyByteBuf buffer) {
        int count = Math.min(MAX_ITEMS, packet.items.size());
        buffer.writeVarInt(count);
        for (int i = 0; i < count; i++) buffer.writeUtf(packet.items.get(i), 256);
    }

    public static MoraleItemsSyncPacket decode(FriendlyByteBuf buffer) {
        int count = Math.max(0, Math.min(MAX_ITEMS, buffer.readVarInt()));
        List<String> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) items.add(buffer.readUtf(256));
        return new MoraleItemsSyncPacket(items);
    }

    public static void handle(MoraleItemsSyncPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientMoraleItemState.apply(packet.items)));
        context.setPacketHandled(true);
    }
}
