import ee.lastpagans.recruitsrtscamera.compat.ChariotFormationMath;

public final class ChariotFormationMathSelfTest {
    public static void main(String[] args) {
        var slots = ChariotFormationMath.lineSlots(3, new ChariotFormationMath.Point(0, 0), 0);
        check(slots.size() == 3, "slot count");
        check(Math.abs(slots.get(1).x()) < 0.001, "centered middle slot");
        check(Math.abs(slots.get(2).x() - slots.get(1).x()) >= 7.0, "safe scythe spacing");
        check(Math.abs(ChariotFormationMath.targetYaw(0, 10)) < 0.001, "north yaw");
        check(Math.abs(ChariotFormationMath.targetYaw(-10, 0) - 90.0F) < 0.001, "west yaw");
        System.out.println("ChariotFormationMathSelfTest passed 5 checks.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
