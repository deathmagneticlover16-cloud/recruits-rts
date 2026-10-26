package ee.lastpagans.recruitsrtscamera.navigation;

import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Region revisions let routes react to nearby block changes without global invalidation. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TacticalTerrainRevisions {
    private static final int REGION_SHIFT = 4;
    private static final Map<ResourceKey<Level>, Map<Long, Long>> REVISIONS = new HashMap<>();
    private static final Map<ResourceKey<Level>, Long> EPOCHS = new HashMap<>();

    private TacticalTerrainRevisions() {
    }

    public static Map<Long, Long> snapshot(ServerLevel level, List<Vec3> points) {
        if (level == null || points == null || points.isEmpty()) return Map.of();
        Map<Long, Long> result = new LinkedHashMap<>();
        Map<Long, Long> levelRevisions = REVISIONS.getOrDefault(level.dimension(), Map.of());
        for (Vec3 point : points) {
            long region = region(BlockPos.containing(point));
            result.putIfAbsent(region, levelRevisions.getOrDefault(region, 0L));
        }
        return Map.copyOf(result);
    }

    public static boolean valid(ServerLevel level, Map<Long, Long> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return true;
        Map<Long, Long> current = REVISIONS.getOrDefault(level.dimension(), Map.of());
        for (Map.Entry<Long, Long> entry : snapshot.entrySet()) {
            if (current.getOrDefault(entry.getKey(), 0L).longValue() != entry.getValue().longValue()) return false;
        }
        return true;
    }

    public static long revisionAt(ServerLevel level, Vec3 position) {
        if (level == null || position == null) return 0L;
        BlockPos block = BlockPos.containing(position);
        return revisionAt(level, block.getX(), block.getZ());
    }

    public static long revisionAt(ServerLevel level, int blockX, int blockZ) {
        if (level == null) return 0L;
        long region = region(blockX >> REGION_SHIFT, blockZ >> REGION_SHIFT);
        return REVISIONS.getOrDefault(level.dimension(), Map.of()).getOrDefault(region, 0L);
    }

    static long regionKey(Vec3 position) {
        return region(BlockPos.containing(position));
    }

    static long revisionForRegion(ServerLevel level, long region) {
        return level == null ? 0L
                : REVISIONS.getOrDefault(level.dimension(), Map.of()).getOrDefault(region, 0L);
    }

    /** Dimension-wide change stamp used to discard a path search spanning a terrain edit. */
    public static long epoch(ServerLevel level) {
        return level == null ? 0L : EPOCHS.getOrDefault(level.dimension(), 0L);
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level) bump(level, event.getPos());
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level) bump(level, event.getPos());
    }

    @SubscribeEvent
    public static void onFluidChange(BlockEvent.FluidPlaceBlockEvent event) {
        if (event.getLevel() instanceof ServerLevel level) bump(level, event.getPos());
    }

    private static void bump(ServerLevel level, BlockPos position) {
        EPOCHS.put(level.dimension(), EPOCHS.getOrDefault(level.dimension(), 0L) + 1L);
        Map<Long, Long> revisions = REVISIONS.computeIfAbsent(level.dimension(), ignored -> new HashMap<>());
        int rx = position.getX() >> REGION_SHIFT;
        int rz = position.getZ() >> REGION_SHIFT;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long key = region(rx + dx, rz + dz);
                revisions.put(key, revisions.getOrDefault(key, 0L) + 1L);
            }
        }
    }

    private static long region(BlockPos position) {
        return region(position.getX() >> REGION_SHIFT, position.getZ() >> REGION_SHIFT);
    }

    private static long region(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    @SubscribeEvent
    public static void onStop(ServerStoppedEvent event) {
        REVISIONS.clear();
        EPOCHS.clear();
    }
}
