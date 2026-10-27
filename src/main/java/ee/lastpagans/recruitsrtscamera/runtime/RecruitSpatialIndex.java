package ee.lastpagans.recruitsrtscamera.runtime;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One immutable, position-correct recruit grid per level/tick for all proximity consumers. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RecruitSpatialIndex {
    private static final int CELL_SIZE = 8;
    private static final Map<ServerLevel, Snapshot> SNAPSHOTS = new HashMap<>();

    private RecruitSpatialIndex() { }

    /** Retained for steering callers; the tick snapshot updates every moving recruit together. */
    public static void track(AbstractRecruitEntity recruit) {
        if (recruit != null && !recruit.level().isClientSide) RecruitRuntimeRegistry.brain(recruit);
    }

    public static List<AbstractRecruitEntity> nearbyGroup(AbstractRecruitEntity origin, double radius) {
        if (origin == null || !(origin.level() instanceof ServerLevel level)
                || radius <= 0.0D || origin.getGroup() == null) return List.of();
        List<AbstractRecruitEntity> result = new ArrayList<>();
        for (AbstractRecruitEntity recruit : nearby(level, origin.position(), radius)) {
            if (recruit != origin && Objects.equals(origin.getGroup(), recruit.getGroup())
                    && recruit.distanceToSqr(origin) <= radius * radius) result.add(recruit);
        }
        return result;
    }

    public static List<AbstractRecruitEntity> nearby(ServerLevel level, Vec3 center, double radius) {
        if (level == null || center == null || radius <= 0.0D) return List.of();
        Snapshot snapshot = snapshot(level);
        int reach = Math.max(1, (int) Math.ceil(radius / CELL_SIZE));
        int centerX = cell(center.x);
        int centerZ = cell(center.z);
        double radiusSq = radius * radius;
        List<AbstractRecruitEntity> result = new ArrayList<>();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                List<AbstractRecruitEntity> bucket = snapshot.cells.get(key(centerX + dx, centerZ + dz));
                if (bucket == null) continue;
                RecruitRuntimeMetrics.SPATIAL_CANDIDATES.add(bucket.size());
                for (AbstractRecruitEntity recruit : bucket) {
                    if (valid(recruit, level) && horizontalDistanceSqr(recruit.position(), center) <= radiusSq) {
                        result.add(recruit);
                    }
                }
            }
        }
        return result;
    }

    public static List<AbstractRecruitEntity> within(ServerLevel level, AABB bounds) {
        if (level == null || bounds == null) return List.of();
        Snapshot snapshot = snapshot(level);
        int minX = cell(bounds.minX);
        int maxX = cell(bounds.maxX);
        int minZ = cell(bounds.minZ);
        int maxZ = cell(bounds.maxZ);
        List<AbstractRecruitEntity> result = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                List<AbstractRecruitEntity> bucket = snapshot.cells.get(key(x, z));
                if (bucket == null) continue;
                RecruitRuntimeMetrics.SPATIAL_CANDIDATES.add(bucket.size());
                for (AbstractRecruitEntity recruit : bucket) {
                    if (valid(recruit, level) && bounds.intersects(recruit.getBoundingBox())) result.add(recruit);
                }
            }
        }
        return result;
    }

    public static List<AbstractRecruitEntity> group(ServerLevel level, UUID groupId) {
        if (level == null || groupId == null) return List.of();
        return snapshot(level).groups.getOrDefault(groupId, List.of());
    }

    private static Snapshot snapshot(ServerLevel level) {
        long tick = level.getGameTime();
        Snapshot cached = SNAPSHOTS.get(level);
        if (cached != null && cached.tick == tick) return cached;

        Map<Long, List<AbstractRecruitEntity>> cells = new HashMap<>();
        Map<UUID, List<AbstractRecruitEntity>> groups = new HashMap<>();
        for (AbstractRecruitEntity recruit : RecruitRuntimeRegistry.loaded(level)) {
            if (!valid(recruit, level)) continue;
            cells.computeIfAbsent(key(cell(recruit.getX()), cell(recruit.getZ())), ignored -> new ArrayList<>())
                    .add(recruit);
            if (recruit.getGroup() != null) {
                groups.computeIfAbsent(recruit.getGroup(), ignored -> new ArrayList<>()).add(recruit);
            }
        }
        cells.replaceAll((ignored, members) -> List.copyOf(members));
        groups.replaceAll((ignored, members) -> List.copyOf(members));
        Snapshot rebuilt = new Snapshot(tick, Map.copyOf(cells), Map.copyOf(groups));
        SNAPSHOTS.put(level, rebuilt);
        RecruitRuntimeMetrics.SPATIAL_REBUILDS.increment();
        return rebuilt;
    }

    private static boolean valid(AbstractRecruitEntity recruit, ServerLevel level) {
        return recruit != null && recruit.level() == level && recruit.isAlive() && !recruit.isRemoved();
    }

    private static int cell(double coordinate) {
        return Math.floorDiv((int) Math.floor(coordinate), CELL_SIZE);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static double horizontalDistanceSqr(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    @SubscribeEvent
    public static void onStop(ServerStoppedEvent event) {
        SNAPSHOTS.clear();
    }

    private record Snapshot(
            long tick,
            Map<Long, List<AbstractRecruitEntity>> cells,
            Map<UUID, List<AbstractRecruitEntity>> groups
    ) { }
}
