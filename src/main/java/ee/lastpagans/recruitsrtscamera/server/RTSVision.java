package ee.lastpagans.recruitsrtscamera.server;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.CaptainEntity;
import ee.lastpagans.recruitsrtscamera.compat.SmallShipsCompat;
import ee.lastpagans.recruitsrtscamera.config.RTSServerConfig;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitSpatialIndex;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server-authoritative tactical visibility used by Fog of War. */
public final class RTSVision {
    private static final double BRUSH_REVEAL_RADIUS = 14.0D;
    private static final int BRUSH_THRESHOLD = 7;
    private static final Map<VisibilityKey, VisibilitySample> ENTITY_CACHE = new HashMap<>();
    private RTSVision() {
    }

    public static boolean fogEnabled() {
        return RTSServerConfig.FOG_OF_WAR.get();
    }

    public static boolean hiddenTargetChecksEnabled() {
        return fogEnabled() && RTSServerConfig.FOG_BLOCK_HIDDEN_TARGETING.get();
    }

    public static boolean canSee(ServerPlayer player, Entity target) {
        if (player == null || target == null || !fogEnabled()) return true;
        long bucket = player.serverLevel().getGameTime() / 5L;
        VisibilityKey key = new VisibilityKey(player.getUUID(), target.getUUID());
        VisibilitySample cached = ENTITY_CACHE.get(key);
        if (cached != null && cached.bucket == bucket) return cached.visible;
        if (ENTITY_CACHE.size() > 4096) ENTITY_CACHE.entrySet().removeIf(entry -> entry.getValue().bucket < bucket - 2L);
        boolean visible = canSeeInternal(player, target.getEyePosition(), target);
        ENTITY_CACHE.put(key, new VisibilitySample(bucket, visible));
        return visible;
    }

    public static boolean canSee(ServerPlayer player, Vec3 target) {
        return player == null || target == null || !fogEnabled() || canSeeInternal(player, target, null);
    }

    private static boolean canSeeInternal(ServerPlayer player, Vec3 target, Entity targetEntity) {
        var level = player.serverLevel();
        double playerRadius = RTSServerConfig.FOG_PLAYER_VISION_RADIUS.get();
        if (withinVision(player.position(), target, playerRadius)
                && visibleFrom(level, player, player.getEyePosition(), target, targetEntity)) return true;

        int maxRadius = Math.max(
                RTSServerConfig.FOG_RECRUIT_VISION_RADIUS.get(),
                Math.max(RTSServerConfig.FOG_CAVALRY_VISION_RADIUS.get(), RTSServerConfig.FOG_SHIP_VISION_RADIUS.get())
        );
        for (AbstractRecruitEntity recruit : RecruitSpatialIndex.nearby(player.serverLevel(), target, maxRadius)) {
            if (!recruit.isAlive() || Math.abs(recruit.getY() - target.y) > 96.0D
                    || !player.getUUID().equals(recruit.getOwnerUUID())) {
                continue;
            }
            Vec3 source = recruit.getEyePosition();
            double radius;
            if (recruit instanceof CaptainEntity captain && SmallShipsCompat.isCaptainAboard(captain)) {
                source = SmallShipsCompat.shipPosition(captain).add(0.0D, 1.5D, 0.0D);
                radius = RTSServerConfig.FOG_SHIP_VISION_RADIUS.get();
            } else if (recruit.isPassenger()) {
                radius = RTSServerConfig.FOG_CAVALRY_VISION_RADIUS.get();
            } else {
                radius = RTSServerConfig.FOG_RECRUIT_VISION_RADIUS.get();
            }
            if (withinVision(source, target, radius)
                    && visibleFrom(level, recruit, source, target, targetEntity)) return true;
        }
        return false;
    }

    private static boolean visibleFrom(net.minecraft.server.level.ServerLevel level, Entity observer,
                                       Vec3 source, Vec3 target, Entity targetEntity) {
        // Concealment belongs to the target: troops in brush see outward, distant troops cannot see in.
        if (targetEntity != null && isDenseBrush(level, targetEntity.blockPosition())
                && horizontalDistanceSqr(source, target) > BRUSH_REVEAL_RADIUS * BRUSH_REVEAL_RADIUS) return false;
        return terrainClear(level, observer, source, target);
    }

    private static boolean terrainClear(net.minecraft.server.level.ServerLevel level, Entity observer,
                                        Vec3 source, Vec3 target) {
        Vec3 direction = target.subtract(source).normalize();
        Vec3 cursor = source;
        // Leaves are handled as density concealment above. Ignoring their collision here is
        // what lets a concealed formation see out of its own forest instead of blinding itself.
        for (int skippedLeaves = 0; skippedLeaves < 16; skippedLeaves++) {
            HitResult hit = level.clip(new ClipContext(cursor, target, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, observer));
            if (hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(target) <= 2.25D) return true;
            if (!(hit instanceof BlockHitResult blockHit)
                    || !level.getBlockState(blockHit.getBlockPos()).is(BlockTags.LEAVES)) return false;
            cursor = hit.getLocation().add(direction.scale(0.35D));
        }
        return false;
    }

    private static boolean isDenseBrush(net.minecraft.server.level.ServerLevel level, BlockPos center) {
        int foliage = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = 0; y <= 3; y++) {
            cursor.set(center.getX() + x, center.getY() + y, center.getZ() + z);
            BlockState state = level.getBlockState(cursor);
            if (state.is(BlockTags.LEAVES) && ++foliage >= BRUSH_THRESHOLD) return true;
        }
        return false;
    }

    private static boolean withinVision(Vec3 source, Vec3 target, double radius) {
        return Math.abs(source.y - target.y) <= 96.0D
                && horizontalDistanceSqr(source, target) <= radius * radius;
    }

    private static double horizontalDistanceSqr(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private record VisibilityKey(UUID observer, UUID target) { }
    private record VisibilitySample(long bucket, boolean visible) { }
}
