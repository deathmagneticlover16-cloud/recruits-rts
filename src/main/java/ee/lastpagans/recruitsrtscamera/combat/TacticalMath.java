package ee.lastpagans.recruitsrtscamera.combat;

/** Pure tactical geometry and hysteresis, shared with the regression tests. */
public final class TacticalMath {
    private TacticalMath() {}
    public static boolean retreat(boolean retreating, double distance, boolean mounted) {
        return distance < (retreating ? (mounted ? 20 : 14) : (mounted ? 12 : 8));
    }
    public static boolean advance(boolean advancing, double distance, boolean mounted) {
        return distance > (advancing ? (mounted ? 25 : 21) : (mounted ? 32 : 27));
    }
    public static double[] arc(double dx, double dz, double radius, double angle, double sign) {
        // Starts tangent to the charge direction, finishes facing the reverse direction.
        double forward = radius * Math.sin(angle);
        double lateral = sign * radius * (1 - Math.cos(angle));
        return new double[] {dx * forward - dz * lateral, dz * forward + dx * lateral};
    }
    public static boolean front(double facingX, double facingZ, double sourceX, double sourceZ) {
        double length = Math.hypot(sourceX, sourceZ);
        return length > 0.0001 && (facingX * sourceX + facingZ * sourceZ) / length >= 0.5;
    }
    public static float projectileDamage(float original, int armor, double multiplier) {
        double exposure = 1 - Math.min(1, Math.max(0, armor) / 20.0);
        return (float) (original * (1 + (multiplier - 1) * exposure));
    }
}
