package ee.lastpagans.recruitsrtscamera.compat;

/** Pure combat scaling shared by runtime code and source-only regression tests. */
public final class ScythedWagonMath {
    private ScythedWagonMath() {
    }

    public static int contactDamage(double currentSpeed, double speedPerGear, double damagePerGear) {
        if (!Double.isFinite(currentSpeed) || !Double.isFinite(speedPerGear)
                || !Double.isFinite(damagePerGear) || speedPerGear <= 0.0D || damagePerGear <= 0.0D) {
            return 0;
        }
        double gears = Math.min(3.0D, Math.abs(currentSpeed) / speedPerGear);
        return Math.max(0, (int) Math.round(gears * damagePerGear));
    }
}
