package ee.lastpagans.recruitsrtscamera.network;

import com.talhanation.recruits.world.RecruitsGroup;
import com.talhanation.recruits.world.RecruitsGroupsSaveData;
import ee.lastpagans.recruitsrtscamera.client.RTSCameraController;
import ee.lastpagans.recruitsrtscamera.persistence.RTSGroupTacticsSavedData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Reconnect sync for persistent Use Missiles group toggles. */
public record MissileStateSyncPacket(Set<UUID> useMissilesGroups) {
    private static final int MAX_SYNC_GROUPS = 128;

    public MissileStateSyncPacket {
        useMissilesGroups = Set.copyOf(new LinkedHashSet<>(useMissilesGroups));
    }

    public static MissileStateSyncPacket forPlayer(ServerPlayer player) {
        Set<UUID> result = new LinkedHashSet<>();
        if (player == null) return new MissileStateSyncPacket(result);
        RTSGroupTacticsSavedData data = RTSGroupTacticsSavedData.get(player.serverLevel());
        for (RecruitsGroup group : RecruitsGroupsSaveData.get(player.serverLevel()).getAllGroups()) {
            if (result.size() >= MAX_SYNC_GROUPS) break;
            if (group == null || group.removed || group.getUUID() == null
                    || !player.getUUID().equals(group.getPlayerUUID())) continue;
            if (data.useMissiles(group.getUUID())) result.add(group.getUUID());
        }
        return new MissileStateSyncPacket(result);
    }

    public static void encode(MissileStateSyncPacket packet, FriendlyByteBuf buffer) {
        int size = Math.min(MAX_SYNC_GROUPS, packet.useMissilesGroups.size());
        buffer.writeVarInt(size);
        int written = 0;
        for (UUID id : packet.useMissilesGroups) {
            if (written++ >= size) break;
            buffer.writeUUID(id);
        }
    }

    public static MissileStateSyncPacket decode(FriendlyByteBuf buffer) {
        int size = Math.max(0, Math.min(MAX_SYNC_GROUPS, buffer.readVarInt()));
        Set<UUID> result = new LinkedHashSet<>();
        for (int i = 0; i < size; i++) result.add(buffer.readUUID());
        return new MissileStateSyncPacket(result);
    }

    public static void handle(MissileStateSyncPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                RTSCameraController.applyPersistentMissileSync(packet.useMissilesGroups)
        ));
        context.setPacketHandled(true);
    }
}
