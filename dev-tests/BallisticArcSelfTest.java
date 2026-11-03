import ee.lastpagans.recruitsrtscamera.combat.BallisticArc;

public final class BallisticArcSelfTest {
    public static void main(String[] args) {
        var near = BallisticArc.solve(12.0D, 0.0D, 0.0D, 1.6D, 0.05D);
        var far = BallisticArc.solve(28.0D, 0.0D, 0.0D, 1.6D, 0.05D);
        require(near.y() > 0.0D, "an arrow needs positive lift on level ground");
        require(far.y() > near.y(), "a deeper formation target needs a higher arc");
        require(Math.abs(far.x() - 1.6D) < 0.0001D, "horizontal arrow speed must be preserved");
        var diagonal = BallisticArc.solve(12.0D, 2.0D, 12.0D, 2.0D, 0.05D);
        require(Math.abs(Math.hypot(diagonal.x(), diagonal.z()) - 2.0D) < 0.0001D,
                "diagonal firing must preserve horizontal speed");
        System.out.println("BallisticArcSelfTest passed 4 checks.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
