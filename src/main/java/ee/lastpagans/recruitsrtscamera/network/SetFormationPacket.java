package ee.lastpagans.recruitsrtscamera.network;

import ee.lastpagans.recruitsrtscamera.server.ServerPacketGuard;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.compat.OrderRelayCompat;
import ee.lastpagans.recruitsrtscamera.compat.SiegeWeaponsCompat;
import ee.lastpagans.recruitsrtscamera.compat.SmallShipsCompat;
import ee.lastpagans.recruitsrtscamera.formation.FormationKind;
import ee.lastpagans.recruitsrtscamera.formation.FormationService;
import ee.lastpagans.recruitsrtscamera.movement.RTSMovementBoostManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Applies one preset formation to all currently selected groups without moving them away. */
public record SetFormationPacket(List<UUID> groupIds, FormationKind kind) {
    public SetFormationPacket {
        int size = Math.min(groupIds.size(), ServerGroupResolver.MAX_GROUPS_PER_ORDER);
        groupIds = List.copyOf(groupIds.subList(0, size));
        if (kind == null) {
            kind = FormationKind.MARCH;
        }
    }

    public static void encode(SetFormationPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.groupIds.size());
        for (UUID groupId : packet.groupIds) {
            buffer.writeUUID(groupId);
        }
        buffer.writeVarInt(packet.kind.ordinal());
    }

    public static SetFormationPacket decode(FriendlyByteBuf buffer) {
        int declaredSize = Math.max(0, Math.min(128, buffer.readVarInt()));
        List<UUID> groups = new ArrayList<>(Math.min(declaredSize, ServerGroupResolver.MAX_GROUPS_PER_ORDER));
        for (int i = 0; i < declaredSize; i++) {
            UUID id = buffer.readUUID();
            if (groups.size() < ServerGroupResolver.MAX_GROUPS_PER_ORDER) {
                groups.add(id);
            }
        }
        int ordinal = buffer.readVarInt();
        FormationKind[] kinds = FormationKind.values();
        FormationKind kind = ordinal >= 0 && ordinal < kinds.length ? kinds[ordinal] : FormationKind.MARCH;
        return new SetFormationPacket(groups, kind);
    }

    public static void handle(SetFormationPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        String relayCommand = relayCommand(packet.kind);
        context.enqueueWork(() -> OrderRelayCompat.executeOrRelay(
                context, packet.groupIds(), relayCommand,
                () -> execute(packet, context.getSender())
        ));
        context.setPacketHandled(true);
    }

    private static void execute(SetFormationPacket packet, ServerPlayer sender) {
        if (!ServerPacketGuard.allow(sender) || packet.groupIds.isEmpty()) {
            return;
        }
        RouteManager.cancel(sender, packet.groupIds);

        Map<UUID, List<AbstractRecruitEntity>> groups = ServerGroupResolver.resolve(sender, packet.groupIds);
        int appliedGroups = 0;
        int appliedRecruits = 0;
        int artilleryGuns = 0;
        int ships = 0;

        for (Map.Entry<UUID, List<AbstractRecruitEntity>> entry : groups.entrySet()) {
            UUID groupId = entry.getKey();
            List<AbstractRecruitEntity> allRecruits = entry.getValue();
            FormationService.remember(sender.serverLevel(), groupId, packet.kind);

            List<AbstractRecruitEntity> formationRecruits = SiegeWeaponsCompat.withoutAttachedArtillery(allRecruits);
            formationRecruits = SmallShipsCompat.withoutAttachedShips(formationRecruits);
            int guns = SiegeWeaponsCompat.attachedGuns(allRecruits);
            int groupShips = SmallShipsCompat.attachedCaptains(allRecruits).size();
            artilleryGuns += guns;
            ships += groupShips;

            boolean groupApplied = false;
            if (!formationRecruits.isEmpty()
                    && FormationService.setAndReform(sender, groupId, formationRecruits, packet.kind)) {
                RTSMovementBoostManager.registerFormationMove(sender, groupId, formationRecruits);
                appliedRecruits += formationRecruits.size();
                groupApplied = true;
            }

            if (groupShips > 0) {
                Vec3 fleetCenter = SmallShipsCompat.fleetCenter(allRecruits);
                float facing = FormationService.currentFacing(sender.serverLevel(), groupId, allRecruits, sender.getYRot());
                SmallShipsCompat.FleetOrder fleet = SmallShipsCompat.commandFleetFormation(
                        sender, allRecruits, fleetCenter, facing, packet.kind, null
                );
                if (fleet.movedAny()) {
                    FormationService.rememberFacing(sender.serverLevel(), groupId, facing);
                    groupApplied = true;
                }
            }

            // Artillery deliberately keeps its dedicated wide battery geometry when G is
            // pressed. Its formation preference is remembered for any ordinary recruits.
            if (guns > 0 && formationRecruits.isEmpty() && groupShips == 0) {
                groupApplied = true;
            }
            if (groupApplied) {
                appliedGroups++;
            }
        }

        if (groups.isEmpty()) {
            sender.displayClientMessage(Component.literal("No loaded owned recruits were found."), true);
        } else if (appliedGroups == 0) {
            sender.displayClientMessage(Component.literal(
                    "Formation preference set to " + packet.kind.displayName()
                            + ", but the tactical formation runtime could not apply it."
            ), true);
        } else {
            sender.displayClientMessage(Component.literal(
                    "Formation: " + packet.kind.displayName() + " · " + appliedGroups
                            + " group(s), " + appliedRecruits + " recruits"
                            + (artilleryGuns > 0 ? " · artillery remains in battery line" : "")
                            + (ships > 0 ? " · ships keep fleet geometry" : "")
            ), true);
        }
    }
    private static String relayCommand(FormationKind kind) {
        return switch (kind) {
            case MARCH -> "formation_march";
            case LINE -> "formation_line";
            case SQUARE -> "formation_square";
            case TRIANGLE -> "formation_wedge";
            case RING -> "formation_h_circle";
            case CIRCLE -> "formation_circle";
            case HOLLOW_SQUARE -> "formation_h_square";
            case VEE -> "formation_vee";
            case BUBBLE -> "formation_none";
        };
    }

}
