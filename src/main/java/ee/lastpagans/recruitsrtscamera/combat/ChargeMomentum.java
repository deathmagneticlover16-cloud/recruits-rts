package ee.lastpagans.recruitsrtscamera.combat;

/** Measured run-up and a finite impact budget. No Minecraft dependencies. */
public final class ChargeMomentum {
    private double distance;
    private int runTicks;
    private double quality;
    private int contacts;
    private int impactAge;
    private int clearTicks;

    public void step(double moved, double headingDot, boolean advancing, boolean nearEnemy,
                     boolean grounded, double fullSpeed, double fullRunup) {
        boolean running = Double.isFinite(moved) && moved >= 0.30D && moved <= 2.5D
                && headingDot >= 0.85D && grounded && advancing;
        if (contacts > 0) {
            impactAge++;
            clearTicks = nearEnemy ? 0 : clearTicks + 1;
            if (!running || impactAge > 12) quality = 0.0D;
            else quality = Math.min(quality, square(clamp(moved / fullSpeed)));
            if (clearTicks >= 10) reset(); // Disengage before earning another impact budget.
            return;
        }
        if (!running) {
            distance = 0.0D;
            runTicks = 0;
            quality = 0.0D;
            return;
        }
        distance = Math.min(fullRunup, distance + moved);
        runTicks = Math.min(24, runTicks + 1);
        quality = runTicks < 10 || distance < 4.0D ? 0.0D
                : clamp(distance / fullRunup) * clamp(runTicks / 24.0D) * square(clamp(moved / fullSpeed));
    }

    public double quality() { return contacts >= 3 ? 0.0D : quality; }

    public boolean hasImpactMomentum() { return contacts > 0 && impactAge <= 12 && quality() > 0.0D; }

    public double consumeImpact() {
        double available = quality();
        if (available <= 0.0D) return 0.0D;
        contacts++;
        quality *= 0.60D;
        if (contacts == 1) impactAge = 0;
        return available;
    }

    public void reset() {
        distance = 0.0D;
        runTicks = 0;
        quality = 0.0D;
        contacts = 0;
        impactAge = 0;
        clearTicks = 0;
    }

    private static double clamp(double x) { return Math.max(0.0D, Math.min(1.0D, x)); }
    private static double square(double x) { return x * x; }
}
