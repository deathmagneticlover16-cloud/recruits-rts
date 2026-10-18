import ee.lastpagans.recruitsrtscamera.math.ScreenProjection;

public final class ScreenProjectionSelfTest {
    private static final double EPSILON = 0.0001D;

    public static void main(String[] args) {
        centerAtYawZero();
        horizontalOrientation();
        centerAtYawNinety();
        pitchedCameraCenter();
        rayProjectionRoundTrip();
        rectangleIntersection();
        projectedBoxSelection();
        System.out.println("ScreenProjectionSelfTest: all checks passed");
    }

    private static void centerAtYawZero() {
        var camera = camera(0.0D, 0.0D);
        var point = ScreenProjection.project(camera, 0.0D, 0.0D, 10.0D);
        require(point != null, "front point should project");
        close(point.x(), 960.0D, "yaw 0 center x");
        close(point.y(), 540.0D, "yaw 0 center y");
    }

    private static void horizontalOrientation() {
        var camera = camera(0.0D, 0.0D);
        var west = ScreenProjection.project(camera, -5.0D, 0.0D, 10.0D);
        var east = ScreenProjection.project(camera, 5.0D, 0.0D, 10.0D);
        require(west != null && east != null, "side points should project");
        require(west.x() > 960.0D, "west must appear to the right while looking south");
        require(east.x() < 960.0D, "east must appear to the left while looking south");
    }

    private static void centerAtYawNinety() {
        var camera = camera(90.0D, 0.0D);
        var point = ScreenProjection.project(camera, -10.0D, 0.0D, 0.0D);
        require(point != null, "west-facing point should project");
        close(point.x(), 960.0D, "yaw 90 center x");
        close(point.y(), 540.0D, "yaw 90 center y");
    }

    private static void pitchedCameraCenter() {
        var camera = camera(0.0D, 45.0D);
        double distance = 20.0D;
        double y = -Math.sin(Math.toRadians(45.0D)) * distance;
        double z = Math.cos(Math.toRadians(45.0D)) * distance;
        var point = ScreenProjection.project(camera, 0.0D, y, z);
        require(point != null, "pitched forward point should project");
        close(point.x(), 960.0D, "pitch center x");
        close(point.y(), 540.0D, "pitch center y");
    }

    private static void rayProjectionRoundTrip() {
        var camera = camera(31.0D, 57.0D);
        double[][] samples = {
                {960.0D, 540.0D},
                {120.0D, 140.0D},
                {1800.0D, 900.0D},
                {500.0D, 800.0D}
        };
        for (double[] sample : samples) {
            var ray = ScreenProjection.ray(camera, sample[0], sample[1]);
            var world = ray.origin().add(ray.direction().scale(50.0D));
            var projected = ScreenProjection.project(camera, world.x(), world.y(), world.z());
            require(projected != null, "round-trip point should project");
            close(projected.x(), sample[0], "round-trip x");
            close(projected.y(), sample[1], "round-trip y");
        }
    }

    private static void rectangleIntersection() {
        require(ScreenProjection.rectanglesIntersect(0, 0, 10, 10, 10, 10, 20, 20),
                "touching rectangles should intersect");
        require(!ScreenProjection.rectanglesIntersect(0, 0, 9, 9, 10, 10, 20, 20),
                "separated rectangles should not intersect");
    }


    private static void projectedBoxSelection() {
        var camera = camera(0.0D, 0.0D);
        var recruit = ScreenProjection.projectBox(
                camera,
                -0.4D, -0.9D, 9.6D,
                0.4D, 0.9D, 10.4D
        );
        require(recruit != null, "recruit box in front should project");
        require(ScreenProjection.rectanglesIntersect(
                        900.0D, 470.0D, 1020.0D, 610.0D,
                        recruit.minX(), recruit.minY(), recruit.maxX(), recruit.maxY()),
                "center drag box should select recruit body");
        require(!ScreenProjection.rectanglesIntersect(
                        0.0D, 0.0D, 200.0D, 200.0D,
                        recruit.minX(), recruit.minY(), recruit.maxX(), recruit.maxY()),
                "distant drag box should not select recruit body");
    }

    private static ScreenProjection.CameraSpec camera(double yaw, double pitch) {
        return new ScreenProjection.CameraSpec(0, 0, 0, yaw, pitch, 70.0D, 1920.0D, 1080.0D);
    }

    private static void close(double actual, double expected, String message) {
        if (Math.abs(actual - expected) > EPSILON) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
