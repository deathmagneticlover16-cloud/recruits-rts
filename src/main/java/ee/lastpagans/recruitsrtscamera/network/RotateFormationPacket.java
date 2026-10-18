package ee.lastpagans.recruitsrtscamera.network;

import ee.lastpagans.recruitsrtscamera.compat.OrderRelayCompat;
import ee.lastpagans.recruitsrtscamera.server.ServerPacketGuard;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.util.FormationUtils;
import com.talhanation.recruits.util.RecruitCommanderUtil;
import ee.lastpagans.recruitsrtscamera.compat.SiegeWeaponsCompat;
import ee.lastpagans.recruitsrtscamera.compat.SmallShipsCompat;
import ee.lastpagans.recruitsrtscamera.formation.FormationRotationMath;
import ee.lastpagans.recruitsrtscamera.formation.FormationService;
import ee.lastpagans.recruitsrtscamera.movement.RTSMovementBoostManager;
import net.minecraft.core.BlockPos;
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

/** Rotates the selected army stencil around its own centre without rebuilding it. */
public record RotateFormationPacket(List<UUID> groupIds, float deltaDegrees) {
    public RotateFormationPacket {
        int size = Math.min(groupIds.size(), ServerGroupResolver.MAX_GROUPS_PER_ORDER);
        groupIds = List.copyOf(groupIds.subList(0, size));
        if (!Float.isFinite(deltaDegrees)) {
            deltaDegrees = 0.0F;
        }
        deltaDegrees = Math.max(-180.0F, Math.min(180.0F, deltaDegrees));
    }

    public static void encode(RotateFormationPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.groupIds.size());
        for (UUID id : packet.groupIds) {
            buffer.writeUUID(id);
        }
        buffer.writeFloat(packet.deltaDegrees);
    }

    public static RotateFormationPacket decode(FriendlyByteBuf buffer) {
        int declaredSize = Math.max(0, Math.min(128, buffer.readVarInt()));
        List<UUID> groups = new ArrayList<>(Math.min(declaredSize, ServerGroupResolver.MAX_GROUPS_PER_ORDER));
        for (int i = 0; i < declaredSize; i++) {
            UUID id = buffer.readUUID();
            if (groups.size() < ServerGroupResolver.MAX_GROUPS_PER_ORDER) {
                groups.add(id);
            }
        }
        return new RotateFormationPacket(groups, buffer.readFloat());
    }

    public static void handle(RotateFormationPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> OrderRelayCompat.executeOrRelay(
                context, packet.groupIds(), "order_face",
                () -> execute(packet, context.getSender())
        ));
        context.setPacketHandled(true);
    }

    private static void execute(RotateFormationPacket packet, ServerPlayer sender) {
        if (!ServerPacketGuard.allow(sender) || packet.groupIds.isEmpty() || Math.abs(packet.deltaDegrees) < 0.25F) {
            return;
        }
        RouteManager.cancel(sender, packet.groupIds);
        Map<UUID, List<AbstractRecruitEntity>> groups = ServerGroupResolver.resolve(sender, packet.groupIds);
        if (groups.isEmpty()) {
            sender.displayClientMessage(Component.literal("No loaded owned recruits were found."), true);
            return;
        }

        List<UUID> ids = new ArrayList<>(groups.keySet());
        List<Vec3> centres = new ArrayList<>(ids.size());
        double pivotX = 0.0D;
        double pivotY = 0.0D;
        double pivotZ = 0.0D;
        for (UUID id : ids) {
            Vec3 centre = groupCenter(groups.get(id));
            centres.add(centre);
            pivotX += centre.x;
            pivotY += centre.y;
            pivotZ += centre.z;
        }
        double inv = 1.0D / ids.size();
        pivotX *= inv;
        pivotY *= inv;
        pivotZ *= inv;
        FormationRotationMath.Point pivot = new FormationRotationMath.Point(pivotX, pivotZ);

        int rotatedGroups = 0;
        int recruitsOrdered = 0;
        int artilleryGuns = 0;
        int ships = 0;
        for (int i = 0; i < ids.size(); i++) {
            UUID groupId = ids.get(i);
            List<AbstractRecruitEntity> recruits = groups.get(groupId);
            Vec3 oldCentre = centres.get(i);
            FormationRotationMath.Point rotatedPoint = FormationRotationMath.rotate(
                    new FormationRotationMath.Point(oldCentre.x, oldCentre.z),
                    pivot,
                    packet.deltaDegrees
            );
            BlockPos surface = FormationUtils.getPositionOrSurface(
                    sender.serverLevel(),
                    BlockPos.containing(rotatedPoint.x(), pivotY, rotatedPoint.z())
            );
            Vec3 newCentre = new Vec3(rotatedPoint.x(), surface.getY(), rotatedPoint.z());

            RecruitCommanderUtil.setRecruitsClearTargets(recruits);
            float oldFacing = FormationService.currentFacing(
                    sender.serverLevel(), groupId, recruits, sender.getYRot()
            );
            float newFacing = FormationRotationMath.rotateYaw(oldFacing, packet.deltaDegrees);
            double batteryWidth = SiegeWeaponsCompat.currentBatteryWidth(recruits, oldFacing);
            SiegeWeaponsCompat.BatteryOrder battery = SiegeWeaponsCompat.commandBatteryLine(
                    sender, recruits, newCentre, newFacing, batteryWidth > 0.0D ? batteryWidth : null
            );
            double fleetWidth = SmallShipsCompat.currentFleetWidth(recruits, oldFacing);
            SmallShipsCompat.FleetOrder fleet = SmallShipsCompat.commandFleetFormation(
                    sender, recruits, newCentre, newFacing,
                    FormationService.activeKind(sender.serverLevel(), groupId), null
            );
            List<AbstractRecruitEntity> formationRecruits = SiegeWeaponsCompat.withoutAttachedArtillery(recruits);
            formationRecruits = SmallShipsCompat.withoutAttachedShips(formationRecruits);

            boolean rotated = false;
            if (!formationRecruits.isEmpty()) {
                if (FormationService.rotatePreserved(
                        sender,
                        groupId,
                        formationRecruits,
                        newCentre,
                        packet.deltaDegrees
                )) {
                    rotated = true;
                } else {
                    // Native fallback can preserve the rotated group centre even when no
                    // formation engine is available; internal facing simply remains native.
                    RecruitCommanderUtil.setRecruitsMove(formationRecruits, surface);
                }
                RTSMovementBoostManager.registerRotationMove(sender, groupId, formationRecruits);
            }
            if (battery.movedAny()) {
                artilleryGuns += battery.guns();
                FormationService.rememberFacing(sender.serverLevel(), groupId, newFacing);
                rotated = true;
            }
            if (fleet.movedAny()) {
                ships += fleet.ships();
                FormationService.rememberFacing(sender.serverLevel(), groupId, newFacing);
                rotated = true;
            }
            if (rotated) {
                rotatedGroups++;
            }
            recruitsOrdered += recruits.size();
        }

        sender.displayClientMessage(Component.literal(
                "Rotate formation: " + Math.round(packet.deltaDegrees) + "° · "
                        + rotatedGroups + "/" + groups.size() + " group(s) · " + recruitsOrdered + " recruits"
                        + (artilleryGuns > 0 ? " · " + artilleryGuns + " gun(s) redeployed" : "")
                        + (ships > 0 ? " · " + ships + " ship(s) redeployed" : "")
        ), true);
    }

    private static Vec3 groupCenter(List<AbstractRecruitEntity> recruits) {
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        for (AbstractRecruitEntity recruit : recruits) {
            x += recruit.getX();
            y += recruit.getY();
            z += recruit.getZ();
        }
        double inv = 1.0D / Math.max(1, recruits.size());
        return new Vec3(x * inv, y * inv, z * inv);
    }
}
