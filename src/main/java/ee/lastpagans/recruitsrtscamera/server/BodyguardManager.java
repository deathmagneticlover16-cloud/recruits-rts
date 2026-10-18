package ee.lastpagans.recruitsrtscamera.server;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.world.RecruitsGroup;
import com.talhanation.recruits.world.RecruitsGroupsSaveData;
import ee.lastpagans.recruitsrtscamera.formation.FormationRuntime;
import ee.lastpagans.recruitsrtscamera.compat.TerrainSurfaceResolver;
import ee.lastpagans.recruitsrtscamera.formation.FormationService;
import ee.lastpagans.recruitsrtscamera.network.BodyguardCarryPacket;
import ee.lastpagans.recruitsrtscamera.network.BodyguardSyncPacket;
import ee.lastpagans.recruitsrtscamera.network.RTSCameraNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Player-as-general attachment to one owned Recruits group. */
public final class BodyguardManager {
    private static final String DATA_KEY = "RecruitsRTSCameraBodyguard";
    private static final String FOLLOW_KEY = "RecruitsRTSCameraBodyguardFollowPlayer";
    private static final double ASSIGN_RANGE_SQ = 32.0D * 32.0D;
    private static final double REATTACH_RANGE = 6.0D;
    private static final double NORMAL_SLOT_STEP = 0.44D;
    private static final double CATCHUP_SLOT_STEP = 0.72D;
    private static final double CATCHUP_DISTANCE = 1.75D;
    private static final double HARD_RECOVERY_DISTANCE = 10.0D;
    private static final double SLOT_EPSILON = 0.08D;
    private static final int FOLLOW_REPLAN_TICKS = 7;

    private static final Map<UUID, Boolean> RTS_ACTIVE = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> ATTACH_BLOCKED = new ConcurrentHashMap<>();
    private static final Map<UUID, Vec3> LAST_TARGET = new ConcurrentHashMap<>();
    private static final Map<UUID, Vec3> LAST_FOLLOW_PLAYER_POS = new ConcurrentHashMap<>();
    private static final Map<UUID, Float> LAST_FOLLOW_YAW = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_FOLLOW_TICK = new ConcurrentHashMap<>();

    private BodyguardManager() {
    }

    public static UUID getBodyguard(ServerPlayer player) {
        if (player == null) return null;
        CompoundTag tag = player.getPersistentData();
        return tag.hasUUID(DATA_KEY) ? tag.getUUID(DATA_KEY) : null;
    }

    public static boolean isBodyguard(ServerPlayer player, UUID groupId) {
        UUID bodyguard = getBodyguard(player);
        return bodyguard != null && bodyguard.equals(groupId);
    }

    public static boolean followsPlayer(ServerPlayer player) {
        return player != null && getBodyguard(player) != null && player.getPersistentData().getBoolean(FOLLOW_KEY);
    }

    public static void setFollowPlayer(ServerPlayer player, boolean enabled) {
        if (player == null) return;
        if (getBodyguard(player) == null) enabled = false;
        player.getPersistentData().putBoolean(FOLLOW_KEY, enabled);
        clearFollowRuntime(player.getUUID());
        if (!enabled && !isRtsActive(player)) {
            UUID groupId = getBodyguard(player);
            if (groupId != null) FormationRuntime.clearCommanderReservation(groupId);
        }
        sync(player);
    }

    public static boolean shouldReserveCommanderSlot(ServerPlayer player, UUID groupId) {
        return player != null && isBodyguard(player, groupId) && (isRtsActive(player) || followsPlayer(player));
    }

    public static boolean setBodyguard(ServerPlayer player, UUID groupId) {
        if (player == null) return false;
        UUID oldGroup = getBodyguard(player);
        if (groupId == null) {
            player.getPersistentData().remove(DATA_KEY);
            player.getPersistentData().remove(FOLLOW_KEY);
            if (oldGroup != null) FormationRuntime.clearCommanderReservation(oldGroup);
            clearRuntime(player.getUUID());
            sync(player);
            return true;
        }
        RecruitsGroup group = findOwnedGroup(player, groupId);
        if (group == null) return false;
        Vec3 center = groupCenter(player.serverLevel(), group);
        if (center == null || center.distanceToSqr(player.position()) > ASSIGN_RANGE_SQ) return false;
        if (oldGroup != null && !oldGroup.equals(groupId)) FormationRuntime.clearCommanderReservation(oldGroup);
        player.getPersistentData().putUUID(DATA_KEY, groupId);
        player.getPersistentData().putBoolean(FOLLOW_KEY, false);
        clearRuntime(player.getUUID());
        sync(player);
        return true;
    }

    public static void setRtsActive(ServerPlayer player, boolean active) {
        if (player == null) return;
        UUID id = player.getUUID();
        UUID groupId = getBodyguard(player);
        if (active) {
            RTS_ACTIVE.put(id, true);
            LAST_TARGET.remove(id);
            // Entering RTS must never become an implicit teleport-to-bodyguard command.
            // If the commander walked away, leave them detached until they physically return.
            RecruitsGroup group = groupId == null ? null : findOwnedGroup(player, groupId);
            Vec3 center = group == null ? null : groupCenter(player.serverLevel(), group);
            if (center != null && center.distanceToSqr(player.position()) > REATTACH_RANGE * REATTACH_RANGE) {
                ATTACH_BLOCKED.put(id, true);
            } else {
                ATTACH_BLOCKED.remove(id);
            }
        } else {
            RTS_ACTIVE.remove(id);
            ATTACH_BLOCKED.remove(id);
            LAST_TARGET.remove(id);
            if (groupId != null && !followsPlayer(player)) FormationRuntime.clearCommanderReservation(groupId);
        }
    }

    public static boolean isRtsActive(ServerPlayer player) {
        return player != null && RTS_ACTIVE.containsKey(player.getUUID());
    }

    public static void tick(ServerPlayer player) {
        if (player == null || !player.isAlive()) return;
        UUID groupId = getBodyguard(player);
        if (groupId == null) return;
        RecruitsGroup group = findOwnedGroup(player, groupId);
        if (group == null || group.removed || group.getSize() <= 0) {
            setBodyguard(player, null);
            return;
        }

        if (!isRtsActive(player)) {
            if (followsPlayer(player)) tickFollowOutsideRts(player, group);
            return;
        }

        ServerLevel level = player.serverLevel();
        if (ATTACH_BLOCKED.containsKey(player.getUUID())) {
            Vec3 center = groupCenter(level, group);
            if (center == null || center.distanceToSqr(player.position()) > REATTACH_RANGE * REATTACH_RANGE) {
                return;
            }
            ATTACH_BLOCKED.remove(player.getUUID());
            LAST_TARGET.remove(player.getUUID());
        }

        Vec3 target = FormationRuntime.commanderSlot(level, player, groupId);
        if (target == null) {
            target = LAST_TARGET.get(player.getUUID());
            if (target == null) {
                Vec3 center = groupCenter(level, group);
                if (center == null) return;
                target = TerrainSurfaceResolver.resolve(level, player, center);
            }
        }

        Vec3 previousTarget = LAST_TARGET.put(player.getUUID(), target);
        Vec3 error = target.subtract(player.position());
        double horizontal = Math.hypot(error.x, error.z);
        Vec3 before = player.position();

        // Feed-forward with the moving slot instead of only chasing yesterday's slot position.
        Vec3 slotVelocity = previousTarget == null ? Vec3.ZERO : target.subtract(previousTarget);
        if (slotVelocity.lengthSqr() > 2.25D) slotVelocity = Vec3.ZERO;

        if (horizontal > HARD_RECOVERY_DISTANCE) {
            // Severe desync while already attached may still be recovered, but never during the
            // re-entry grace above. This prevents normal RTS toggling from teleporting the player.
            if (canStandAt(level, player, target)) player.teleportTo(target.x, target.y, target.z);
        } else if (horizontal > SLOT_EPSILON || Math.abs(error.y) > 0.06D) {
            double correctionScale = horizontal > CATCHUP_DISTANCE ? 0.58D : 0.36D;
            Vec3 move = slotVelocity.scale(0.92D).add(error.scale(correctionScale));
            double horizontalMove = Math.hypot(move.x, move.z);
            double maxStep = horizontal > CATCHUP_DISTANCE ? CATCHUP_SLOT_STEP : NORMAL_SLOT_STEP;
            if (horizontalMove > maxStep && horizontalMove > 1.0E-6D) {
                double factor = maxStep / horizontalMove;
                move = new Vec3(move.x * factor, move.y, move.z * factor);
            }
            move = new Vec3(move.x, Mth.clamp(move.y, -0.45D, 0.55D), move.z);
            player.move(MoverType.SELF, move);
        }

        Float heading = FormationRuntime.formationHeading(groupId);
        if (heading != null && Float.isFinite(heading)) {
            player.setYRot(heading);
            player.setYHeadRot(heading);
            player.setYBodyRot(heading);
        }

        if (player.position().distanceToSqr(before) > 1.0E-7D || heading != null) syncCarry(player);
    }

    /** While RTS is closed, the commander leads and the Bodyguard reforms around the player. */
    private static void tickFollowOutsideRts(ServerPlayer player, RecruitsGroup group) {
        UUID playerId = player.getUUID();
        long now = player.serverLevel().getGameTime();
        Vec3 last = LAST_FOLLOW_PLAYER_POS.get(playerId);
        float lastYaw = LAST_FOLLOW_YAW.getOrDefault(playerId, player.getYRot());
        long lastTick = LAST_FOLLOW_TICK.getOrDefault(playerId, Long.MIN_VALUE / 2);
        boolean moved = last == null || last.distanceToSqr(player.position()) > 0.65D * 0.65D;
        boolean turned = Math.abs(Mth.wrapDegrees(player.getYRot() - lastYaw)) > 12.0F;
        if (now - lastTick < FOLLOW_REPLAN_TICKS || (!moved && !turned)) return;

        List<AbstractRecruitEntity> recruits = loadedMembers(player.serverLevel(), player, group);
        if (recruits.isEmpty()) return;
        FormationService.rememberFacing(player.serverLevel(), group.getUUID(), player.getYRot());
        if (FormationService.movePreserved(player, group.getUUID(), recruits, player.position())) {
            Vec3 slot = FormationRuntime.commanderSlot(player.serverLevel(), player, group.getUUID());
            if (slot != null) {
                FormationRuntime.translateFormation(group.getUUID(), player.position().subtract(slot));
            }
            LAST_FOLLOW_PLAYER_POS.put(playerId, player.position());
            LAST_FOLLOW_YAW.put(playerId, player.getYRot());
            LAST_FOLLOW_TICK.put(playerId, now);
        }
    }

    private static List<AbstractRecruitEntity> loadedMembers(ServerLevel level, ServerPlayer player, RecruitsGroup group) {
        List<AbstractRecruitEntity> recruits = new ArrayList<>();
        if (level == null || player == null || group == null || group.members == null) return recruits;
        for (UUID id : group.members) {
            Entity entity = level.getEntity(id);
            if (entity instanceof AbstractRecruitEntity recruit && recruit.isAlive()
                    && player.getUUID().equals(recruit.getOwnerUUID())) recruits.add(recruit);
        }
        return recruits;
    }

    private static boolean canStandAt(ServerLevel level, ServerPlayer player, Vec3 target) {
        if (level == null || player == null || target == null) return false;
        AABB box = player.getBoundingBox().move(
                target.x - player.getX(), target.y - player.getY(), target.z - player.getZ()
        ).inflate(-0.02D, 0.0D, -0.02D);
        return level.noCollision(player, box);
    }

    private static void syncCarry(ServerPlayer player) {
        RTSCameraNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new BodyguardCarryPacket(player.getX(), player.getY(), player.getZ(), player.getYRot()));
    }

    public static void logout(ServerPlayer player) {
        if (player == null) return;
        RTS_ACTIVE.remove(player.getUUID());
        clearRuntime(player.getUUID());
        UUID groupId = getBodyguard(player);
        if (groupId != null) FormationRuntime.clearCommanderReservation(groupId);
    }

    public static void sync(ServerPlayer player) {
        if (player == null) return;
        RTSCameraNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new BodyguardSyncPacket(getBodyguard(player), followsPlayer(player)));
    }

    private static void clearRuntime(UUID playerId) {
        ATTACH_BLOCKED.remove(playerId);
        LAST_TARGET.remove(playerId);
        clearFollowRuntime(playerId);
    }

    private static void clearFollowRuntime(UUID playerId) {
        LAST_FOLLOW_PLAYER_POS.remove(playerId);
        LAST_FOLLOW_YAW.remove(playerId);
        LAST_FOLLOW_TICK.remove(playerId);
    }

    private static Vec3 groupCenter(ServerLevel level, RecruitsGroup group) {
        if (level == null || group == null || group.members == null) return null;
        double x = 0.0D, y = 0.0D, z = 0.0D;
        int count = 0;
        for (UUID id : group.members) {
            Entity entity = level.getEntity(id);
            if (!(entity instanceof AbstractRecruitEntity recruit) || !recruit.isAlive()) continue;
            x += recruit.getX(); y += recruit.getY(); z += recruit.getZ(); count++;
        }
        return count == 0 ? null : new Vec3(x / count, y / count, z / count);
    }

    private static RecruitsGroup findOwnedGroup(ServerPlayer player, UUID groupId) {
        if (player == null || groupId == null) return null;
        for (RecruitsGroup group : RecruitsGroupsSaveData.get(player.serverLevel()).getAllGroups()) {
            if (group != null && groupId.equals(group.getUUID()) && player.getUUID().equals(group.getPlayerUUID())) return group;
        }
        return null;
    }
}
