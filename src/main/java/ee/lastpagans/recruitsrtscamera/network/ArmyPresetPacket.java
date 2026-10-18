package ee.lastpagans.recruitsrtscamera.network;

import ee.lastpagans.recruitsrtscamera.combat.RTSGroupAttackManager;
import ee.lastpagans.recruitsrtscamera.compat.OrderRelayCompat;
import ee.lastpagans.recruitsrtscamera.server.ServerPacketGuard;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.BowmanEntity;
import com.talhanation.recruits.entities.CrossBowmanEntity;
import com.talhanation.recruits.entities.SiegeEngineerEntity;
import com.talhanation.recruits.util.FormationUtils;
import com.talhanation.recruits.util.RecruitCommanderUtil;
import ee.lastpagans.recruitsrtscamera.compat.SiegeWeaponsCompat;
import ee.lastpagans.recruitsrtscamera.compat.SmallShipsCompat;
import ee.lastpagans.recruitsrtscamera.formation.ArmyDeploymentMath;
import ee.lastpagans.recruitsrtscamera.formation.ArmyPreset;
import ee.lastpagans.recruitsrtscamera.formation.FormationService;
import ee.lastpagans.recruitsrtscamera.movement.RTSMovementBoostManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Applies one high-level multi-group deployment template around the army's current centre. */
public record ArmyPresetPacket(List<UUID> groupIds, ArmyPreset preset) {
    public ArmyPresetPacket {
        int size = Math.min(groupIds.size(), ServerGroupResolver.MAX_GROUPS_PER_ORDER);
        groupIds = List.copyOf(groupIds.subList(0, size));
        preset = preset == null ? ArmyPreset.BALANCED : preset;
    }

    public static void encode(ArmyPresetPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.groupIds.size());
        for (UUID id : packet.groupIds) {
            buffer.writeUUID(id);
        }
        buffer.writeVarInt(packet.preset.ordinal());
    }

    public static ArmyPresetPacket decode(FriendlyByteBuf buffer) {
        int declaredSize = Math.max(0, Math.min(128, buffer.readVarInt()));
        List<UUID> groups = new ArrayList<>(Math.min(declaredSize, ServerGroupResolver.MAX_GROUPS_PER_ORDER));
        for (int i = 0; i < declaredSize; i++) {
            UUID id = buffer.readUUID();
            if (groups.size() < ServerGroupResolver.MAX_GROUPS_PER_ORDER) {
                groups.add(id);
            }
        }
        int ordinal = Math.max(0, buffer.readVarInt());
        ArmyPreset[] presets = ArmyPreset.values();
        return new ArmyPresetPacket(groups, presets[ordinal % presets.length]);
    }

    public static void handle(ArmyPresetPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> OrderRelayCompat.executeOrRelay(
                context, packet.groupIds(), "order_forward",
                () -> execute(packet, context.getSender())
        ));
        context.setPacketHandled(true);
    }

    private static void execute(ArmyPresetPacket packet, ServerPlayer sender) {
        if (!ServerPacketGuard.allow(sender) || packet.groupIds.isEmpty()) {
            return;
        }
        RouteManager.cancel(sender, packet.groupIds);
        RTSGroupAttackManager.cancelOwned(sender, packet.groupIds);
        Map<UUID, List<AbstractRecruitEntity>> groups = ServerGroupResolver.resolve(sender, packet.groupIds);
        if (groups.isEmpty()) {
            sender.displayClientMessage(Component.literal("No loaded owned recruits were found."), true);
            return;
        }

        Map<UUID, Vec3> centers = new LinkedHashMap<>();
        double armyX = 0.0D;
        double armyY = 0.0D;
        double armyZ = 0.0D;
        double facingSin = 0.0D;
        double facingCos = 0.0D;
        int facingCount = 0;
        List<ArmyDeploymentMath.GroupSpec> specs = new ArrayList<>();

        for (Map.Entry<UUID, List<AbstractRecruitEntity>> entry : groups.entrySet()) {
            UUID groupId = entry.getKey();
            List<AbstractRecruitEntity> recruits = entry.getValue();
            Vec3 center = groupCenter(recruits);
            centers.put(groupId, center);
            armyX += center.x;
            armyY += center.y;
            armyZ += center.z;

            float yaw = FormationService.currentFacing(sender.serverLevel(), groupId, recruits, sender.getYRot());
            double radians = Math.toRadians(yaw);
            facingSin += Math.sin(radians);
            facingCos += Math.cos(radians);
            facingCount++;

            specs.add(new ArmyDeploymentMath.GroupSpec(
                    groupId.toString(),
                    roleOf(recruits),
                    recruits.size(),
                    center.x,
                    center.z
            ));
        }

        double inv = 1.0D / groups.size();
        armyX *= inv;
        armyY *= inv;
        armyZ *= inv;
        float armyFacing = facingCount == 0 || Math.abs(facingSin) + Math.abs(facingCos) < 1.0E-7D
                ? sender.getYRot()
                : (float) Math.toDegrees(Math.atan2(facingSin, facingCos));

        Map<String, ArmyDeploymentMath.Target> targets = ArmyDeploymentMath.plan(
                packet.preset,
                specs,
                armyX,
                armyZ,
                armyFacing
        );
        if (targets.size() != groups.size()) {
            sender.displayClientMessage(Component.literal("Could not plan that army preset."), true);
            return;
        }

        int formed = 0;
        int ordered = 0;
        int artilleryGuns = 0;
        int ships = 0;
        for (Map.Entry<UUID, List<AbstractRecruitEntity>> entry : groups.entrySet()) {
            UUID groupId = entry.getKey();
            List<AbstractRecruitEntity> recruits = entry.getValue();
            ArmyDeploymentMath.Target target = targets.get(groupId.toString());
            if (target == null) {
                continue;
            }
            BlockPos surface = FormationUtils.getPositionOrSurface(
                    sender.serverLevel(),
                    BlockPos.containing(target.x(), armyY, target.z())
            );
            Vec3 destination = new Vec3(target.x(), surface.getY(), target.z());
            RecruitCommanderUtil.setRecruitsClearTargets(recruits);
            float oldFacing = FormationService.currentFacing(
                    sender.serverLevel(), groupId, recruits, sender.getYRot()
            );
            double batteryWidth = SiegeWeaponsCompat.currentBatteryWidth(recruits, oldFacing);
            SiegeWeaponsCompat.BatteryOrder battery = SiegeWeaponsCompat.commandBatteryLine(
                    sender, recruits, destination, armyFacing, batteryWidth > 0.0D ? batteryWidth : null
            );
            double fleetWidth = SmallShipsCompat.currentFleetWidth(recruits, oldFacing);
            SmallShipsCompat.FleetOrder fleet = SmallShipsCompat.commandFleetFormation(
                    sender, recruits, destination, armyFacing,
                    FormationService.activeKind(sender.serverLevel(), groupId), null
            );
            List<AbstractRecruitEntity> formationRecruits = SiegeWeaponsCompat.withoutAttachedArtillery(recruits);
            formationRecruits = SmallShipsCompat.withoutAttachedShips(formationRecruits);

            boolean groupFormed = false;
            if (!formationRecruits.isEmpty()) {
                if (FormationService.deployPreset(sender, groupId, formationRecruits, destination, armyFacing)) {
                    groupFormed = true;
                } else {
                    RecruitCommanderUtil.setRecruitsMove(formationRecruits, surface);
                }
                RTSMovementBoostManager.registerFormationMove(sender, groupId, formationRecruits);
            }
            if (battery.movedAny()) {
                artilleryGuns += battery.guns();
                FormationService.rememberFacing(sender.serverLevel(), groupId, armyFacing);
                groupFormed = true;
            }
            if (fleet.movedAny()) {
                ships += fleet.ships();
                FormationService.rememberFacing(sender.serverLevel(), groupId, armyFacing);
                groupFormed = true;
            }
            if (groupFormed) {
                formed++;
            }
            ordered += recruits.size();
        }

        sender.displayClientMessage(Component.literal(
                "Army preset: " + packet.preset.displayName() + " · " + formed + "/" + groups.size()
                        + " formations · " + ordered + " recruits"
                        + (artilleryGuns > 0 ? " · " + artilleryGuns + " gun(s) in battery lines" : "")
                        + (ships > 0 ? " · " + ships + " ship(s) in fleet lines" : "")
        ), true);
    }

    private static ArmyDeploymentMath.Role roleOf(List<AbstractRecruitEntity> recruits) {
        int cavalry = 0;
        int ranged = 0;
        int support = 0;
        int alive = 0;
        for (AbstractRecruitEntity recruit : recruits) {
            if (recruit == null || !recruit.isAlive()) {
                continue;
            }
            alive++;
            if (RTSMovementBoostManager.isCavalry(recruit)) {
                cavalry++;
            } else if (recruit instanceof BowmanEntity || recruit instanceof CrossBowmanEntity) {
                ranged++;
            } else if (recruit instanceof SiegeEngineerEntity) {
                support++;
            }
        }
        if (alive <= 0) {
            return ArmyDeploymentMath.Role.INFANTRY;
        }
        if (cavalry * 2 >= alive) {
            return ArmyDeploymentMath.Role.CAVALRY;
        }
        if (ranged * 2 >= alive) {
            return ArmyDeploymentMath.Role.RANGED;
        }
        if (support * 2 >= alive) {
            return ArmyDeploymentMath.Role.SUPPORT;
        }
        return ArmyDeploymentMath.Role.INFANTRY;
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
