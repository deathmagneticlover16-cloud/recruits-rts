package ee.lastpagans.recruitsrtscamera.combat;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.compat.SeriousAnimationsCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.horse.AbstractHorse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Formation-wide scheduler for SPA's optional mounted triumph animation. */
final class MountedTriumphManager {
    private static final float MIN_MORALE_EXCLUSIVE = 35.0F;
    private static final float OPPORTUNITY_CHANCE = 0.50F;
    private static final int MIN_OPPORTUNITY_TICKS = 80;
    private static final int OPPORTUNITY_VARIANCE_TICKS = 61;
    private static final Map<UUID, Long> NEXT_OPPORTUNITY = new HashMap<>();

    private MountedTriumphManager() { }

    static void maybePlay(
            ServerLevel level,
            UUID groupId,
            List<AbstractRecruitEntity> cavalry,
            float averageMorale,
            boolean chargingRunningOrWedge,
            long now
    ) {
        if (level == null || groupId == null || cavalry == null || cavalry.isEmpty()
                || averageMorale <= MIN_MORALE_EXCLUSIVE || !chargingRunningOrWedge
                || !SeriousAnimationsCompat.available()) return;

        long due = NEXT_OPPORTUNITY.getOrDefault(groupId, now);
        if (now < due) return;
        int interval = MIN_OPPORTUNITY_TICKS + level.random.nextInt(OPPORTUNITY_VARIANCE_TICKS);
        NEXT_OPPORTUNITY.put(groupId, now + Math.max(interval,
                SeriousAnimationsCompat.mountedTriumphDurationTicks() + 20L));
        if (level.random.nextFloat() >= OPPORTUNITY_CHANCE) return;

        int start = level.random.nextInt(cavalry.size());
        for (int index = 0; index < cavalry.size(); index++) {
            AbstractRecruitEntity recruit = cavalry.get((start + index) % cavalry.size());
            if (recruit == null || !recruit.isAlive() || !(recruit.getVehicle() instanceof AbstractHorse)
                    || recruit.getMainHandItem().isEmpty()
                    || SeriousAnimationsCompat.isMountedTriumphActive(recruit)
                    || !SeriousAnimationsCompat.canPlayMountedTriumph(recruit)) continue;
            SeriousAnimationsCompat.playMountedTriumph(recruit);
            return;
        }
    }

    static void reset() {
        NEXT_OPPORTUNITY.clear();
    }

    static void retainGroups(Set<UUID> loadedCavalryGroups) {
        NEXT_OPPORTUNITY.keySet().retainAll(loadedCavalryGroups);
    }
}
