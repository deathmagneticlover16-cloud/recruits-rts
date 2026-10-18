package ee.lastpagans.recruitsrtscamera.network;

import ee.lastpagans.recruitsrtscamera.battle.GroupBattleSnapshot;
import ee.lastpagans.recruitsrtscamera.client.ClientBattleState;
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
        });
    }

    public static BattleStateSyncPacket decode(FriendlyByteBuf buffer) {
        int size = Math.max(0, Math.min(128, buffer.readVarInt()));
        Map<UUID, GroupBattleSnapshot> groups = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            UUID id = buffer.readUUID();
            groups.put(id, new GroupBattleSnapshot(
                    buffer.readFloat(), buffer.readFloat(), buffer.readByte(),
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean()
            ));
        }
        return new BattleStateSyncPacket(groups);
    }

    public static void handle(BattleStateSyncPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> ClientBattleState.replace(packet.groups));
        context.setPacketHandled(true);
    }
}
