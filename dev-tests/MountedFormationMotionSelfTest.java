import ee.lastpagans.recruitsrtscamera.navigation.MountedFormationMotion;

public final class MountedFormationMotionSelfTest {
    private static int checks;

    public static void main(String[] args) {
        convergesWithoutSway();
        preservesContinuousPaceChanges();
        settlesWithoutTeleporting();
        rejectsExceptionalIncomingImpulse();
        System.out.println("MountedFormationMotionSelfTest passed " + checks + " checks.");
    }

    private static void convergesWithoutSway() {
        double horseX = -3.0D;
        double horseZ = 3.0D;
        double centerX = 0.0D;
        double velocityX = 0.0D;
        double velocityZ = 0.0D;
        int lateralCrossings = 0;
        double previousZ = horseZ;
        for (int tick = 0; tick < 160; tick++) {
            centerX += 0.22D;
            MountedFormationMotion.Vector next = MountedFormationMotion.velocity(
                    velocityX, velocityZ, centerX - horseX, -horseZ, 0.22D, 0.0D, 1.08D);
            velocityX = next.x();
            velocityZ = next.z();
            horseX += velocityX;
            horseZ += velocityZ;
            if (Math.signum(previousZ) != 0.0D && Math.signum(horseZ) != Math.signum(previousZ)) lateralCrossings++;
            previousZ = horseZ;
        }
        check(Math.abs(horseZ) < 0.08D, "lateral slot error converges");
        check(lateralCrossings <= 1, "lateral correction does not sway repeatedly across the slot");
        check(Math.abs(centerX - horseX) < 0.25D, "horse retains the moving longitudinal slot");
    }

    private static void preservesContinuousPaceChanges() {
        double velocityX = 0.20D;
        double maximumChange = 0.0D;
        for (int tick = 0; tick < 12; tick++) {
            MountedFormationMotion.Vector next = MountedFormationMotion.velocity(
                    velocityX, 0.0D, 0.0D, 0.0D, 0.50D, 0.0D, 1.0D);
            maximumChange = Math.max(maximumChange, Math.abs(next.x() - velocityX));
            velocityX = next.x();
        }
        check(velocityX > 0.49D, "run pace is reached smoothly");
        check(maximumChange < 0.22D, "walk-to-run does not create a teleport-sized velocity jump");
    }

    private static void settlesWithoutTeleporting() {
        double position = 4.0D;
        double velocity = 0.5D;
        double maximum = 0.0D;
        for (int tick = 0; tick < 80; tick++) {
            MountedFormationMotion.Vector next = MountedFormationMotion.velocity(
                    velocity, 0.0D, -position, 0.0D, 0.0D, 0.0D, 1.08D);
            velocity = next.x();
            maximum = Math.max(maximum, Math.abs(velocity));
            position += velocity;
        }
        check(Math.abs(position) < 0.05D, "stationary slot recovery settles accurately");
        check(maximum < 0.30D, "slot recovery remains bounded");
    }

    private static void rejectsExceptionalIncomingImpulse() {
        MountedFormationMotion.Vector next = MountedFormationMotion.velocity(
                3.0D, -2.0D, 0.4D, 0.0D, 0.20D, 0.0D, 1.0D);
        check(Math.hypot(next.x(), next.z()) < 0.45D,
                "exceptional incoming impulse cannot fling one horse across the formation");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
