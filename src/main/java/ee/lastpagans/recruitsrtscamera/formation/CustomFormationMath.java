package ee.lastpagans.recruitsrtscamera.formation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Dependency-free geometry for Alt+RMB freehand formations.
 *
 * The drawn stroke is treated as the front rank. Extra ranks are generated on the
 * "behind" side of the stroke, matching the facing convention used by FrontageMath.
 * This class deliberately knows nothing about Minecraft or recruit entities so its
 * output can be regression-tested without launching the game.
 */
public final class CustomFormationMath {
    public static final int MAX_STROKE_POINTS = 96;
    public static final int MAX_RANKS = 8;
    public static final double MIN_POINT_STEP = 0.35D;
    public static final double MIN_STROKE_LENGTH = 2.0D;
    public static final double MAX_STROKE_LENGTH = 180.0D;
    public static final double DEFAULT_RANK_SPACING = 1.08D;
    public static final double DEFAULT_GROUP_GAP = 1.5D;

    private CustomFormationMath() {
    }

    public static List<Point> sanitize(List<Point> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Point> result = new ArrayList<>(Math.min(raw.size(), MAX_STROKE_POINTS));
        Point last = null;
        for (Point point : raw) {
            if (point == null || !finite(point)) {
                continue;
            }
            if (last == null || distance(last, point) >= MIN_POINT_STEP) {
                result.add(point);
                last = point;
                if (result.size() >= MAX_STROKE_POINTS) {
                    break;
                }
            }
        }
        return List.copyOf(result);
    }

    /** A light three-point moving average that keeps the first and last points fixed. */
    public static List<Point> smooth(List<Point> input, int passes) {
        List<Point> points = sanitize(input);
        if (points.size() < 3 || passes <= 0) {
            return points;
        }
        List<Point> current = new ArrayList<>(points);
        int safePasses = Math.min(3, passes);
        for (int pass = 0; pass < safePasses; pass++) {
            List<Point> next = new ArrayList<>(current.size());
            next.add(current.get(0));
            for (int i = 1; i < current.size() - 1; i++) {
                Point a = current.get(i - 1);
                Point b = current.get(i);
                Point c = current.get(i + 1);
                next.add(new Point(
                        (a.x + 2.0D * b.x + c.x) / 4.0D,
                        (a.z + 2.0D * b.z + c.z) / 4.0D
                ));
            }
            next.add(current.get(current.size() - 1));
            current = next;
        }
        return List.copyOf(current);
    }

    public static double length(List<Point> path) {
        if (path == null || path.size() < 2) {
            return 0.0D;
        }
        double total = 0.0D;
        for (int i = 1; i < path.size(); i++) {
            total += distance(path.get(i - 1), path.get(i));
        }
        return total;
    }

    /** Split a stroke into contiguous group ranges, preserving group order. */
    public static List<PathRange> splitByCounts(List<Integer> troopCounts, double totalLength) {
        if (troopCounts == null || troopCounts.isEmpty() || totalLength <= 0.0D) {
            return List.of();
        }
        int totalTroops = 0;
        List<Integer> counts = new ArrayList<>(troopCounts.size());
        for (Integer raw : troopCounts) {
            int count = raw == null ? 0 : Math.max(0, raw);
            counts.add(count);
            totalTroops += count;
        }
        if (totalTroops <= 0) {
            return List.of();
        }

        int groups = counts.size();
        double gap = groups <= 1 ? 0.0D : Math.min(DEFAULT_GROUP_GAP, totalLength / (groups * 4.0D));
        double usable = Math.max(0.05D, totalLength - gap * (groups - 1));
        double cursor = 0.0D;
        double remaining = usable;
        int remainingTroops = totalTroops;
        List<PathRange> result = new ArrayList<>(groups);

        for (int i = 0; i < groups; i++) {
            int count = counts.get(i);
            double span;
            if (i == groups - 1) {
                span = remaining;
            } else if (remainingTroops <= 0) {
                span = 0.0D;
            } else {
                span = usable * count / totalTroops;
                span = Math.min(span, remaining);
            }
            result.add(new PathRange(cursor, cursor + span));
            cursor += span;
            remaining -= span;
            remainingTroops -= count;
            if (i < groups - 1) {
                cursor += gap;
            }
        }
        return List.copyOf(result);
    }

    /**
     * Create exactly troopCount ordered slots inside one group's contiguous stroke range.
     * Slot order is curve progression first and rank second, making index-to-index recruit
     * assignment deterministic rather than nearest-slot/random-looking.
     */
    public static List<Slot> slotsForRange(
            List<Point> rawPath,
            PathRange range,
            int troopCount,
            int requestedRanks,
            double rankSpacing
    ) {
        List<Point> path = sanitize(rawPath);
        int count = Math.max(0, troopCount);
        if (path.size() < 2 || range == null || count == 0) {
            return List.of();
        }

        double total = length(path);
        if (total <= 1.0E-6D) {
            return List.of();
        }
        double start = clamp(range.startDistance, 0.0D, total);
        double end = clamp(range.endDistance, start, total);
        int ranks = Math.max(1, Math.min(Math.min(MAX_RANKS, count), requestedRanks));
        int columns = (int) Math.ceil(count / (double) ranks);
        double depthStep = clamp(rankSpacing, 0.75D, 1.5D);

        List<Slot> slots = new ArrayList<>(count);
        for (int column = 0; column < columns && slots.size() < count; column++) {
            double distanceAlong = columns <= 1
                    ? (start + end) * 0.5D
                    : start + (end - start) * column / (columns - 1.0D);
            Sample sample = sample(path, distanceAlong);
            // Path tangent is formation-right. Front/facing is its left normal.
            double forwardX = -sample.tangentZ;
            double forwardZ = sample.tangentX;
            float facingYaw = (float) Math.toDegrees(Math.atan2(sample.tangentZ, sample.tangentX));

            for (int rank = 0; rank < ranks && slots.size() < count; rank++) {
                double behind = rank * depthStep;
                slots.add(new Slot(
                        sample.point.x - forwardX * behind,
                        sample.point.z - forwardZ * behind,
                        facingYaw,
                        distanceAlong,
                        rank
                ));
            }
        }
        return List.copyOf(slots);
    }

    /** Arc-distance of the closest point on a polyline, for deterministic sorting. */
    public static double closestArcDistance(List<Point> path, Point point) {
        if (path == null || path.size() < 2 || point == null) {
            return 0.0D;
        }
        double walked = 0.0D;
        double bestDistanceSq = Double.POSITIVE_INFINITY;
        double bestArc = 0.0D;
        for (int i = 1; i < path.size(); i++) {
            Point a = path.get(i - 1);
            Point b = path.get(i);
            double dx = b.x - a.x;
            double dz = b.z - a.z;
            double lenSq = dx * dx + dz * dz;
            double segmentLength = Math.sqrt(lenSq);
            if (lenSq < 1.0E-10D) {
                continue;
            }
            double t = ((point.x - a.x) * dx + (point.z - a.z) * dz) / lenSq;
            t = clamp(t, 0.0D, 1.0D);
            double px = a.x + dx * t;
            double pz = a.z + dz * t;
            double ddx = point.x - px;
            double ddz = point.z - pz;
            double d2 = ddx * ddx + ddz * ddz;
            if (d2 < bestDistanceSq) {
                bestDistanceSq = d2;
                bestArc = walked + segmentLength * t;
            }
            walked += segmentLength;
        }
        return bestArc;
    }

    /** Sort point indexes by their current progression along the destination stroke. */
    public static List<Integer> orderByPath(List<Point> path, List<Point> points) {
        List<Integer> indexes = new ArrayList<>();
        if (points == null) {
            return indexes;
        }
        for (int i = 0; i < points.size(); i++) {
            indexes.add(i);
        }
        indexes.sort(Comparator
                .comparingDouble((Integer index) -> closestArcDistance(path, points.get(index)))
                .thenComparingInt(Integer::intValue));
        return List.copyOf(indexes);
    }

    public static Sample sample(List<Point> path, double requestedDistance) {
        if (path == null || path.isEmpty()) {
            return new Sample(new Point(0.0D, 0.0D), 1.0D, 0.0D);
        }
        if (path.size() == 1) {
            return new Sample(path.get(0), 1.0D, 0.0D);
        }
        double remaining = Math.max(0.0D, requestedDistance);
        for (int i = 1; i < path.size(); i++) {
            Point a = path.get(i - 1);
            Point b = path.get(i);
            double dx = b.x - a.x;
            double dz = b.z - a.z;
            double segment = Math.sqrt(dx * dx + dz * dz);
            if (segment < 1.0E-8D) {
                continue;
            }
            if (remaining <= segment || i == path.size() - 1) {
                double t = clamp(remaining / segment, 0.0D, 1.0D);
                return new Sample(
                        new Point(a.x + dx * t, a.z + dz * t),
                        dx / segment,
                        dz / segment
                );
            }
            remaining -= segment;
        }
        Point a = path.get(path.size() - 2);
        Point b = path.get(path.size() - 1);
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        double segment = Math.max(1.0E-8D, Math.sqrt(dx * dx + dz * dz));
        return new Sample(b, dx / segment, dz / segment);
    }

    private static boolean finite(Point point) {
        return Double.isFinite(point.x) && Double.isFinite(point.z);
    }

    private static double distance(Point a, Point b) {
        return Math.hypot(b.x - a.x, b.z - a.z);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public record Point(double x, double z) {
    }

    public record PathRange(double startDistance, double endDistance) {
    }

    public record Slot(double x, double z, float facingYaw, double arcDistance, int rank) {
    }

    public record Sample(Point point, double tangentX, double tangentZ) {
    }
}
