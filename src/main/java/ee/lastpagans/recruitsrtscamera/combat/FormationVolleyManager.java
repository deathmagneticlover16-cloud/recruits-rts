package ee.lastpagans.recruitsrtscamera.combat;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.CrossBowmanEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitSpatialIndex;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Shared group clocks that turn independent ranged AI timers into readable formation volleys. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FormationVolleyManager {
    private static final int BOW_DRAW_TICKS = 20;
    private static final int BOW_RELOAD_TICKS = 40;
    private static final int CROSSBOW_MAX_READY_WAIT = 12;
    private static final int MAX_GROUP_CLOCKS = 1_024;
    private static final Map<UUID, VolleyState> GROUPS = new LinkedHashMap<>(128, 0.75F, true);
    private static long lastCleanupAt = Long.MIN_VALUE;

    private FormationVolleyManager() { }

    /** Attack-time value injected into every Bowman goal in the group. */
    public static int bowAttackTime(AbstractRecruitEntity recruit) {
        if (!eligible(recruit)) return Integer.MIN_VALUE;
        long now = recruit.level().getGameTime();
        VolleyState state = state(recruit.getGroup(), now);
        if (state.nextBowRelease == Long.MIN_VALUE) state.nextBowRelease = now + BOW_DRAW_TICKS;
        // Every archer reads the same release window. The old code let the first archer
        // which reached this method advance the group clock, so the rest of the line saw
        // a fresh reload timer and only one or two arrows left each volley.
        if (now > state.nextBowRelease) state.nextBowRelease = now + BOW_RELOAD_TICKS;
        if (now == state.nextBowRelease) return 0;
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, state.nextBowRelease - now));
    }

    /** Hold loaded crossbows until the formation is ready, with a bounded straggler timeout. */
    public static boolean crossbowMayRelease(CrossBowmanEntity recruit) {
        if (!eligible(recruit) || !(recruit.level() instanceof ServerLevel level)) return true;
        long now = level.getGameTime();
        VolleyState state = state(recruit.getGroup(), now);
        if (now <= state.crossbowFireUntil) return true;
        state.crossbowReady.add(recruit.getUUID());
        if (state.crossbowFirstReady == Long.MIN_VALUE) state.crossbowFirstReady = now;
        int expected = expectedCrossbows(level, recruit.getGroup(), state, now);
        if (state.crossbowReady.size() >= expected || now - state.crossbowFirstReady >= CROSSBOW_MAX_READY_WAIT) {
            state.crossbowFireUntil = now + 2L;
            state.crossbowReady.clear();
            state.crossbowFirstReady = Long.MIN_VALUE;
            return true;
        }
        return false;
    }

    /** One fixed animation release tick and reload clock for every javelin in a group volley. */
    public static long reserveJavelinRelease(AbstractRecruitEntity recruit, int windupTicks, int reloadTicks) {
        if (!eligible(recruit)) return recruit.level().getGameTime() + Math.max(1, windupTicks);
        long now = recruit.level().getGameTime();
        VolleyState state = state(recruit.getGroup(), now);
        if (now < state.nextJavelinVolley && now > state.javelinJoinUntil) return -1L;
        if (now >= state.nextJavelinVolley) {
            state.javelinRelease = now + Math.max(1, windupTicks);
            state.javelinJoinUntil = now + 3L;
            state.nextJavelinVolley = state.javelinRelease + Math.max(1, reloadTicks);
        }
        return now <= state.javelinJoinUntil ? state.javelinRelease : -1L;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        GROUPS.clear();
        lastCleanupAt = Long.MIN_VALUE;
    }

    private static boolean eligible(AbstractRecruitEntity recruit) {
        return recruit != null && recruit.getGroup() != null && recruit.isAlive()
                && (!TacticalCombatManager.settings(recruit).holdFire()
                || TacticalCombatManager.explicitFireOrder(recruit));
    }

    private static VolleyState state(UUID group, long now) {
        VolleyState result = GROUPS.computeIfAbsent(group, ignored -> new VolleyState());
        result.lastUsed = now;
        if (GROUPS.size() > 512 && lastCleanupAt != now) {
            lastCleanupAt = now;
            GROUPS.values().removeIf(state -> now - state.lastUsed > 1200L);
        }
        while (GROUPS.size() > MAX_GROUP_CLOCKS) {
            var iterator = GROUPS.keySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
        return result;
    }

    private static int expectedCrossbows(ServerLevel level, UUID group, VolleyState state, long now) {
        if (state.expectedAt == now) return state.expectedCrossbows;
        state.expectedAt = now;
        state.expectedCrossbows = Math.max(1, (int) RecruitSpatialIndex.group(level, group).stream()
                .filter(recruit -> recruit instanceof CrossBowmanEntity && recruit.isAlive()
                        && recruit.getTarget() != null && recruit.getShouldRanged()
                        && (!TacticalCombatManager.settings(recruit).holdFire()
                        || TacticalCombatManager.explicitFireOrder(recruit)))
                .count());
        return state.expectedCrossbows;
    }

    private static final class VolleyState {
        long lastUsed;
        long nextBowRelease = Long.MIN_VALUE;
        long crossbowFirstReady = Long.MIN_VALUE;
        long crossbowFireUntil = Long.MIN_VALUE;
        long expectedAt = Long.MIN_VALUE;
        int expectedCrossbows = 1;
        final Set<UUID> crossbowReady = new HashSet<>();
        long javelinRelease = Long.MIN_VALUE;
        long javelinJoinUntil = Long.MIN_VALUE;
        long nextJavelinVolley = Long.MIN_VALUE;
    }
}
