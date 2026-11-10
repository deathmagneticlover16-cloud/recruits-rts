package ee.lastpagans.recruitsrtscamera.combat;

/** Dependency-free firing solution used to give formation arrows a deliberate arc. */
public final class BallisticArc {
    private BallisticArc() { }

    public static Velocity solve(
            double deltaX,
            double deltaY,
            double deltaZ,
            double horizontalSpeed,
            double gravity
    ) {
        double horizontal = Math.hypot(deltaX, deltaZ);
        double speed = Math.max(0.10D, horizontalSpeed);
        if (horizontal < 1.0E-5D) return new Velocity(0.0D, Math.max(0.10D, speed), 0.0D);
        double flightTicks = Math.max(1.0D, horizontal / speed);
        double vertical = deltaY / flightTicks + 0.5D * Math.max(0.0D, gravity) * flightTicks;
        vertical = Math.max(-0.25D, Math.min(Math.max(0.35D, speed * 0.65D), vertical));
        return new Velocity(deltaX / horizontal * speed, vertical, deltaZ / horizontal * speed);
    }

    /** Exact discrete-tick vertical launch speed for projectiles with drag and gravity. */
    public static double verticalWithDrag(
            double horizontalDistance,
            double verticalDistance,
            double horizontalSpeed,
            double gravity,
            double drag
    ) {
        double speed = Math.max(0.10D, horizontalSpeed);
        double safeDrag = Math.max(0.01D, Math.min(0.999999D, drag));
        double dragLoss = 1.0D - safeDrag;
        double reachableFraction = horizontalDistance * dragLoss / speed;
        double ticks = reachableFraction >= 0.999999D
                ? Math.max(1.0D, horizontalDistance / speed)
                : Math.max(1.0D, Math.log1p(-reachableFraction) / Math.log(safeDrag));
        double travelSum = (1.0D - Math.pow(safeDrag, ticks)) / dragLoss;
        return (verticalDistance + Math.max(0.0D, gravity) / dragLoss * (ticks - travelSum)) / travelSum;
    }

    public record Velocity(double x, double y, double z) { }
}
