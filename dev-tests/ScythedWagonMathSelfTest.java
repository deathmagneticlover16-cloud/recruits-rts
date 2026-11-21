import ee.lastpagans.recruitsrtscamera.compat.ScythedWagonMath;

public final class ScythedWagonMathSelfTest {
    public static void main(String[] args) {
        check(ScythedWagonMath.contactDamage(0.0D, 0.23D, 8.0D) == 0, "idle wagon");
        check(ScythedWagonMath.contactDamage(0.23D, 0.23D, 8.0D) == 8, "first gear");
        check(ScythedWagonMath.contactDamage(0.46D, 0.23D, 8.0D) == 16, "second gear");
        check(ScythedWagonMath.contactDamage(0.69D, 0.23D, 8.0D) == 24, "third gear");
        check(ScythedWagonMath.contactDamage(-0.23D, 0.23D, 8.0D) == 8, "reverse contact");
        check(ScythedWagonMath.contactDamage(Double.NaN, 0.23D, 8.0D) == 0, "invalid motion");
        System.out.println("ScythedWagonMathSelfTest passed 6 checks.");
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
