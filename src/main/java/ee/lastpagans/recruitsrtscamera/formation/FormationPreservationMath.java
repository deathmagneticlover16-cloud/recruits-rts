package ee.lastpagans.recruitsrtscamera.formation;

import java.util.ArrayList;
import java.util.List;

/** Dependency-free translation math for preserving a multi-group battlefield layout. */
public final class FormationPreservationMath {
    private FormationPreservationMath() {
    }

    /**
     * Treat every group equally, find their old arrangement centre, then translate all
     * centres so that the arrangement centre lands on the requested destination.
     */
    public static List<Point> translateGroupCenters(List<Point> currentCenters, Point newArmyCenter) {
        if (currentCenters == null || currentCenters.isEmpty() || newArmyCenter == null) {
            return List.of();
        }
        double centerX = 0.0D;
        double centerZ = 0.0D;
        int valid = 0;
        for (Point point : currentCenters) {
            if (point == null || !Double.isFinite(point.x) || !Double.isFinite(point.z)) {
                continue;
            }
            centerX += point.x;
            centerZ += point.z;
            valid++;
        }
        if (valid == 0) {
            return List.of();
        }
        centerX /= valid;
        centerZ /= valid;

        List<Point> result = new ArrayList<>(currentCenters.size());
        for (Point point : currentCenters) {
            if (point == null || !Double.isFinite(point.x) || !Double.isFinite(point.z)) {
                result.add(newArmyCenter);
            } else {
                result.add(new Point(
                        newArmyCenter.x + (point.x - centerX),
                        newArmyCenter.z + (point.z - centerZ)
                ));
            }
        }
        return List.copyOf(result);
    }

    public record Point(double x, double z) {
    }
}
