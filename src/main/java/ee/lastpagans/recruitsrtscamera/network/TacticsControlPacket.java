package ee.lastpagans.recruitsrtscamera.network;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.combat.TacticalOrders;
import ee.lastpagans.recruitsrtscamera.compat.OrderRelayCompat;
import ee.lastpagans.recruitsrtscamera.server.ServerPacketGuard;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import java.util.*;
import java.util.function.Supplier;

public record TacticsControlPacket(List<UUID> groupIds, Action action) {
    public enum Action {
        SKIRMISH_ON, SKIRMISH_OFF, RANGED, MELEE, NATIVE_COMBAT,
        MOUNT, DISMOUNT, SHIELD_FRONT, SHIELD_UP, SHIELD_AUTO, SHIELD_NATIVE,
        SECOND_WIND, HOLD_LINE, WAR_CRY
    }
    public TacticsControlPacket {
        groupIds = List.copyOf(groupIds.subList(0, Math.min(groupIds.size(), ServerGroupResolver.MAX_GROUPS_PER_ORDER)));
    }
    public static void encode(TacticsControlPacket p, FriendlyByteBuf b) {
        b.writeVarInt(p.groupIds.size());
        p.groupIds.forEach(b::writeUUID);
        b.writeEnum(p.action);
    }
    public static TacticsControlPacket decode(FriendlyByteBuf b) {
        int size = b.readVarInt();
        if (size < 0 || size > ServerGroupResolver.MAX_GROUPS_PER_ORDER) throw new IllegalArgumentException("Invalid group count");
        List<UUID> ids = new ArrayList<>(size);
        for (int i = 0; i < size; i++) ids.add(b.readUUID());
        return new TacticsControlPacket(ids, b.readEnum(Action.class));
    }
    public static void handle(TacticsControlPacket p, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        String relay = switch (p.action) {
            case RANGED -> "order_fire_at_will";
            case MELEE, WAR_CRY -> "order_attack";
            case MOUNT, SKIRMISH_ON -> "order_forward";
            default -> "order_move_and_hold";
        };
        context.enqueueWork(() -> OrderRelayCompat.executeOrRelay(context, p.groupIds, relay,
                () -> execute(p, context.getSender())));
        context.setPacketHandled(true);
    }
    private static void execute(TacticsControlPacket p, ServerPlayer sender) {
        if (!ServerPacketGuard.allow(sender)) return;
        Map<UUID, List<AbstractRecruitEntity>> groups = ServerGroupResolver.resolve(sender, p.groupIds);
        if (groups.isEmpty()) return;
        TacticalOrders.execute(sender, groups, p.action);
        RTSCameraNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender), TacticsStateSyncPacket.forPlayer(sender));
    }
}
