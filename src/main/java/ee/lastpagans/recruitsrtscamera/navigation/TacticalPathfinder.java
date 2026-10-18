package ee.lastpagans.recruitsrtscamera.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Collision-shape-aware tactical A* used by RTS formations.
 *
 * <p>Nodes represent real standing surfaces rather than heightmap columns. This makes slabs,
 * stairs and modded collision shapes first-class terrain, rejects unsupported air over holes,
 * and adds explicit vertical links through climbable blocks. The returned points are centered
 * on their supporting shape so local navigation does not scrape stair or ladder edges.</p>
 */
public final class TacticalPathfinder {
    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final double MAX_STEP_UP = 1.25D;
    private static final double MAX_DROP = 1.50D;

    private TacticalPathfinder() {
    }

    public static Route find(ServerLevel level, Mob mover, Vec3 destination, int maxVisited) {
        return find(level, mover, mover == null ? null : mover.position(), destination, maxVisited);
    }

    public static Route find(ServerLevel level, Mob mover, Vec3 startPosition, Vec3 destination, int maxVisited) {
        if (level == null || mover == null || startPosition == null || destination == null
                || !finite(startPosition) || !finite(destination)) {
            return Route.EMPTY;
        }
        Cell start = nearestCell(level, mover, startPosition, 1, 2);
        Cell goal = nearestCell(level, mover, destination, 2, 5);
        if (start == null || goal == null) return Route.EMPTY;

        PriorityQueue<SearchNode> open = new PriorityQueue<>(Comparator.comparingDouble(SearchNode::score));
        Map<BlockPos, Double> cost = new HashMap<>();
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        Map<BlockPos, Cell> cells = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();
        cost.put(start.key, 0.0D);
        cells.put(start.key, start);
        open.add(new SearchNode(start, heuristic(start, goal)));
        Cell closest = start;
        double closestDistance = heuristic(start, goal);
        int visited = 0;

        while (!open.isEmpty() && visited++ < Math.max(128, maxVisited)) {
            SearchNode search = open.poll();
            Cell current = search.cell;
            if (!closed.add(current.key)) continue;
            double remaining = heuristic(current, goal);
            if (remaining < closestDistance) {
                closestDistance = remaining;
                closest = current;
            }
            if (reached(current, goal)) {
                return build(level, mover, current, start, parent, cells, true);
            }
            for (Cell neighbor : neighbors(level, mover, current)) {
                if (closed.contains(neighbor.key)) continue;
                double next = cost.get(current.key) + movementCost(current, neighbor);
                if (next + 1.0E-6D >= cost.getOrDefault(neighbor.key, Double.POSITIVE_INFINITY)) continue;
                cost.put(neighbor.key, next);
                parent.put(neighbor.key, current.key);
                cells.put(neighbor.key, neighbor);
                open.add(new SearchNode(neighbor, next + heuristic(neighbor, goal)));
            }
        }
        Route partial = build(level, mover, closest, start, parent, cells, false);
        return partial.points.size() > 1 ? partial : Route.EMPTY;
    }

    /** Resolve a safe standing point near a desired slot without consulting a heightmap. */
    public static Vec3 nearestStandable(ServerLevel level, Mob mover, Vec3 desired) {
        Cell cell = nearestCell(level, mover, desired, 2, 4);
        return cell == null ? null : cell.position;
    }

    /** Resolve only the requested column. Used by local steering so it never selects a ledge beside the probe. */
    public static Vec3 standableAt(ServerLevel level, Mob mover, Vec3 desired) {
        BlockPos origin = BlockPos.containing(desired);
        Cell best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        double localX = desired.x - origin.getX();
        double localZ = desired.z - origin.getZ();
        for (int floorY = origin.getY() - 2; floorY <= origin.getY() + 2; floorY++) {
            Cell cell = standingOn(level, mover, new BlockPos(origin.getX(), floorY, origin.getZ()), localX, localZ);
            if (cell == null) continue;
            double score = Math.abs(cell.position.y - desired.y);
            if (score < bestScore) {
                best = cell;
                bestScore = score;
            }
        }
        Cell climb = climbableCell(level, mover, origin);
        if (climb != null && (best == null || climb.position.distanceToSqr(desired) < best.position.distanceToSqr(desired))) {
            best = climb;
        }
        return best == null ? null : best.position;
    }

    /**
     * Cheap visibility test for local steering. Every sampled column must have a collision-shape-backed
     * standing point with a traversable height change; unsupported samples make a hole impassable.
     */
    public static boolean canSteerDirect(ServerLevel level, Mob mover, Vec3 destination) {
        Vec3 start = mover.position();
        double horizontal = Math.hypot(destination.x - start.x, destination.z - start.z);
        int samples = Math.max(1, (int) Math.ceil(horizontal / 0.45D));
        double previousY = start.y;
        for (int i = 1; i <= samples; i++) {
            double fraction = i / (double) samples;
            Vec3 probe = start.lerp(destination, fraction);
            Cell cell = nearestCell(level, mover, probe, 0, 2);
            if (cell == null) return false;
            double change = cell.position.y - previousY;
            if (!cell.climbable && (change > MAX_STEP_UP || change < -MAX_DROP)) return false;
            previousY = cell.position.y;
        }
        return true;
    }

    public static boolean isClimbable(ServerLevel level, BlockPos position) {
        return level != null && position != null && level.getBlockState(position).is(BlockTags.CLIMBABLE);
    }

    private static List<Cell> neighbors(ServerLevel level, Mob mover, Cell current) {
        List<Cell> result = new ArrayList<>(12);
        for (int[] direction : CARDINALS) {
            addHorizontal(level, mover, current, direction[0], direction[1], false, result);
        }
        for (int[] direction : DIAGONALS) {
            addHorizontal(level, mover, current, direction[0], direction[1], true, result);
        }
        if (current.climbable || isClimbable(level, BlockPos.containing(current.position))) {
            addClimb(level, mover, current, 1, result);
            addClimb(level, mover, current, -1, result);
        }
        return result;
    }

    private static void addHorizontal(
            ServerLevel level,
            Mob mover,
            Cell current,
            int dx,
            int dz,
            boolean diagonal,
            List<Cell> result
    ) {
        Cell best = null;
        double bestDelta = Double.POSITIVE_INFINITY;
        int baseY = (int) Math.floor(current.position.y);
        for (int floorY = baseY - 2; floorY <= baseY + 1; floorY++) {
            Cell candidate = standingOn(level, mover, new BlockPos(current.key.getX() + dx, floorY,
                    current.key.getZ() + dz));
            if (candidate == null) continue;
            double rise = candidate.position.y - current.position.y;
            if (!current.climbable && !candidate.climbable && (rise > MAX_STEP_UP || rise < -MAX_DROP)) continue;
            double delta = Math.abs(rise);
            if (delta < bestDelta) {
                best = candidate;
                bestDelta = delta;
            }
        }
        if (best == null) {
            Cell ladder = climbableCell(level, mover,
                    new BlockPos(current.key.getX() + dx, BlockPos.containing(current.position).getY(),
                            current.key.getZ() + dz));
            if (ladder != null) best = ladder;
        }
        if (best == null) return;
        if (diagonal && (!columnPassable(level, mover, current, dx, 0)
                || !columnPassable(level, mover, current, 0, dz))) return;
        result.add(best);
    }

    private static boolean columnPassable(ServerLevel level, Mob mover, Cell current, int dx, int dz) {
        int baseY = (int) Math.floor(current.position.y);
        for (int y = baseY - 2; y <= baseY + 1; y++) {
            Cell cell = standingOn(level, mover,
                    new BlockPos(current.key.getX() + dx, y, current.key.getZ() + dz));
            if (cell != null) {
                double change = cell.position.y - current.position.y;
                if (change <= MAX_STEP_UP && change >= -MAX_DROP) return true;
            }
        }
        return false;
    }

    private static void addClimb(ServerLevel level, Mob mover, Cell current, int dy, List<Cell> result) {
        BlockPos next = BlockPos.containing(current.position).offset(0, dy, 0);
        Cell climb = climbableCell(level, mover, next);
        if (climb != null) {
            result.add(climb);
            return;
        }
        if (dy > 0) {
            for (int[] direction : CARDINALS) {
                Cell exit = standingOn(level, mover, new BlockPos(next.getX() + direction[0], next.getY() - 1,
                        next.getZ() + direction[1]));
                if (exit != null && exit.position.y >= current.position.y) result.add(exit);
            }
        }
    }

    private static Cell nearestCell(
            ServerLevel level,
            Mob mover,
            Vec3 target,
            int horizontalRadius,
            int verticalRadius
    ) {
        Cell best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        BlockPos origin = BlockPos.containing(target);
        for (int radius = 0; radius <= horizontalRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (int floorY = origin.getY() - verticalRadius; floorY <= origin.getY() + verticalRadius; floorY++) {
                        Cell cell = standingOn(level, mover, new BlockPos(origin.getX() + dx, floorY, origin.getZ() + dz));
                        if (cell == null) continue;
                        double score = cell.position.distanceToSqr(target);
                        if (score < bestScore) {
                            best = cell;
                            bestScore = score;
                        }
                    }
                    for (int y = origin.getY() - verticalRadius; y <= origin.getY() + verticalRadius; y++) {
                        Cell climb = climbableCell(level, mover, new BlockPos(origin.getX() + dx, y, origin.getZ() + dz));
                        if (climb == null) continue;
                        double score = climb.position.distanceToSqr(target) + 0.15D;
                        if (score < bestScore) {
                            best = climb;
                            bestScore = score;
                        }
                    }
                }
            }
            if (best != null && radius == 0) break;
        }
        return best;
    }

    private static Cell standingOn(ServerLevel level, Mob mover, BlockPos floor) {
        return standingOn(level, mover, floor, 0.5D, 0.5D);
    }

    private static Cell standingOn(
            ServerLevel level,
            Mob mover,
            BlockPos floor,
            double preferredX,
            double preferredZ
    ) {
        if (!level.hasChunkAt(floor) || floor.getY() <= level.getMinBuildHeight()
                || floor.getY() >= level.getMaxBuildHeight() - 2) return null;
        BlockState state = level.getBlockState(floor);
        VoxelShape shape = state.getCollisionShape(level, floor, CollisionContext.of(mover));
        if (shape.isEmpty()) return null;

        AABB chosen = null;
        double chosenDistance = Double.POSITIVE_INFINITY;
        for (AABB box : shape.toAabbs()) {
            double width = box.maxX - box.minX;
            double depth = box.maxZ - box.minZ;
            double required = Math.min(0.95D, mover.getBbWidth() * 0.70D);
            if (width + 1.0E-5D < required || depth + 1.0E-5D < required) continue;
            double cx = clamp(preferredX, box.minX + required * 0.5D, box.maxX - required * 0.5D);
            double cz = clamp(preferredZ, box.minZ + required * 0.5D, box.maxZ - required * 0.5D);
            double distance = (cx - preferredX) * (cx - preferredX) + (cz - preferredZ) * (cz - preferredZ);
            if (chosen == null || box.maxY > chosen.maxY + 1.0E-5D
                    || Math.abs(box.maxY - chosen.maxY) < 1.0E-5D && distance < chosenDistance) {
                chosen = new AABB(cx, box.minY, cz, cx, box.maxY, cz);
                chosenDistance = distance;
            }
        }
        if (chosen == null) return null;
        Vec3 feet = new Vec3(floor.getX() + chosen.minX, floor.getY() + chosen.maxY,
                floor.getZ() + chosen.minZ);
        if (!collisionFree(level, mover, feet)) return null;
        BlockPos key = new BlockPos(floor.getX(), (int) Math.floor(feet.y + 1.0E-4D), floor.getZ());
        return new Cell(key, feet, false);
    }

    private static Cell climbableCell(ServerLevel level, Mob mover, BlockPos position) {
        if (!level.hasChunkAt(position) || !level.getBlockState(position).is(BlockTags.CLIMBABLE)) return null;
        Vec3 feet = Vec3.atBottomCenterOf(position);
        AABB destination = destinationBox(mover, feet).deflate(0.04D);
        if (!level.noCollision(mover, destination)) return null;
        return new Cell(position.immutable(), feet, true);
    }

    private static boolean collisionFree(ServerLevel level, Mob mover, Vec3 feet) {
        return level.noCollision(mover, destinationBox(mover, feet).deflate(0.025D));
    }

    private static AABB destinationBox(Mob mover, Vec3 feet) {
        AABB current = mover.getBoundingBox();
        return current.move(feet.x - mover.getX(), feet.y - mover.getY(), feet.z - mover.getZ());
    }

    private static Route build(
            ServerLevel level,
            Mob mover,
            Cell end,
            Cell start,
            Map<BlockPos, BlockPos> parent,
            Map<BlockPos, Cell> cells,
            boolean complete
    ) {
        List<Cell> reversed = new ArrayList<>();
        Cell cursor = end;
        reversed.add(cursor);
        while (!cursor.key.equals(start.key)) {
            BlockPos previous = parent.get(cursor.key);
            if (previous == null) break;
            cursor = cells.get(previous);
            if (cursor == null) break;
            reversed.add(cursor);
        }
        Collections.reverse(reversed);
        boolean constrained = false;
        List<Vec3> points = new ArrayList<>(reversed.size());
        Cell previous = null;
        for (int i = 0; i < reversed.size(); i++) {
            Cell cell = reversed.get(i);
            points.add(cell.position);
            if (cell.climbable || previous != null && Math.abs(cell.position.y - previous.position.y) > 0.40D) {
                constrained = true;
            }
            // A flat doorway, bridge, ledge, or one-block corridor also cannot carry a full
            // formation. Endpoints are ignored so a destination beside a wall can still reform.
            if (i > 0 && i + 1 < reversed.size() && openSides(level, mover, cell) < 4) constrained = true;
            previous = cell;
        }
        List<Vec3> immutable = List.copyOf(points);
        return new Route(immutable, complete, constrained, TacticalTerrainRevisions.snapshot(level, immutable));
    }

    private static int openSides(ServerLevel level, Mob mover, Cell cell) {
        int count = 0;
        for (int[] direction : CARDINALS) {
            if (columnPassable(level, mover, cell, direction[0], direction[1])) count++;
        }
        return count;
    }

    private static double movementCost(Cell a, Cell b) {
        double dx = a.position.x - b.position.x;
        double dy = a.position.y - b.position.y;
        double dz = a.position.z - b.position.z;
        return Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * (a.climbable || b.climbable ? 1.1D : 1.8D);
    }

    private static double heuristic(Cell a, Cell b) {
        return Math.abs(a.position.x - b.position.x) + Math.abs(a.position.z - b.position.z)
                + Math.abs(a.position.y - b.position.y) * 1.25D;
    }

    private static boolean reached(Cell current, Cell goal) {
        return Math.abs(current.position.x - goal.position.x) < 0.6D
                && Math.abs(current.position.z - goal.position.z) < 0.6D
                && Math.abs(current.position.y - goal.position.y) < 0.75D;
    }

    private static double clamp(double value, double min, double max) {
        if (min > max) return (min + max) * 0.5D;
        return Math.max(min, Math.min(max, value));
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private record Cell(BlockPos key, Vec3 position, boolean climbable) {}
    private record SearchNode(Cell cell, double score) {}

    public record Route(List<Vec3> points, boolean complete, boolean constrained, Map<Long, Long> terrainRevisions) {
        public static final Route EMPTY = new Route(List.of(), false, false, Map.of());

        public boolean isValid(ServerLevel level) {
            return TacticalTerrainRevisions.valid(level, terrainRevisions);
        }
    }
}
