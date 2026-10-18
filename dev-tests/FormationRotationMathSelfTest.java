import ee.lastpagans.recruitsrtscamera.formation.FormationRotationMath;

public final class FormationRotationMathSelfTest {
    private static int checks;

    public static void main(String[] args) {
        FormationRotationMath.Point pivot = new FormationRotationMath.Point(10, 10);
        FormationRotationMath.Point p = FormationRotationMath.rotate(new FormationRotationMath.Point(12, 10), pivot, 90);
        near(p.x(), 10);
        near(p.z(), 12);

        FormationRotationMath.Point q = FormationRotationMath.rotateOffset(0, 3, -90);
        near(q.x(), 3);
        near(q.z(), 0);

        near(FormationRotationMath.signedAngle(1, 0, 0, 1), 90);
        near(FormationRotationMath.signedAngle(0, 1, 1, 0), -90);
        near(FormationRotationMath.rotateYaw(170, 30), -160);
        near(FormationRotationMath.rotateYaw(-170, -30), 160);

        System.out.println("FormationRotationMathSelfTest: passed " + checks + " checks");
    }

    private static void near(double actual, double expected) {
        checks++;
        if (Math.abs(actual - expected) > 1.0E-6) {
            throw new AssertionError("expected " + expected + " but got " + actual);
        }
    }
}
