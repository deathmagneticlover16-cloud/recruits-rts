import ee.lastpagans.recruitsrtscamera.formation.ArtilleryFormationMath;

import java.util.List;

public final class ArtilleryFormationMathSelfTest {
    private static int checks;

    public static void main(String[] args) {
        var center = new ArtilleryFormationMath.Point(100.0, 200.0);
        var three = ArtilleryFormationMath.lineSlots(3, center, 0.0F, 12.0);
        check(three.size() == 3, "three slots");
        near(three.get(0).x(), 88.0, "left x");
        near(three.get(1).x(), 100.0, "center x");
        near(three.get(2).x(), 112.0, "right x");
        near(three.get(0).z(), 200.0, "z unchanged");

        var rotated = ArtilleryFormationMath.lineSlots(3, center, 90.0F, 12.0);
        near(rotated.get(0).z(), 188.0, "rotated left z");
        near(rotated.get(2).z(), 212.0, "rotated right z");

        near(ArtilleryFormationMath.spacingForWidth(3, 30.0), 15.0, "frontage spacing");
        near(ArtilleryFormationMath.spacingForWidth(3, 2.0), ArtilleryFormationMath.MIN_GUN_SPACING, "minimum spacing");
        near(ArtilleryFormationMath.spacingForWidth(2, 100.0), ArtilleryFormationMath.MAX_GUN_SPACING, "maximum spacing");

        var bundled = List.of(
                new ArtilleryFormationMath.Point(0.0, 0.0),
                new ArtilleryFormationMath.Point(1.0, 0.0),
                new ArtilleryFormationMath.Point(2.0, 0.0)
        );
        near(ArtilleryFormationMath.estimateSpacing(bundled, 0.0F), ArtilleryFormationMath.MIN_GUN_SPACING, "bundled repaired to min");

        var sane = List.of(
                new ArtilleryFormationMath.Point(-12.0, 0.0),
                new ArtilleryFormationMath.Point(0.0, 0.0),
                new ArtilleryFormationMath.Point(12.0, 0.0)
        );
        near(ArtilleryFormationMath.estimateSpacing(sane, 0.0F), 12.0, "preserve sane spacing");

        System.out.println("ArtilleryFormationMathSelfTest passed " + checks + " checks");
    }

    private static void check(boolean value, String name) {
        checks++;
        if (!value) throw new AssertionError(name);
    }

    private static void near(double actual, double expected, String name) {
        checks++;
        if (Math.abs(actual - expected) > 1.0E-6) {
            throw new AssertionError(name + ": expected " + expected + " but got " + actual);
        }
    }
}
