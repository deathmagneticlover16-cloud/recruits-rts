package ee.lastpagans.recruitsrtscamera.network;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.IStrategicFire;
import com.talhanation.recruits.entities.SiegeEngineerEntity;
import ee.lastpagans.recruitsrtscamera.compat.OrderRelayCompat;
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

/** H = hold fire, J = fire at will. */
public record FireControlPacket(List<UUID> groupIds, Mode mode) {
    public enum Mode { HOLD_FIRE, FIRE_AT_WILL }

    public FireControlPacket {
        int size = Math.min(groupIds.size(), ServerGroupResolver.MAX_GROUPS_PER_ORDER);
        groupIds = List.copyOf(groupIds.subList(0, size));
        mode = mode == null ? Mode.HOLD_FIRE : mode;
    }

    public static void encode(FireControlPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.groupIds.size());
        for (UUID id : packet.groupIds) {
            buffer.writeUUID(id);
        }
        buffer.writeEnum(packet.mode);
    }

    public static FireControlPacket decode(FriendlyByteBuf buffer) {
        int declared = Math.max(0, Math.min(128, buffer.readVarInt()));
        List<UUID> ids = new ArrayList<>(Math.min(declared, ServerGroupResolver.MAX_GROUPS_PER_ORDER));
        for (int i = 0; i < declared; i++) {
            UUID id = buffer.readUUID();
            if (ids.size() < ServerGroupResolver.MAX_GROUPS_PER_ORDER) {
                ids.add(id);
            }
        }
        return new FireControlPacket(ids, buffer.readEnum(Mode.class));
    }

    public static void handle(FireControlPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        String relayCommand = packet.mode == Mode.HOLD_FIRE ? "order_cease_fire" : "order_fire_at_will";
        context.enqueueWork(() -> OrderRelayCompat.executeOrRelay(
                context, packet.groupIds(), relayCommand,
                () -> execute(packet, context.getSender())
        ));
        context.setPacketHandled(true);
    }

    private static void execute(FireControlPacket packet, ServerPlayer sender) {
        if (!ServerPacketGuard.allow(sender) || packet.groupIds.isEmpty()) {
            return;
        }
        Map<UUID, List<AbstractRecruitEntity>> groups = ServerGroupResolver.resolve(sender, packet.groupIds);
        var data = ee.lastpagans.recruitsrtscamera.persistence.RTSGroupTacticsSavedData.get(sender.serverLevel());
        for (UUID id : groups.keySet()) data.setSettings(id, data.settings(id).withHoldFire(packet.mode == Mode.HOLD_FIRE));
        int changed = 0;
        int artillery = 0;
        for (List<AbstractRecruitEntity> recruits : groups.values()) {
            for (AbstractRecruitEntity recruit : recruits) {
                if (!(recruit instanceof IStrategicFire strategic)) {
                    continue;
                }
                if (recruit instanceof SiegeEngineerEntity engineer) {
                    boolean ok = packet.mode == Mode.HOLD_FIRE
                            ? SiegeWeaponsCompat.holdFire(engineer)
                            : SiegeWeaponsCompat.fireAtWill(engineer);
                    if (ok) {
                        artillery++;
                        changed++;
                    }
                    continue;
                }
                strategic.setShouldStrategicFire(false);
                recruit.setShouldRanged(packet.mode == Mode.FIRE_AT_WILL
                        && data.settings(recruit.getGroup()).combat() != ee.lastpagans.recruitsrtscamera.combat.TacticalSettings.CombatMode.MELEE);
                if (packet.mode == Mode.HOLD_FIRE) {
                    recruit.setTarget(null);
                }
                changed++;
            }
        }
        String label = packet.mode == Mode.HOLD_FIRE ? "Hold fire" : "Fire at will";
        sender.displayClientMessage(Component.literal(
                label + ": " + changed + " ranged unit(s)"
                        + (artillery > 0 ? " · " + artillery + " artillery crew(s)" : "")
        ), true);
        RTSCameraNetwork.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> sender),
                TacticsStateSyncPacket.forPlayer(sender));
    }
}
