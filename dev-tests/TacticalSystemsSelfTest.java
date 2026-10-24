import ee.lastpagans.recruitsrtscamera.combat.CavalryManeuver;
import ee.lastpagans.recruitsrtscamera.combat.CavalryManeuver.Point;
import ee.lastpagans.recruitsrtscamera.combat.TacticalMath;
import ee.lastpagans.recruitsrtscamera.combat.TacticalSettings;
import java.util.*;

public final class TacticalSystemsSelfTest {
    private static int checks;
    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
    private static boolean near(Point a, Point b) { return a.minus(b).lengthSquared() < 0.000001; }
    public static void main(String[] args) {
        // Infantry never alternates retreat/attack around the same distance threshold.
        check(TacticalMath.retreat(false, 7.9, false), "Foot danger triggers retreat");
        check(!TacticalMath.retreat(false, 8.1, false), "Foot safe entry band");
        check(TacticalMath.retreat(true, 12, false), "Retreat stays committed through the neutral band");
        check(!TacticalMath.retreat(true, 14.1, false), "Foot retreat releases after clearance");
        check(TacticalMath.retreat(false, 11.9, true), "Mounted skirmishers react earlier");
        check(TacticalMath.retreat(true, 19.9, true), "Mounted clearance accounts for cavalry speed");
        check(!TacticalMath.retreat(true, 20.1, true), "Mounted retreat releases");
        check(TacticalMath.advance(false, 28, false), "Distant foot archers advance");
        check(!TacticalMath.advance(false, 26, false), "Safe foot archers stay");
        check(TacticalMath.advance(true, 23, false), "Advance persists into neutral band");
        check(!TacticalMath.advance(true, 20, false), "Advance yields to native firing AI");
        check(TacticalMath.advance(false, 33, true), "Mounted effective range is wider");
        check(!TacticalMath.advance(true, 24, true), "Mounted firing band reached");

        Point start = new Point(0, -24), target = new Point(0, 0);
        var plan = new CavalryManeuver(start, target, 1);
        check(plan.phase() == CavalryManeuver.Phase.CHARGE, "Room for immediate run-up");
        Point exit = plan.committedExit();
        check(near(exit, new Point(0, 5.5)), "Pass exits five and a half blocks beyond line");
        check(near(plan.tick(start, new Point(20, 0)), exit), "Moving target cannot bend committed charge");
        check(near(plan.committedImpact(), target), "Impact geometry remains frozen");
        check(near(plan.tick(new Point(0, 3), null), exit), "Target death does not stop pass");
        check(plan.phase() == CavalryManeuver.Phase.PASS, "Crossing impact advances to pass");
        plan.tick(new Point(0, 5.5), null);
        check(plan.phase() == CavalryManeuver.Phase.TURN, "Turn begins beyond line");
        Point firstTurn = plan.tick(new Point(0, 5.5), null);
        check(firstTurn.z() > 5.5 && firstTurn.x() < 0, "First turn point continues forward and outward");

        for (int sign : new int[]{-1, 1}) {
            var cycle = new CavalryManeuver(start, target, sign);
            Point position = start;
            Set<CavalryManeuver.Phase> phases = EnumSet.noneOf(CavalryManeuver.Phase.class);
            boolean reformed = false;
            for (int tick = 0; tick < 400; tick++) {
                Point destination = cycle.tick(position, target);
                phases.add(cycle.phase());
                if (cycle.phase() == CavalryManeuver.Phase.REFORM) { reformed = true; break; }
                Point delta = destination.minus(position);
                double length = Math.sqrt(delta.lengthSquared());
                if (length > 0) position = position.add(delta.scale(Math.min(0.6, length) / length));
            }
            check(reformed, "Cavalry completes full pass and curved turn in either direction");
            check(phases.containsAll(List.of(CavalryManeuver.Phase.CHARGE, CavalryManeuver.Phase.PASS,
                    CavalryManeuver.Phase.TURN)), "All committed phases visited");
            check(position.z() > 3 && Math.abs(position.x()) > 4,
                    "Quarter-sized reform still clears the immediate melee pocket");
            check(near(cycle.tick(position, null), position), "No target means wait clear of line");
            cycle.tick(position, new Point(4, -10));
            check(near(cycle.committedImpact(), new Point(4, -10)), "Next pass accepts new formation target");
        }
        var distant = new CavalryManeuver(new Point(0, -60), target, 1);
        check(distant.phase() == CavalryManeuver.Phase.APPROACH, "Distant cavalry stages before charging");
        check(near(distant.tick(new Point(0, -60), target), new Point(0, -22)), "Staging leaves run-up distance");
        check(!distant.blocked(new Point(0, -40)), "Blocked lane first requests turn-out");
        check(!distant.blocked(new Point(0, -40)), "Blocked first turn tries the opposite side");
        check(distant.blocked(new Point(0, -40)), "Both blocked turn directions request pause/replan");

        check(TacticalMath.projectileDamage(10, 0, 2) == 20, "Unarmored arrow lethality");
        check(TacticalMath.projectileDamage(10, 10, 2) == 15, "Partial armor reduces bonus");
        check(TacticalMath.projectileDamage(10, 20, 2) == 10, "Full armor adds no bonus");
        check(TacticalMath.projectileDamage(10, 40, 2) == 10, "Extra armor cannot create negative bonus");
        check(TacticalMath.projectileDamage(10, 0, 1) == 10, "Config 1 disables rebalance");
        check(TacticalMath.projectileDamage(10, 0, 1.6) == 16, "Thrown missile multiplier");
        var settings = TacticalSettings.DEFAULT.withSkirmish(true).withCombat(TacticalSettings.CombatMode.MELEE)
                .withShield(TacticalSettings.ShieldMode.UP).withHoldFire(true);
        check(settings.skirmish() && settings.holdFire(), "Independent toggles coexist");
        check(settings.withCombat(TacticalSettings.CombatMode.RANGED).holdFire(), "Weapon change cannot release Hold Fire");
        check(TacticalSettings.DEFAULT.combat() == TacticalSettings.CombatMode.NATIVE && !TacticalSettings.DEFAULT.skirmish(),
                "Defaults preserve native choices");
        System.out.println("Passed " + checks + " tactical geometry, skirmish, projectile and settings checks.");
    }
}
