import ee.lastpagans.recruitsrtscamera.combat.ChargeMomentum;

/** Gameplay invariants for run-up, impact decay and disengagement, independent of Forge. */
public final class CavalryChargeSelfTest {
    private static int checks;

    public static void main(String[] args) {
        ChargeMomentum full = run(0.72, 32);
        near(full.quality(), 1.0, "full speed and 22-block run-up reach full impact");
        ChargeMomentum slow = run(0.40, 60);
        truth(slow.quality() > 0.0 && slow.quality() < full.quality() * 0.4, "slow run-up has much less energy");
        ChargeMomentum shortRun = run(0.72, 12);
        truth(shortRun.quality() < 0.25, "short acceleration cannot deliver a full charge");
        near(run(0.0, 500).quality(), 0.0, "waiting does not earn damage");
        near(run(0.20, 500).quality(), 0.0, "walking does not earn damage");

        for (int reason = 0; reason < 4; reason++) {
            ChargeMomentum momentum = run(0.72, 32);
            momentum.step(reason == 0 ? 12.0 : 0.72, reason == 1 ? 0.2 : 1.0,
                    reason != 2, true, reason != 3, 0.72, 22.0);
            near(momentum.quality(), 0.0, "teleport / sharp turn / retreat / airborne resets run-up " + reason);
        }
        near(full.consumeImpact(), 1.0, "first front-rank hit gets full energy");
        near(full.consumeImpact(), 0.6, "second contact spends energy");
        near(full.consumeImpact(), 0.36, "third contact spends more energy");
        near(full.consumeImpact(), 0.0, "no endless penetration");
        for (int i = 0; i < 300; i++) full.step(0.72, 1.0, true, true, true, 0.72, 22.0);
        near(full.quality(), 0.0, "remaining in melee cannot recharge impacts");
        for (int i = 0; i < 10; i++) full.step(0.72, 1.0, false, false, true, 0.72, 22.0);
        near(full.quality(), 0.0, "disengagement alone gives no free damage");
        for (int i = 0; i < 32; i++) full.step(0.72, 1.0, true, false, true, 0.72, 22.0);
        near(full.quality(), 1.0, "a new full run-up can charge again");
        full.consumeImpact();
        full.step(0.0, 1.0, true, true, true, 0.72, 22.0);
        near(full.quality(), 0.0, "getting stopped spends remaining penetration energy");
        for (int i = 0; i < 20; i++) full.step(0.72, 1.0, true, true, true, 0.72, 22.0);
        near(full.quality(), 0.0, "jitter after impact does not rearm the charge");
        ChargeMomentum invalid = run(Double.NaN, 50);
        near(invalid.quality(), 0.0, "invalid movement cannot contaminate damage");
        System.out.println("CavalryChargeSelfTest passed " + checks + " gameplay checks.");
        System.out.println("Default bonus damage: full charge 48.0, 0.4-block/tick charge "
                + Math.round(slow.quality() * 48.0 * 10.0) / 10.0 + ", short run-up "
                + Math.round(shortRun.quality() * 48.0 * 10.0) / 10.0 + ". Armor applies afterward.");
    }

    private static ChargeMomentum run(double speed, int ticks) {
        ChargeMomentum momentum = new ChargeMomentum();
        for (int i = 0; i < ticks; i++) momentum.step(speed, 1.0, true, false, true, 0.72, 22.0);
        return momentum;
    }
    private static void near(double a, double b, String message) { truth(Math.abs(a - b) < 0.000001, message); }
    private static void truth(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}
