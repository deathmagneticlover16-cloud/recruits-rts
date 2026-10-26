package ee.lastpagans.recruitsrtscamera.navigation;

import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitRuntimeMetrics;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * One-tick static terrain snapshot shared by formation members and incremental path searches.
 * Dynamic entity collision remains uncached and is checked by the caller at the final feet box.
 */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
final class TacticalTerrainCache {
    private static final Map<ServerLevel, LevelCache> LEVELS = new WeakHashMap<>();

    private TacticalTerrainCache() { }

    static List<AABB> collisionBoxes(ServerLevel level, Mob mover, BlockPos floor) {
        if (level == null || mover == null || floor == null) return List.of();
        LevelCache cache = cache(level);
        long revision = TacticalTerrainRevisions.revisionAt(level, floor.getX(), floor.getZ());
        SurfaceKey key = new SurfaceKey(floor.asLong(), mover.getClass(),
                Math.round(mover.getBbWidth() * 100.0F),
                Math.round(mover.getBbHeight() * 100.0F), revision);
        List<AABB> cached = cache.surfaces.get(key);
        if (cached != null) {
            RecruitRuntimeMetrics.TERRAIN_CACHE_HITS.increment();
            return cached;
        }
        BlockState state = level.getBlockState(floor);
        VoxelShape shape = state.getCollisionShape(level, floor, CollisionContext.of(mover));
        List<AABB> result = shape.isEmpty() ? List.of() : List.copyOf(shape.toAabbs());
        cache.surfaces.put(key, result);
        RecruitRuntimeMetrics.TERRAIN_CACHE_MISSES.increment();
        return result;
    }

    static boolean fluidEmpty(ServerLevel level, BlockPos position) {
        if (level == null || position == null) return false;
        LevelCache cache = cache(level);
        long revision = TacticalTerrainRevisions.revisionAt(level, position.getX(), position.getZ());
        FluidKey key = new FluidKey(position.asLong(), revision);
        Boolean cached = cache.fluids.get(key);
        if (cached != null) {
            RecruitRuntimeMetrics.TERRAIN_CACHE_HITS.increment();
            return cached;
        }
        boolean empty = level.getFluidState(position).isEmpty();
        cache.fluids.put(key, empty);
        RecruitRuntimeMetrics.TERRAIN_CACHE_MISSES.increment();
        return empty;
    }

    private static LevelCache cache(ServerLevel level) {
        long gameTime = level.getGameTime();
        LevelCache cache = LEVELS.computeIfAbsent(level, ignored -> new LevelCache());
        if (cache.gameTime != gameTime) {
            cache.gameTime = gameTime;
            cache.surfaces.clear();
            cache.fluids.clear();
        }
        return cache;
    }

    @SubscribeEvent
    public static void onStop(ServerStoppedEvent event) {
        LEVELS.clear();
    }

    private static final class LevelCache {
        long gameTime = Long.MIN_VALUE;
        final Map<SurfaceKey, List<AABB>> surfaces = new HashMap<>();
        final Map<FluidKey, Boolean> fluids = new HashMap<>();
    }

    private record SurfaceKey(
            long block, Class<?> type, int widthCentimeters, int heightCentimeters, long revision
    ) { }
    private record FluidKey(long block, long revision) { }
}
