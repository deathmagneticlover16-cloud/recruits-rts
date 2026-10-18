package ee.lastpagans.recruitsrtscamera.formation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Dependency-free formation geometry and stable minimum-distance slot assignment. */
public final class FormationSlotMath {
    private FormationSlotMath() {
    }

    public static List<Offset> create(
            FormationKind kind,
            int count,
            double spacing,
            int requestedRanks,
            double frontageWidth,
            boolean mounted
    ) {
        if (count <= 0) return List.of();
        double side = Math.max(1.0D, 1.02D * spacing) * (mounted ? 1.7D : 1.0D);
        double depth = Math.max(1.1D, 1.12D * spacing) * (mounted ? 1.7D : 1.0D);
        List<Offset> result = new ArrayList<>(count);
        switch (kind == null ? FormationKind.MARCH : kind) {
            case MARCH -> addGrid(result, count, Math.min(4, Math.max(2, (int) Math.ceil(Math.sqrt(count)))), side, depth);
            case LINE -> {
                int ranks = requestedRanks > 0 ? requestedRanks
                        : frontageWidth > 0.0D ? Math.max(1,
                        (int) Math.ceil(count / Math.max(1.0D, frontageWidth / side))) : 2;
                int columns = Math.max(1, (int) Math.ceil(count / (double) ranks));
                addGrid(result, count, columns, side, depth);
            }
            case SQUARE -> addGrid(result, count, (int) Math.ceil(Math.sqrt(count)), side, depth);
            case TRIANGLE -> {
                for (int row = 0; result.size() < count; row++) {
                    int width = row * 2 + 1;
                    for (int column = 0; column < width && result.size() < count; column++) {
                        result.add(new Offset((column - (width - 1) / 2.0D) * side, row * depth));
                    }
                }
            }
            case RING -> {
                double radius = Math.max(side, count * side / (Math.PI * 2.0D));
                for (int i = 0; i < count; i++) {
                    double angle = Math.PI * 2.0D * i / count;
                    result.add(new Offset(Math.cos(angle) * radius, Math.sin(angle) * radius));
                }
            }
            case CIRCLE, BUBBLE -> addDisk(result, count, side, depth);
            case HOLLOW_SQUARE -> addHollowSquare(result, count, side, depth);
            case VEE -> {
                result.add(new Offset(0.0D, 0.0D));
                for (int i = 1; i < count; i++) {
                    int rank = (i + 1) / 2;
                    result.add(new Offset((i % 2 == 1 ? -rank : rank) * side, rank * depth));
                }
            }
        }
        center(result);
        return List.copyOf(result);
    }

    /** Hungarian assignment for normal groups; deterministic greedy fallback for very large groups. */
    public static int[] assign(List<Point> units, List<Point> slots) {
        int count = Math.min(units.size(), slots.size());
        if (count <= 0) return new int[0];
        if (count > 120) return greedy(units, slots, count);
        double[][] costs = new double[count][count];
        for (int i = 0; i < count; i++) {
            for (int j = 0; j < count; j++) {
                double dx = units.get(i).x - slots.get(j).x;
                double dz = units.get(i).z - slots.get(j).z;
                costs[i][j] = dx * dx + dz * dz;
            }
        }
        return hungarian(costs);
    }

    private static int[] hungarian(double[][] costs) {
        int n = costs.length;
        double[] u = new double[n + 1];
        double[] v = new double[n + 1];
        int[] p = new int[n + 1];
        int[] way = new int[n + 1];
        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            double[] min = new double[n + 1];
            boolean[] used = new boolean[n + 1];
            Arrays.fill(min, Double.POSITIVE_INFINITY);
            do {
                used[j0] = true;
                int i0 = p[j0];
                double delta = Double.POSITIVE_INFINITY;
                int j1 = 0;
                for (int j = 1; j <= n; j++) {
                    if (used[j]) continue;
                    double current = costs[i0 - 1][j - 1] - u[i0] - v[j];
                    if (current < min[j]) {
                        min[j] = current;
                        way[j] = j0;
                    }
                    if (min[j] < delta) {
                        delta = min[j];
                        j1 = j;
                    }
                }
                for (int j = 0; j <= n; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        min[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);
            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }
        int[] result = new int[n];
        for (int j = 1; j <= n; j++) result[p[j] - 1] = j - 1;
        return result;
    }

    private static int[] greedy(List<Point> units, List<Point> slots, int count) {
        int[] result = new int[count];
        boolean[] used = new boolean[count];
        for (int unit = 0; unit < count; unit++) {
            Point position = units.get(unit);
            int best = -1;
            double bestCost = Double.POSITIVE_INFINITY;
            for (int slot = 0; slot < count; slot++) {
                if (used[slot]) continue;
                Point candidate = slots.get(slot);
                double dx = position.x - candidate.x;
                double dz = position.z - candidate.z;
                double cost = dx * dx + dz * dz;
                if (cost < bestCost) {
                    bestCost = cost;
                    best = slot;
                }
            }
            result[unit] = best;
            used[best] = true;
        }
        return result;
    }

    private static void addGrid(List<Offset> result, int count, int columns, double side, double depth) {
        for (int i = 0; i < count; i++) {
            int row = i / columns;
            int rowCount = Math.min(columns, count - row * columns);
            int column = i % columns;
            result.add(new Offset((column - (rowCount - 1) / 2.0D) * side, row * depth));
        }
    }

    private static void addDisk(List<Offset> result, int count, double side, double depth) {
        result.add(new Offset(0.0D, 0.0D));
        for (int ring = 1; result.size() < count; ring++) {
            int capacity = ring * 6;
            for (int i = 0; i < capacity && result.size() < count; i++) {
                double angle = Math.PI * 2.0D * i / capacity;
                result.add(new Offset(Math.cos(angle) * ring * side, Math.sin(angle) * ring * depth));
            }
        }
    }

    private static void addHollowSquare(List<Offset> result, int count, double side, double depth) {
        int width = Math.max(2, (int) Math.ceil((count + 4) / 4.0D));
        int edge = Math.max(1, width - 1);
        int perimeter = Math.max(1, width * 4 - 4);
        double half = edge / 2.0D;
        for (int i = 0; i < count; i++) {
            int position = i * perimeter / count;
            int sideIndex = Math.min(3, position / edge);
            int along = position % edge;
            result.add(switch (sideIndex) {
                case 0 -> new Offset((along - half) * side, -half * depth);
                case 1 -> new Offset(half * side, (along - half) * depth);
                case 2 -> new Offset((half - along) * side, half * depth);
                default -> new Offset(-half * side, (half - along) * depth);
            });
        }
    }

    private static void center(List<Offset> offsets) {
        double right = offsets.stream().mapToDouble(Offset::right).average().orElse(0.0D);
        double back = offsets.stream().mapToDouble(Offset::back).average().orElse(0.0D);
        for (int i = 0; i < offsets.size(); i++) {
            Offset offset = offsets.get(i);
            offsets.set(i, new Offset(offset.right - right, offset.back - back));
        }
    }

    public record Offset(double right, double back) {}
    public record Point(double x, double z) {}
}
