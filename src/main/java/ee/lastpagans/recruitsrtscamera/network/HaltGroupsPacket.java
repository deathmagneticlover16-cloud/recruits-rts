package ee.lastpagans.recruitsrtscamera.network;

import ee.lastpagans.recruitsrtscamera.combat.RTSGroupAttackManager;
import ee.lastpagans.recruitsrtscamera.movement.RTSMovementBoostManager;

import ee.lastpagans.recruitsrtscamera.compat.OrderRelayCompat;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.CaptainEntity;
import com.talhanation.recruits.entities.SiegeEngineerEntity;
import com.talhanation.recruits.util.RecruitCommanderUtil;
import ee.lastpagans.recruitsrtscamera.compat.BetterFormationsCompat;
import ee.lastpagans.recruitsrtscamera.compat.SmallShipsCompat;
import ee.lastpagans.recruitsrtscamera.compat.SiegeWeaponsCompat;
import ee.lastpagans.recruitsrtscamera.server.ServerPacketGuard;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Immediately stop selected groups without erasing their remembered formation. */
public record HaltGroupsPacket(List<UUID> groupIds) {
    public HaltGroupsPacket {
        int size = Math.min(groupIds.size(), ServerGroupResolver.MAX_GROUPS_PER_ORDER);
        groupIds = List.copyOf(groupIds.subList(0, size));
    }

    public static void encode(HaltGroupsPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.groupIds.size());
        for (UUID id : packet.groupIds) buffer.writeUUID(id);
    }

    public static HaltGroupsPacket decode(FriendlyByteBuf buffer) {
        int declared = Math.max(0, Math.min(128, buffer.readVarInt()));
        List<UUID> groups = new ArrayList<>();
        for (int i = 0; i < declared; i++) {
            UUID id = buffer.readUUID();
            if (groups.size() < ServerGroupResolver.MAX_GROUPS_PER_ORDER) groups.add(id);
        }
        return new HaltGroupsPacket(groups);
    }

    public static void handle(HaltGroupsPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> OrderRelayCompat.executeOrRelay(
                context, packet.groupIds(), "order_move_and_hold",
                () -> execute(packet, context.getSender())
        ));
        context.setPacketHandled(true);
    }

    private static void execute(HaltGroupsPacket packet, ServerPlayer sender) {
        if (!ServerPacketGuard.allow(sender) || packet.groupIds.isEmpty()) {
            return;
        }
        Map<UUID, List<AbstractRecruitEntity>> groups = ServerGroupResolver.resolve(sender, packet.groupIds);
        if (groups.isEmpty()) {
            return;
        }
        RouteManager.cancel(sender, packet.groupIds);
        RTSGroupAttackManager.cancelOwned(sender, packet.groupIds);
        RTSMovementBoostManager.cancelForcedOrders(packet.groupIds);
        int stopped = 0;
        for (Map.Entry<UUID, List<AbstractRecruitEntity>> entry : groups.entrySet()) {
            BetterFormationsCompat.haltFormation(entry.getKey());
            List<AbstractRecruitEntity> recruits = entry.getValue();
            RecruitCommanderUtil.setRecruitsClearTargets(recruits);
            for (AbstractRecruitEntity recruit : recruits) {
                recruit.getNavigation().stop();
                recruit.clearMovePos();
                recruit.setShouldMovePos(false);
                recruit.setHoldPos(recruit.position());
                recruit.setFollowState(2);
                recruit.setDeltaMovement(0.0D, recruit.getDeltaMovement().y, 0.0D);
                if (recruit instanceof CaptainEntity captain) {
                    SmallShipsCompat.haltCaptain(captain);
                }
                if (recruit instanceof SiegeEngineerEntity engineer) {
                    SiegeWeaponsCompat.haltEngineer(engineer);
                }
                stopped++;
            }
        }
        sender.displayClientMessage(Component.literal("HALT: " + groups.size() + " group(s), " + stopped + " recruits stopped."), true);
    }
}
