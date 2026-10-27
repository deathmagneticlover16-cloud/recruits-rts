package ee.lastpagans.recruitsrtscamera.network;

import ee.lastpagans.recruitsrtscamera.battle.GroupBattleSnapshot;
import ee.lastpagans.recruitsrtscamera.client.ClientBattleState;
import ee.lastpagans.recruitsrtscamera.troops.TroopStatProfile;
import ee.lastpagans.recruitsrtscamera.troops.TroopStatistic;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public record BattleStateSyncPacket(Map<UUID, GroupBattleSnapshot> groups) {
    public BattleStateSyncPacket {
        groups = Map.copyOf(groups);
    }

    public static void encode(BattleStateSyncPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.groups.size());
        packet.groups.forEach((id, state) -> {
            buffer.writeUUID(id);
            buffer.writeFloat(state.fatigue());
            buffer.writeFloat(state.morale());
            buffer.writeByte(state.moraleState());
            buffer.writeVarInt(Math.max(0, state.combatBaseline()));
            buffer.writeVarInt(Math.max(0, state.currentCount()));
            buffer.writeBoolean(state.routedThisBattle());
            for (TroopStatistic statistic : TroopStatistic.values()) {
                buffer.writeFloat((float) state.statistics().value(statistic));
            }
            buffer.writeFloat(state.carriedWeight());
            buffer.writeFloat(state.carryCapacity());
            buffer.writeFloat(state.foodSupply());
        });
    }

    public static BattleStateSyncPacket decode(FriendlyByteBuf buffer) {
        int size = Math.max(0, Math.min(128, buffer.readVarInt()));
        Map<UUID, GroupBattleSnapshot> groups = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            UUID id = buffer.readUUID();
            float fatigue = buffer.readFloat();
            float morale = buffer.readFloat();
            int moraleState = buffer.readByte();
            int baseline = buffer.readVarInt();
            int current = buffer.readVarInt();
            boolean routed = buffer.readBoolean();
            TroopStatProfile.Builder statistics = TroopStatProfile.builder();
            for (TroopStatistic statistic : TroopStatistic.values()) {
                statistics.set(statistic, buffer.readFloat());
            }
            groups.put(id, new GroupBattleSnapshot(fatigue, morale, moraleState,
                    baseline, current, routed, statistics.build(),
                    buffer.readFloat(), buffer.readFloat(), buffer.readFloat()));
        }
        return new BattleStateSyncPacket(groups);
    }

    public static void handle(BattleStateSyncPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> ClientBattleState.replace(packet.groups));
        context.setPacketHandled(true);
    }
}
