package ee.lastpagans.recruitsrtscamera.compat;

import java.util.ArrayList;
import java.util.List;

/** Forge-free geometry for safe, stable chariot slots. */
public final class ChariotFormationMath {
    public static final double MIN_SPACING = 7.0D;

    private ChariotFormationMath() { }

    public static List<Point> lineSlots(int count, Point center, float facingYaw) {
        if (count <= 0 || center == null) return List.of();
        double yaw = Math.toRadians(facingYaw);
        double rightX = Math.cos(yaw);
        double rightZ = Math.sin(yaw);
        List<Point> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double across = (i - (count - 1) * 0.5D) * MIN_SPACING;
            result.add(new Point(center.x + rightX * across, center.z + rightZ * across));
        }
        return List.copyOf(result);
    }

    public static double projection(Point point, float facingYaw) {
        double yaw = Math.toRadians(facingYaw);
        return point.x * Math.cos(yaw) + point.z * Math.sin(yaw);
    }

    public static float targetYaw(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    public record Point(double x, double z) { }
}
