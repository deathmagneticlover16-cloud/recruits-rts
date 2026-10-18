package ee.lastpagans.recruitsrtscamera.network;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.movement.RTSMovementBoostManager;
import ee.lastpagans.recruitsrtscamera.server.ServerPacketGuard;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Changes the pace of selected groups without issuing a new destination. */
public record MovementPacePacket(List<UUID> groupIds, boolean running) {
    public MovementPacePacket {
        int size = Math.min(groupIds.size(), ServerGroupResolver.MAX_GROUPS_PER_ORDER);
        groupIds = List.copyOf(groupIds.subList(0, size));
    }

    public static void encode(MovementPacePacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.groupIds.size());
        for (UUID id : packet.groupIds) buffer.writeUUID(id);
        buffer.writeBoolean(packet.running);
    }

    public static MovementPacePacket decode(FriendlyByteBuf buffer) {
        int declared = Math.max(0, Math.min(128, buffer.readVarInt()));
        List<UUID> ids = new ArrayList<>(Math.min(declared, ServerGroupResolver.MAX_GROUPS_PER_ORDER));
        for (int i = 0; i < declared; i++) {
            UUID id = buffer.readUUID();
            if (ids.size() < ServerGroupResolver.MAX_GROUPS_PER_ORDER) ids.add(id);
        }
        return new MovementPacePacket(ids, buffer.readBoolean());
    }

    public static void handle(MovementPacePacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> execute(packet, context.getSender()));
        context.setPacketHandled(true);
    }

    private static void execute(MovementPacePacket packet, ServerPlayer sender) {
        if (!ServerPacketGuard.allow(sender) || packet.groupIds.isEmpty()) return;
        Map<UUID, List<AbstractRecruitEntity>> groups = ServerGroupResolver.resolve(sender, packet.groupIds);
        for (Map.Entry<UUID, List<AbstractRecruitEntity>> entry : groups.entrySet()) {
            if (packet.running) {
                RTSMovementBoostManager.registerHastyMove(sender, entry.getKey(), entry.getValue());
            } else {
                RTSMovementBoostManager.registerSlowMove(sender, entry.getKey(), entry.getValue());
            }
        }
    }
}
