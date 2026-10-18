package ee.lastpagans.recruitsrtscamera.network;

import com.talhanation.recruits.world.RecruitsGroupsSaveData;
import ee.lastpagans.recruitsrtscamera.client.ClientTactics;
import ee.lastpagans.recruitsrtscamera.combat.TacticalSettings;
import ee.lastpagans.recruitsrtscamera.persistence.RTSGroupTacticsSavedData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import java.util.*;
import java.util.function.Supplier;

public record TacticsStateSyncPacket(Map<UUID, TacticalSettings> settings) {
    public TacticsStateSyncPacket { settings = Map.copyOf(settings); }
    public static TacticsStateSyncPacket forPlayer(ServerPlayer player) {
        Map<UUID, TacticalSettings> result = new LinkedHashMap<>();
        var data = RTSGroupTacticsSavedData.get(player.serverLevel());
        for (var group : RecruitsGroupsSaveData.get(player.serverLevel()).getAllGroups()) {
            if (result.size() >= 128) break;
            if (group != null && group.getUUID() != null && !group.removed && player.getUUID().equals(group.getPlayerUUID()))
                result.put(group.getUUID(), data.settings(group.getUUID()));
        }
        return new TacticsStateSyncPacket(result);
    }
    public static void encode(TacticsStateSyncPacket p, FriendlyByteBuf b) {
        b.writeVarInt(p.settings.size());
        p.settings.forEach((id, s) -> {
            b.writeUUID(id); b.writeEnum(s.combat()); b.writeBoolean(s.skirmish());
            b.writeEnum(s.shield()); b.writeBoolean(s.holdFire());
        });
    }
    public static TacticsStateSyncPacket decode(FriendlyByteBuf b) {
        int size = b.readVarInt();
        if (size < 0 || size > 128) throw new IllegalArgumentException("Invalid tactics sync size");
        Map<UUID, TacticalSettings> result = new HashMap<>();
        for (int i = 0; i < size; i++) result.put(b.readUUID(), new TacticalSettings(
                b.readEnum(TacticalSettings.CombatMode.class), b.readBoolean(),
                b.readEnum(TacticalSettings.ShieldMode.class), b.readBoolean()));
        return new TacticsStateSyncPacket(result);
    }
    public static void handle(TacticsStateSyncPacket p, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientTactics.apply(p.settings)));
        context.setPacketHandled(true);
    }
}
