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
        dragAwarePitchLandsAtCursor();
        System.out.println("BallisticArcSelfTest passed 5 checks.");
    }

    private static void dragAwarePitchLandsAtCursor() {
        double drag = 0.99D;
        double gravity = 0.05D;
        double horizontalSpeed = 2.4D;
        int ticks = 40;
        double horizontalDistance = horizontalSpeed * (1.0D - Math.pow(drag, ticks)) / (1.0D - drag);
        double expectedY = -7.5D;
        double vertical = BallisticArc.verticalWithDrag(
                horizontalDistance, expectedY, horizontalSpeed, gravity, drag);
        double actualY = 0.0D;
        for (int tick = 0; tick < ticks; tick++) {
            actualY += vertical;
            vertical = vertical * drag - gravity;
        }
        require(Math.abs(actualY - expectedY) < 0.0001D,
                "drag-aware strategic pitch must cross the cursor height at target range");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
