package ee.lastpagans.recruitsrtscamera.formation;

import java.util.ArrayList;
import java.util.List;

/**
 * Dependency-free frontage planning math. This class intentionally knows nothing
 * about Minecraft, Recruits, or Better Formations so it can be regression tested
 * without launching the game.
 */
public final class FrontageMath {
    public static final double MIN_FRONTAGE = 2.0D;
    public static final double MAX_FRONTAGE = 80.0D;
    public static final double DEFAULT_GROUP_GAP = 1.5D;

    private FrontageMath() {
    }

    public static float facingYaw(double dx, double dz) {
        if (dx * dx + dz * dz < 1.0E-8D) {
            return 0.0F;
        }
        // Better Formations derives forward = (-sin(yaw), 0, cos(yaw)) and its
        // right vector from that. This yaw makes the drag A->B become formation-right.
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) + 180.0F;
        yaw %= 360.0F;
        if (yaw >= 180.0F) yaw -= 360.0F;
        if (yaw < -180.0F) yaw += 360.0F;
        return yaw;
    }

    public static List<GroupSegment> split(List<Integer> troopCounts, double requestedWidth) {
        if (troopCounts == null || troopCounts.isEmpty()) {
            return List.of();
        }

        List<Integer> counts = new ArrayList<>(troopCounts.size());
        int totalTroops = 0;
        for (Integer value : troopCounts) {
            int count = value == null ? 0 : Math.max(0, value);
            counts.add(count);
            totalTroops += count;
        }
        if (totalTroops <= 0) {
            return List.of();
        }

        double width = clamp(requestedWidth, MIN_FRONTAGE, MAX_FRONTAGE);
        int groups = counts.size();
        double maxGapByWidth = groups <= 1 ? 0.0D : width / (groups * 3.0D);
        double gap = groups <= 1 ? 0.0D : Math.min(DEFAULT_GROUP_GAP, maxGapByWidth);
        double usableWidth = Math.max(0.1D, width - gap * (groups - 1));

        List<GroupSegment> result = new ArrayList<>(groups);
        double cursor = -width / 2.0D;
        int remainingTroops = totalTroops;
        double remainingWidth = usableWidth;

        for (int i = 0; i < groups; i++) {
            int count = counts.get(i);
            double segmentWidth;
            if (i == groups - 1) {
                segmentWidth = remainingWidth;
            } else if (remainingTroops <= 0) {
                segmentWidth = 0.0D;
            } else {
                segmentWidth = usableWidth * count / totalTroops;
                segmentWidth = Math.min(segmentWidth, remainingWidth);
            }

            double centerOffset = cursor + segmentWidth / 2.0D;
            LineFit fit = bestLineFit(count, segmentWidth);
            result.add(new GroupSegment(centerOffset, segmentWidth, fit.ranks(), fit.spacing(), fit.actualWidth()));

            cursor += segmentWidth;
            remainingWidth -= segmentWidth;
            remainingTroops -= count;
            if (i < groups - 1) {
                cursor += gap;
            }
        }
        return List.copyOf(result);
    }

    /**
     * Chooses Better Formations line ranks + spacing that best approximate a target width.
     * Better Formations uses columns = ceil(count / ranks), with horizontal step
     * max(1.0, 1.02 * spacing), so we mirror that exact public behaviour here.
     */
    public static LineFit bestLineFit(int troopCount, double targetWidth) {
        int count = Math.max(1, troopCount);
        double target = Math.max(0.0D, targetWidth);
        LineFit best = null;
        double bestScore = Double.POSITIVE_INFINITY;

        for (int ranks = 1; ranks <= Math.min(20, count); ranks++) {
            int columns = (int) Math.ceil(count / (double) ranks);
            double spacing;
            if (columns <= 1) {
                spacing = 1.0D;
            } else {
                double desiredStep = target / (columns - 1.0D);
                spacing = clamp(desiredStep / 1.02D, 0.65D, 1.5D);
            }
            double step = Math.max(1.0D, 1.02D * spacing);
            double actualWidth = Math.max(0.0D, (columns - 1) * step);
            double error = Math.abs(actualWidth - target);

            // When two layouts fit equally well, prefer fewer ranks (a broader front).
            double score = error + ranks * 1.0E-5D;
            if (score < bestScore) {
                bestScore = score;
                best = new LineFit(ranks, spacing, columns, actualWidth);
            }
        }
        return best;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public record GroupSegment(
            double centerOffset,
            double allocatedWidth,
            int ranks,
            double spacing,
            double actualWidth
    ) {
    }

    public record LineFit(int ranks, double spacing, int columns, double actualWidth) {
    }
}
