package ee.lastpagans.recruitsrtscamera.network;

import com.talhanation.recruits.world.RecruitsGroup;
import com.talhanation.recruits.world.RecruitsGroupsSaveData;
import ee.lastpagans.recruitsrtscamera.client.RTSCameraController;
import ee.lastpagans.recruitsrtscamera.formation.FormationKind;
import ee.lastpagans.recruitsrtscamera.formation.FormationMode;
import ee.lastpagans.recruitsrtscamera.persistence.RTSFormationSavedData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Small reconnect sync so the client HUD also remembers persistent formation state. */
public record FormationStateSyncPacket(Map<UUID, FormationKind> formations, Set<UUID> customGroups, Set<UUID> looseSpacingGroups) {
    private static final int MAX_SYNC_GROUPS = 128;

    public FormationStateSyncPacket {
        formations = Map.copyOf(new LinkedHashMap<>(formations));
        customGroups = Set.copyOf(new LinkedHashSet<>(customGroups));
        looseSpacingGroups = Set.copyOf(new LinkedHashSet<>(looseSpacingGroups));
    }

    public static FormationStateSyncPacket forPlayer(ServerPlayer player) {
        Map<UUID, FormationKind> formations = new LinkedHashMap<>();
        Set<UUID> custom = new LinkedHashSet<>();
        Set<UUID> loose = new LinkedHashSet<>();
        if (player == null) {
            return new FormationStateSyncPacket(formations, custom, loose);
        }
        RTSFormationSavedData saved = RTSFormationSavedData.get(player.serverLevel());
        for (RecruitsGroup group : RecruitsGroupsSaveData.get(player.serverLevel()).getAllGroups()) {
            if (formations.size() >= MAX_SYNC_GROUPS) {
                break;
            }
            if (group == null || group.removed || !player.getUUID().equals(group.getPlayerUUID())) {
                continue;
            }
            RTSFormationSavedData.GroupState state = saved.get(group.getUUID());
            if (state == null) {
                continue;
            }
            formations.put(group.getUUID(), state.mode() == FormationMode.CUSTOM ? state.preferred() : state.active());
            if (state.mode() == FormationMode.CUSTOM) {
                custom.add(group.getUUID());
            } else if (state.spacing() >= 1.75D) {
                loose.add(group.getUUID());
            }
        }
        return new FormationStateSyncPacket(formations, custom, loose);
    }

    public static void encode(FormationStateSyncPacket packet, FriendlyByteBuf buffer) {
        int size = Math.min(MAX_SYNC_GROUPS, packet.formations.size());
        buffer.writeVarInt(size);
        int written = 0;
        for (Map.Entry<UUID, FormationKind> entry : packet.formations.entrySet()) {
            if (written++ >= size) {
                break;
            }
            buffer.writeUUID(entry.getKey());
            buffer.writeEnum(entry.getValue());
            buffer.writeBoolean(packet.customGroups.contains(entry.getKey()));
            buffer.writeBoolean(packet.looseSpacingGroups.contains(entry.getKey()));
        }
    }

    public static FormationStateSyncPacket decode(FriendlyByteBuf buffer) {
        int size = Math.max(0, Math.min(MAX_SYNC_GROUPS, buffer.readVarInt()));
        Map<UUID, FormationKind> formations = new LinkedHashMap<>();
        Set<UUID> custom = new LinkedHashSet<>();
        Set<UUID> loose = new LinkedHashSet<>();
        for (int i = 0; i < size; i++) {
            UUID id = buffer.readUUID();
            FormationKind kind = buffer.readEnum(FormationKind.class);
            boolean isCustom = buffer.readBoolean();
            boolean isLoose = buffer.readBoolean();
            formations.put(id, kind);
            if (isCustom) {
                custom.add(id);
            }
            if (isLoose) {
                loose.add(id);
            }
        }
        return new FormationStateSyncPacket(formations, custom, loose);
    }

    public static void handle(FormationStateSyncPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                RTSCameraController.applyPersistentFormationSync(
                        packet.formations, packet.customGroups, packet.looseSpacingGroups
                )
        ));
        context.setPacketHandled(true);
    }
}
