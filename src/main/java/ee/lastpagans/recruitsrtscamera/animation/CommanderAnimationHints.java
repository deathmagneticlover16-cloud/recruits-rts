package ee.lastpagans.recruitsrtscamera.animation;

import com.talhanation.recruits.entities.AbstractRecruitEntity;

/**
 * Tiny optional bridge for the separate Serious Player Animations/Recruits addon.
 *
 * <p>No animation mod is required. Commander writes semantic hints into Recruit persistent
 * data with a short expiry. An animation addon can read these keys without depending on RTS
 * Camera internals, and RTS Camera remains fully functional when no animation addon exists.</p>
 */
public final class CommanderAnimationHints {
    public static final String HINT_KEY = "RecruitsRTSAnimHint";
    public static final String PRIORITY_KEY = "RecruitsRTSAnimHintPriority";
    public static final String UNTIL_KEY = "RecruitsRTSAnimHintUntil";

    private CommanderAnimationHints() {
    }

    public enum Hint {
        STANDARD_BEARER(0),
        WINDED(1),
        TIRED(1),
        EXHAUSTED(2),
        WAVERING(3),
        SPEAR_THROW(4),
        ROUTING(5);

        private final int priority;

        Hint(int priority) {
            this.priority = priority;
        }

        public int priority() {
            return priority;
        }
    }

    public static void offer(AbstractRecruitEntity recruit, Hint hint, int durationTicks) {
        if (recruit == null || hint == null || recruit.level().isClientSide) return;
        long now = recruit.level().getGameTime();
        long until = recruit.getPersistentData().getLong(UNTIL_KEY);
        int currentPriority = recruit.getPersistentData().getInt(PRIORITY_KEY);
        if (until > now && currentPriority > hint.priority()) return;
        recruit.getPersistentData().putString(HINT_KEY, hint.name());
        recruit.getPersistentData().putInt(PRIORITY_KEY, hint.priority());
        recruit.getPersistentData().putLong(UNTIL_KEY, now + Math.max(1, durationTicks));
    }

    public static String current(AbstractRecruitEntity recruit) {
        if (recruit == null) return "";
        long now = recruit.level().getGameTime();
        if (recruit.getPersistentData().getLong(UNTIL_KEY) <= now) return "";
        return recruit.getPersistentData().getString(HINT_KEY);
    }
}
