package ee.lastpagans.recruitsrtscamera.network;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.persistence.RTSGroupTacticsSavedData;
import ee.lastpagans.recruitsrtscamera.server.ServerPacketGuard;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Explicit group-level permission to consume tagged melee weapons as thrown missiles. */
public record MissileControlPacket(List<UUID> groupIds, boolean enabled) {
    public MissileControlPacket {
        int size = Math.min(groupIds.size(), ServerGroupResolver.MAX_GROUPS_PER_ORDER);
        groupIds = List.copyOf(groupIds.subList(0, size));
    }

    public static void encode(MissileControlPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.groupIds.size());
        for (UUID id : packet.groupIds) buffer.writeUUID(id);
        buffer.writeBoolean(packet.enabled);
    }

    public static MissileControlPacket decode(FriendlyByteBuf buffer) {
        int declared = Math.max(0, Math.min(128, buffer.readVarInt()));
        List<UUID> ids = new ArrayList<>(Math.min(declared, ServerGroupResolver.MAX_GROUPS_PER_ORDER));
        for (int i = 0; i < declared; i++) {
            UUID id = buffer.readUUID();
            if (ids.size() < ServerGroupResolver.MAX_GROUPS_PER_ORDER) ids.add(id);
        }
        return new MissileControlPacket(ids, buffer.readBoolean());
    }

    public static void handle(MissileControlPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> execute(packet, context.getSender()));
        context.setPacketHandled(true);
    }

    private static void execute(MissileControlPacket packet, ServerPlayer sender) {
        if (!ServerPacketGuard.allow(sender) || packet.groupIds.isEmpty()) return;
        Map<UUID, List<AbstractRecruitEntity>> groups = ServerGroupResolver.resolve(sender, packet.groupIds);
        RTSGroupTacticsSavedData data = RTSGroupTacticsSavedData.get(sender.serverLevel());
        int changed = 0;
        for (UUID groupId : groups.keySet()) {
            data.setUseMissiles(groupId, packet.enabled);
            changed++;
        }
        sender.displayClientMessage(Component.literal(
                (packet.enabled ? "Use Missiles" : "Hold Missiles") + ": " + changed + " group(s)"
        ), true);
        RTSCameraNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> sender),
                MissileStateSyncPacket.forPlayer(sender)
        );
    }
}
