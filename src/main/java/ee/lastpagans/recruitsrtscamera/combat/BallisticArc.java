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

    public record Velocity(double x, double y, double z) { }
}
