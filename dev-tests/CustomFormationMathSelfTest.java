import ee.lastpagans.recruitsrtscamera.formation.CustomFormationMath;
import ee.lastpagans.recruitsrtscamera.formation.FormationPreservationMath;

import java.util.List;

public final class CustomFormationMathSelfTest {
    private static int checks;

    public static void main(String[] args) {
        sanitizeChecks();
        splitChecks();
        slotChecks();
        curveChecks();
        preservationChecks();
        System.out.println("CustomFormationMathSelfTest passed " + checks + " checks.");
    }

    private static void sanitizeChecks() {
        var raw = List.of(
                new CustomFormationMath.Point(0, 0),
                new CustomFormationMath.Point(0.05, 0),
                new CustomFormationMath.Point(1, 0),
                new CustomFormationMath.Point(2, 0)
        );
        var clean = CustomFormationMath.sanitize(raw);
        eq(clean.size(), 3, "tiny mouse jitter removed");
        near(CustomFormationMath.length(clean), 2.0, 0.0001, "straight length preserved");
    }

    private static void splitChecks() {
        var ranges = CustomFormationMath.splitByCounts(List.of(20, 40, 20), 42.0);
        eq(ranges.size(), 3, "three group ranges");
        truth(ranges.get(0).endDistance() < ranges.get(1).startDistance(), "gap 1 exists");
        truth(ranges.get(1).endDistance() < ranges.get(2).startDistance(), "gap 2 exists");
        double first = ranges.get(0).endDistance() - ranges.get(0).startDistance();
        double middle = ranges.get(1).endDistance() - ranges.get(1).startDistance();
        truth(middle > first, "larger group gets longer curve section");
    }

    private static void slotChecks() {
        var path = List.of(
                new CustomFormationMath.Point(0, 0),
                new CustomFormationMath.Point(12, 0)
        );
        var slots = CustomFormationMath.slotsForRange(
                path,
                new CustomFormationMath.PathRange(0, 12),
                12,
                2,
                1.0
        );
        eq(slots.size(), 12, "exact slot count");
        near(slots.get(0).x(), 0.0, 0.0001, "first column at path start");
        near(slots.get(0).z(), 0.0, 0.0001, "front rank on path");
        near(slots.get(1).z(), -1.0, 0.0001, "second rank behind path");
        truth(slots.get(10).x() > slots.get(0).x(), "slot order progresses along curve");
    }

    private static void curveChecks() {
        var path = List.of(
                new CustomFormationMath.Point(0, 0),
                new CustomFormationMath.Point(5, 0),
                new CustomFormationMath.Point(5, 5)
        );
        var sample = CustomFormationMath.sample(path, 7.0);
        near(sample.point().x(), 5.0, 0.0001, "corner sample x");
        near(sample.point().z(), 2.0, 0.0001, "corner sample z");
        near(CustomFormationMath.closestArcDistance(path, new CustomFormationMath.Point(5.2, 3.0)), 8.0, 0.01,
                "closest arc distance follows polyline");
    }

    private static void preservationChecks() {
        var old = List.of(
                new FormationPreservationMath.Point(-10, -5),
                new FormationPreservationMath.Point(0, 0),
                new FormationPreservationMath.Point(10, 8)
        );
        var moved = FormationPreservationMath.translateGroupCenters(
                old,
                new FormationPreservationMath.Point(100, 200)
        );
        eq(moved.size(), 3, "preserved group count");
        near(moved.get(2).x() - moved.get(0).x(), 20.0, 0.0001, "x separation preserved");
        near(moved.get(2).z() - moved.get(0).z(), 13.0, 0.0001, "z separation preserved");
        near((moved.get(0).x() + moved.get(1).x() + moved.get(2).x()) / 3.0, 100.0, 0.0001,
                "new army center x");
        near((moved.get(0).z() + moved.get(1).z() + moved.get(2).z()) / 3.0, 200.0, 0.0001,
                "new army center z");
    }

    private static void near(double actual, double expected, double epsilon, String label) {
        checks++;
        if (Math.abs(actual - expected) > epsilon) {
            throw new AssertionError(label + ": expected " + expected + " but got " + actual);
        }
    }

    private static void eq(int actual, int expected, String label) {
        checks++;
        if (actual != expected) {
            throw new AssertionError(label + ": expected " + expected + " but got " + actual);
        }
    }

    private static void truth(boolean value, String label) {
        checks++;
        if (!value) {
            throw new AssertionError(label);
        }
    }
}
