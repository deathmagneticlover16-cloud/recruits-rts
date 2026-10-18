package ee.lastpagans.recruitsrtscamera.runtime;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** A small event-maintained grid used for same-formation steering queries. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RecruitSpatialIndex {
    private static final int CELL_SIZE = 8;
    private static final Map<UUID, Entry> ENTRIES = new HashMap<>();
    private static final Map<Cell, Set<UUID>> CELLS = new HashMap<>();

    private RecruitSpatialIndex() {
    }

    public static void track(AbstractRecruitEntity recruit) {
        if (recruit == null || recruit.level().isClientSide) return;
        Cell cell = cell(recruit);
        Entry previous = ENTRIES.put(recruit.getUUID(), new Entry(recruit, cell));
        if (previous != null && !previous.cell.equals(cell)) remove(previous.cell, recruit.getUUID());
        CELLS.computeIfAbsent(cell, ignored -> new HashSet<>()).add(recruit.getUUID());
    }

    public static List<AbstractRecruitEntity> nearbyGroup(AbstractRecruitEntity origin, double radius) {
        if (origin == null || radius <= 0.0D || origin.getGroup() == null) return List.of();
        track(origin);
        int reach = Math.max(1, (int) Math.ceil(radius / CELL_SIZE));
        int cx = floorCell(origin.getX());
        int cz = floorCell(origin.getZ());
        double radiusSq = radius * radius;
        List<AbstractRecruitEntity> result = new ArrayList<>();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                Set<UUID> ids = CELLS.get(new Cell(origin.level().dimension(), cx + dx, cz + dz));
                if (ids == null) continue;
                for (UUID id : ids) {
                    if (id.equals(origin.getUUID())) continue;
                    Entry entry = ENTRIES.get(id);
                    AbstractRecruitEntity recruit = entry == null ? null : entry.recruit;
                    if (recruit == null || !recruit.isAlive() || recruit.isRemoved()
                            || recruit.level() != origin.level() || !Objects.equals(recruit.getGroup(), origin.getGroup())) {
                        continue;
                    }
                    if (recruit.distanceToSqr(origin) <= radiusSq) result.add(recruit);
                }
            }
        }
        return result;
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof AbstractRecruitEntity recruit) track(recruit);
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof AbstractRecruitEntity recruit) remove(recruit);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!event.getEntity().level().isClientSide && event.getEntity() instanceof AbstractRecruitEntity recruit) {
            remove(recruit);
        }
    }

    private static void remove(AbstractRecruitEntity recruit) {
        Entry entry = ENTRIES.remove(recruit.getUUID());
        if (entry != null) remove(entry.cell, recruit.getUUID());
    }

    private static void remove(Cell cell, UUID id) {
        Set<UUID> ids = CELLS.get(cell);
        if (ids == null) return;
        ids.remove(id);
        if (ids.isEmpty()) CELLS.remove(cell);
    }

    private static Cell cell(Entity entity) {
        return new Cell(entity.level().dimension(), floorCell(entity.getX()), floorCell(entity.getZ()));
    }

    private static int floorCell(double coordinate) {
        return Math.floorDiv((int) Math.floor(coordinate), CELL_SIZE);
    }

    @SubscribeEvent
    public static void onStop(ServerStoppedEvent event) {
        ENTRIES.clear();
        CELLS.clear();
    }

    private record Cell(ResourceKey<Level> level, int x, int z) {}
    private record Entry(AbstractRecruitEntity recruit, Cell cell) {}
}
