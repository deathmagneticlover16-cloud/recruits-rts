package ee.lastpagans.recruitsrtscamera.compat;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/**
 * Optional bridge to Recruits: Serious Animations.
 *
 * <p>The animation addon deliberately remains optional. When 0.8.1+ is installed we use
 * its public spear-throw synchronization API and its authored release tick. Without it,
 * Commander keeps the exact same delayed projectile timing and simply omits the animation.</p>
 */
public final class SeriousAnimationsCompat {
    private static final String MOD_ID = "recruits_serious_animations";
    private static final int FALLBACK_RELEASE_TICK = 11;
    private static final int FALLBACK_DURATION_TICKS = 19;

    private static boolean resolved;
    private static boolean available;
    private static Method playSpearThrow;
    private static Method spearThrowReleaseTick;
    private static Method spearThrowDurationTicks;

    private SeriousAnimationsCompat() {
    }

    public static boolean available() {
        resolve();
        return available;
    }

    /** Starts the synchronized SPA Recruit spear/pilum throw animation when available. */
    public static void playSpearThrow(AbstractRecruitEntity recruit) {
        if (recruit == null) return;
        resolve();
        if (!available || playSpearThrow == null) return;
        try {
            playSpearThrow.invoke(null, recruit);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Animation support must never be able to break Commander combat.
        }
    }

    public static int spearThrowReleaseTick() {
        resolve();
        if (!available || spearThrowReleaseTick == null) return FALLBACK_RELEASE_TICK;
        try {
            Object value = spearThrowReleaseTick.invoke(null);
            if (value instanceof Number number) {
                return Math.max(1, number.intValue());
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return FALLBACK_RELEASE_TICK;
    }

    public static int spearThrowDurationTicks() {
        resolve();
        if (!available || spearThrowDurationTicks == null) return FALLBACK_DURATION_TICKS;
        try {
            Object value = spearThrowDurationTicks.invoke(null);
            if (value instanceof Number number) {
                return Math.max(spearThrowReleaseTick(), number.intValue());
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return FALLBACK_DURATION_TICKS;
    }

    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            if (!ModList.get().isLoaded(MOD_ID)) return;
            Class<?> api = Class.forName("com.jarmod.recruitsspa.RecruitsAnimationsApi");
            playSpearThrow = api.getMethod("playSpearThrow", AbstractRecruitEntity.class);
            spearThrowReleaseTick = api.getMethod("spearThrowReleaseTick");
            spearThrowDurationTicks = api.getMethod("spearThrowDurationTicks");
            available = true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            available = false;
            playSpearThrow = null;
            spearThrowReleaseTick = null;
            spearThrowDurationTicks = null;
        }
    }
}
