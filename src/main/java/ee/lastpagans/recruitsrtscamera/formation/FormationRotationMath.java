package ee.lastpagans.recruitsrtscamera.formation;

/** Dependency-free X/Z rotation helpers shared by client preview and server execution. */
public final class FormationRotationMath {
    private FormationRotationMath() {
    }

    public static Point rotate(Point point, Point pivot, double degrees) {
        if (point == null || pivot == null || !Double.isFinite(degrees)) {
            return point;
        }
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double dx = point.x() - pivot.x();
        double dz = point.z() - pivot.z();
        return new Point(
                pivot.x() + dx * cos - dz * sin,
                pivot.z() + dx * sin + dz * cos
        );
    }

    public static Point rotateOffset(double dx, double dz, double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Point(dx * cos - dz * sin, dx * sin + dz * cos);
    }

    public static float rotateYaw(float yaw, double degrees) {
        if (!Float.isFinite(yaw) || !Double.isFinite(degrees)) {
            return yaw;
        }
        return normalizeYaw((float) (yaw + degrees));
    }

    /** Signed angle from vector A to vector B in the X/Z plane, in degrees. */
    public static double signedAngle(double ax, double az, double bx, double bz) {
        double aLen = Math.hypot(ax, az);
        double bLen = Math.hypot(bx, bz);
        if (aLen < 1.0E-6D || bLen < 1.0E-6D) {
            return 0.0D;
        }
        ax /= aLen;
        az /= aLen;
        bx /= bLen;
        bz /= bLen;
        double cross = ax * bz - az * bx;
        double dot = Math.max(-1.0D, Math.min(1.0D, ax * bx + az * bz));
        return Math.toDegrees(Math.atan2(cross, dot));
    }

    public static float normalizeYaw(float yaw) {
        float normalized = yaw % 360.0F;
        if (normalized >= 180.0F) normalized -= 360.0F;
        if (normalized < -180.0F) normalized += 360.0F;
        return normalized;
    }

    public record Point(double x, double z) {
    }
}
