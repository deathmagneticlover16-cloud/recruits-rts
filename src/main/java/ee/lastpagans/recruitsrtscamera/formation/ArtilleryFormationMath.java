package ee.lastpagans.recruitsrtscamera.formation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Dependency-free battery-line math used by optional Siege Weapons compatibility.
 * One point represents one manned siege weapon, not one recruit.
 */
public final class ArtilleryFormationMath {
    /** Artillery pieces need much more room than infantry formation slots. */
    public static final double MIN_GUN_SPACING = 8.0D;
    public static final double DEFAULT_GUN_SPACING = 12.0D;
    public static final double MAX_GUN_SPACING = 20.0D;

    private ArtilleryFormationMath() {
    }

    public static List<Point> lineSlots(int count, Point center, float facingYaw, double spacing) {
        if (count <= 0 || center == null) {
            return List.of();
        }
        double safeSpacing = clampSpacing(spacing);
        double radians = Math.toRadians(facingYaw);
        // Better Formations/Minecraft-facing convention: this is the formation-right axis.
        double rightX = Math.cos(radians);
        double rightZ = Math.sin(radians);
        double half = (count - 1) * safeSpacing * 0.5D;

        List<Point> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double offset = i * safeSpacing - half;
            result.add(new Point(
                    center.x() + rightX * offset,
                    center.z() + rightZ * offset
            ));
        }
        return List.copyOf(result);
    }

    public static double spacingForWidth(int count, double requestedWidth) {
        if (count <= 1) {
            return DEFAULT_GUN_SPACING;
        }
        return clampSpacing(Math.abs(requestedWidth) / (count - 1.0D));
    }

    /**
     * Estimate an already-deployed battery's spacing along its facing axis. Bundled or
     * badly scrambled batteries deliberately fall back to the safe default/minimum.
     */
    public static double estimateSpacing(List<Point> positions, float facingYaw) {
        if (positions == null || positions.size() <= 1) {
            return DEFAULT_GUN_SPACING;
        }
        double radians = Math.toRadians(facingYaw);
        double rightX = Math.cos(radians);
        double rightZ = Math.sin(radians);
        List<Double> projected = positions.stream()
                .map(point -> point.x() * rightX + point.z() * rightZ)
                .sorted(Comparator.naturalOrder())
                .toList();
        double total = 0.0D;
        int gaps = 0;
        for (int i = 1; i < projected.size(); i++) {
            double gap = projected.get(i) - projected.get(i - 1);
            if (Double.isFinite(gap) && gap > 0.1D) {
                total += gap;
                gaps++;
            }
        }
        if (gaps <= 0) {
            return DEFAULT_GUN_SPACING;
        }
        return clampSpacing(total / gaps);
    }

    public static double projection(Point point, float facingYaw) {
        double radians = Math.toRadians(facingYaw);
        return point.x() * Math.cos(radians) + point.z() * Math.sin(radians);
    }

    public static double clampSpacing(double spacing) {
        if (!Double.isFinite(spacing)) {
            return DEFAULT_GUN_SPACING;
        }
        return Math.max(MIN_GUN_SPACING, Math.min(MAX_GUN_SPACING, spacing));
    }

    public record Point(double x, double z) {
    }
}
