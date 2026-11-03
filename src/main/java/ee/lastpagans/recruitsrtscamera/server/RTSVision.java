package ee.lastpagans.recruitsrtscamera.server;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.config.RTSServerConfig;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitRuntimeRegistry;
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

/** Server-authoritative terrain and brush hiddenness without distance-based Fog of War. */
public final class RTSVision {
    private static final double BRUSH_REVEAL_RADIUS = 14.0D;
    private static final int BRUSH_THRESHOLD = 7;
    private static final Map<VisibilityKey, VisibilitySample> ENTITY_CACHE = new HashMap<>();
    private RTSVision() {
    }

    public static boolean hiddenTargetChecksEnabled() {
        return RTSServerConfig.HIDDENNESS_BLOCK_TARGETING.get();
    }

    public static boolean canSee(ServerPlayer player, Entity target) {
        if (player == null || target == null) return true;
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
        return player == null || target == null || canSeeInternal(player, target, null);
    }

    /**
     * Strategic fire designates terrain rather than a concealed entity. Test a point above
     * the clicked surface and apply terrain LOS only; brush around some unrelated formation
     * must never reject a flat-plain bombardment coordinate.
     */
    public static boolean canDesignateGround(ServerPlayer player, BlockPos target) {
        if (player == null || target == null) return true;
        Vec3 aim = new Vec3(target.getX() + 0.5D, target.getY() + 1.15D, target.getZ() + 0.5D);
        var level = player.serverLevel();
        if (terrainClear(level, player, player.getEyePosition(), aim)) return true;
        for (AbstractRecruitEntity recruit : RecruitRuntimeRegistry.loaded(level)) {
            if (recruit.isAlive() && player.getUUID().equals(recruit.getOwnerUUID())
                    && terrainClear(level, recruit, recruit.getEyePosition(), aim)) return true;
        }
        return false;
    }

    private static boolean canSeeInternal(ServerPlayer player, Vec3 target, Entity targetEntity) {
        var level = player.serverLevel();
        // Brush density is a property of the target location. Sample it once per cached
        // visibility decision rather than once for every potential friendly observer.
        boolean concealedByBrush = targetEntity != null && isDenseBrush(level, targetEntity.blockPosition());
        // Distance never hides an open-plain formation. The commander and every loaded
        // owned formation are potential observers; only terrain and target-side brush
        // concealment decide whether the target is hidden.
        if (visibleFrom(level, player, player.getEyePosition(), target, concealedByBrush)) return true;
        for (AbstractRecruitEntity recruit : RecruitRuntimeRegistry.loaded(level)) {
            if (!recruit.isAlive() || !player.getUUID().equals(recruit.getOwnerUUID())) {
                continue;
            }
            Vec3 source = recruit.getEyePosition();
            if (visibleFrom(level, recruit, source, target, concealedByBrush)) return true;
        }
        return false;
    }

    private static boolean visibleFrom(net.minecraft.server.level.ServerLevel level, Entity observer,
                                       Vec3 source, Vec3 target, boolean concealedByBrush) {
        // Concealment belongs to the target: troops in brush see outward, distant troops cannot see in.
        if (concealedByBrush
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

    private static double horizontalDistanceSqr(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private record VisibilityKey(UUID observer, UUID target) { }
    private record VisibilitySample(long bucket, boolean visible) { }
}
