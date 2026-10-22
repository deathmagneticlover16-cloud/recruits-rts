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
    private static final int FALLBACK_TRIUMPH_DURATION_TICKS = 45;

    private static boolean resolved;
    private static boolean available;
    private static Method playSpearThrow;
    private static Method spearThrowReleaseTick;
    private static Method spearThrowDurationTicks;
    private static Method playMountedTriumph;
    private static Method canPlayMountedTriumph;
    private static Method isMountedTriumphActive;
    private static Method mountedTriumphDurationTicks;

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

    public static boolean canPlayMountedTriumph(AbstractRecruitEntity recruit) {
        resolve();
        return invokeTriumphPredicate(canPlayMountedTriumph, recruit, false);
    }

    public static boolean isMountedTriumphActive(AbstractRecruitEntity recruit) {
        resolve();
        return invokeTriumphPredicate(isMountedTriumphActive, recruit, false);
    }

    /** Starts SPA's mounted triumph and returns whether SPA accepted the request. */
    public static boolean playMountedTriumph(AbstractRecruitEntity recruit) {
        resolve();
        return invokeTriumphPredicate(playMountedTriumph, recruit, false);
    }

    public static int mountedTriumphDurationTicks() {
        resolve();
        if (!available || mountedTriumphDurationTicks == null) return FALLBACK_TRIUMPH_DURATION_TICKS;
        try {
            Object value = mountedTriumphDurationTicks.invoke(null);
            if (value instanceof Number number) return Math.max(1, number.intValue());
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return FALLBACK_TRIUMPH_DURATION_TICKS;
    }

    private static boolean invokeTriumphPredicate(Method method, AbstractRecruitEntity recruit, boolean fallback) {
        if (recruit == null) return fallback;
        resolve();
        if (!available || method == null) return fallback;
        try {
            Object value = method.invoke(null, recruit);
            return value instanceof Boolean result ? result : fallback;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return fallback;
        }
    }

    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            if (!ModList.get().isLoaded(MOD_ID)) return;
            Class<?> api;
            try {
                api = Class.forName("com.lastpagans.recruitsspa.RecruitsAnimationsApi");
            } catch (ClassNotFoundException newPackageMissing) {
                api = Class.forName("com.jarmod.recruitsspa.RecruitsAnimationsApi");
            }
            playSpearThrow = optionalMethod(api, "playSpearThrow", AbstractRecruitEntity.class);
            spearThrowReleaseTick = optionalMethod(api, "spearThrowReleaseTick");
            spearThrowDurationTicks = optionalMethod(api, "spearThrowDurationTicks");
            playMountedTriumph = optionalMethod(api, "playMountedTriumph", AbstractRecruitEntity.class);
            canPlayMountedTriumph = optionalMethod(api, "canPlayMountedTriumph", AbstractRecruitEntity.class);
            isMountedTriumphActive = optionalMethod(api, "isMountedTriumphActive", AbstractRecruitEntity.class);
            mountedTriumphDurationTicks = optionalMethod(api, "mountedTriumphDurationTicks");
            available = true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            available = false;
            playSpearThrow = null;
            spearThrowReleaseTick = null;
            spearThrowDurationTicks = null;
            playMountedTriumph = null;
            canPlayMountedTriumph = null;
            isMountedTriumphActive = null;
            mountedTriumphDurationTicks = null;
        }
    }

    private static Method optionalMethod(Class<?> api, String name, Class<?>... parameters) {
        try {
            return api.getMethod(name, parameters);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }
}
