package ee.lastpagans.recruitsrtscamera.battle;

import ee.lastpagans.recruitsrtscamera.troops.TroopStatProfile;

/**
 * Client-facing group battle state.
 *
 * <p>routedThisBattle is deliberately separate from moraleState. moraleState answers
 * "what is the unit doing now?" while routedThisBattle is the battle-session latch
 * requested by Commander: once a group UUID has routed in the current combat session,
 * the flag remains true until that combat session expires and a new one begins.</p>
 */
public record GroupBattleSnapshot(
        float fatigue,
        float morale,
        int moraleState,
        int combatBaseline,
        int currentCount,
        boolean routedThisBattle,
        TroopStatProfile statistics,
        float carriedWeight,
        float carryCapacity,
        float foodSupply
) {
    public GroupBattleSnapshot {
        statistics = statistics == null ? TroopStatProfile.NEUTRAL : statistics;
        carriedWeight = Math.max(0.0F, carriedWeight);
        carryCapacity = Math.max(0.0F, carryCapacity);
        foodSupply = Math.max(0.0F, foodSupply);
    }
}
