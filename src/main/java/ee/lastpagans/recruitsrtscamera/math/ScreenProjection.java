package ee.lastpagans.recruitsrtscamera.math;

/**
 * Small dependency-free camera projection helper.
 *
 * Minecraft's yaw convention is preserved:
 * yaw 0 looks toward +Z and positive pitch looks downward.
 */
public final class ScreenProjection {
    private static final double DEG_TO_RAD = Math.PI / 180.0D;
    private static final double NEAR_DEPTH = 0.01D;

    private ScreenProjection() {
    }

    public static Ray ray(CameraSpec camera, double screenX, double screenY) {
        Basis basis = basis(camera.yawDegrees(), camera.pitchDegrees());
        double ndcX = (screenX / camera.screenWidth()) * 2.0D - 1.0D;
        double ndcY = 1.0D - (screenY / camera.screenHeight()) * 2.0D;
        double tanHalfFov = Math.tan(camera.verticalFovDegrees() * DEG_TO_RAD * 0.5D);
        double aspect = camera.screenWidth() / camera.screenHeight();

        Vec direction = basis.forward()
                .add(basis.right().scale(ndcX * aspect * tanHalfFov))
                .add(basis.up().scale(ndcY * tanHalfFov))
                .normalize();
        return new Ray(new Vec(camera.x(), camera.y(), camera.z()), direction);
    }

    public static ProjectedPoint project(CameraSpec camera, double worldX, double worldY, double worldZ) {
        Basis basis = basis(camera.yawDegrees(), camera.pitchDegrees());
        Vec delta = new Vec(
                worldX - camera.x(),
                worldY - camera.y(),
                worldZ - camera.z()
        );
        double depth = delta.dot(basis.forward());
        if (depth <= NEAR_DEPTH) {
            return null;
        }

        double tanHalfFov = Math.tan(camera.verticalFovDegrees() * DEG_TO_RAD * 0.5D);
        double aspect = camera.screenWidth() / camera.screenHeight();
        double normalizedX = delta.dot(basis.right()) / (depth * tanHalfFov * aspect);
        double normalizedY = delta.dot(basis.up()) / (depth * tanHalfFov);

        double screenX = (normalizedX + 1.0D) * 0.5D * camera.screenWidth();
        double screenY = (1.0D - normalizedY) * 0.5D * camera.screenHeight();
        return new ProjectedPoint(screenX, screenY, depth);
    }


    public static ProjectedBounds projectBox(
            CameraSpec camera,
            double minWorldX,
            double minWorldY,
            double minWorldZ,
            double maxWorldX,
            double maxWorldY,
            double maxWorldZ
    ) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        int projected = 0;

        double[] xs = {minWorldX, maxWorldX};
        double[] ys = {minWorldY, maxWorldY};
        double[] zs = {minWorldZ, maxWorldZ};
        for (double x : xs) {
            for (double y : ys) {
                for (double z : zs) {
                    ProjectedPoint point = project(camera, x, y, z);
                    if (point == null) {
                        continue;
                    }
                    projected++;
                    minX = Math.min(minX, point.x());
                    minY = Math.min(minY, point.y());
                    maxX = Math.max(maxX, point.x());
                    maxY = Math.max(maxY, point.y());
                }
            }
        }
        return projected == 0 ? null : new ProjectedBounds(minX, minY, maxX, maxY);
    }

    public static boolean rectanglesIntersect(
            double aMinX,
            double aMinY,
            double aMaxX,
            double aMaxY,
            double bMinX,
            double bMinY,
            double bMaxX,
            double bMaxY
    ) {
        return aMaxX >= bMinX && bMaxX >= aMinX && aMaxY >= bMinY && bMaxY >= aMinY;
    }

    private static Basis basis(double yawDegrees, double pitchDegrees) {
        double yaw = yawDegrees * DEG_TO_RAD;
        double pitch = pitchDegrees * DEG_TO_RAD;

        // Equivalent to Vec3.directionFromRotation(pitch, yaw) in Minecraft.
        Vec forward = new Vec(
                -Math.sin(yaw) * Math.cos(pitch),
                -Math.sin(pitch),
                Math.cos(yaw) * Math.cos(pitch)
        ).normalize();

        // Screen-right. At yaw 0 the camera faces south (+Z), making west (-X) screen-right.
        Vec right = new Vec(-Math.cos(yaw), 0.0D, -Math.sin(yaw)).normalize();
        Vec up = right.cross(forward).normalize();
        return new Basis(forward, right, up);
    }

    public record CameraSpec(
            double x,
            double y,
            double z,
            double yawDegrees,
            double pitchDegrees,
            double verticalFovDegrees,
            double screenWidth,
            double screenHeight
    ) {
        public CameraSpec {
            if (!Double.isFinite(verticalFovDegrees) || verticalFovDegrees <= 1.0D || verticalFovDegrees >= 179.0D) {
                throw new IllegalArgumentException("verticalFovDegrees must be between 1 and 179");
            }
            if (!Double.isFinite(screenWidth) || !Double.isFinite(screenHeight)
                    || screenWidth <= 0.0D || screenHeight <= 0.0D) {
                throw new IllegalArgumentException("screen dimensions must be positive");
            }
        }
    }

    public record ProjectedPoint(double x, double y, double depth) {
    }

    public record ProjectedBounds(double minX, double minY, double maxX, double maxY) {
    }

    public record Ray(Vec origin, Vec direction) {
    }

    public record Vec(double x, double y, double z) {
        public Vec add(Vec other) {
            return new Vec(x + other.x, y + other.y, z + other.z);
        }

        public Vec scale(double amount) {
            return new Vec(x * amount, y * amount, z * amount);
        }

        public double dot(Vec other) {
            return x * other.x + y * other.y + z * other.z;
        }

        public Vec cross(Vec other) {
            return new Vec(
                    y * other.z - z * other.y,
                    z * other.x - x * other.z,
                    x * other.y - y * other.x
            );
        }

        public double length() {
            return Math.sqrt(x * x + y * y + z * z);
        }

        public Vec normalize() {
            double length = length();
            if (length < 1.0E-12D) {
                return new Vec(0.0D, 0.0D, 0.0D);
            }
            return scale(1.0D / length);
        }
    }

    private record Basis(Vec forward, Vec right, Vec up) {
    }
}
