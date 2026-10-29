package ee.lastpagans.recruitsrtscamera.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitRuntimeMetrics;

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
    private static final double FORMATION_SLOT_MAX_DROP = 1.25D;
    private static final long SEARCH_TIME_BUDGET_NANOS = 20_000_000L;

    private TacticalPathfinder() {
    }

    public static Route find(ServerLevel level, Mob mover, Vec3 destination, int maxVisited) {
        return find(level, mover, mover == null ? null : mover.position(), destination, maxVisited);
    }

    public static Route find(ServerLevel level, Mob mover, Vec3 startPosition, Vec3 destination, int maxVisited) {
        SearchSession session = begin(level, mover, startPosition, destination, maxVisited);
        while (!session.advance(System.nanoTime() + SEARCH_TIME_BUDGET_NANOS)) {
            // Compatibility entry point. Runtime formation code uses the resumable session
            // directly; this loop remains for isolated callers and tests.
        }
        return session.result();
    }

    public static SearchSession begin(
            ServerLevel level, Mob mover, Vec3 startPosition, Vec3 destination, int maxVisited
    ) {
        return new SearchSession(level, mover, startPosition, destination, maxVisited);
    }

    /** Resolve a safe standing point near a desired slot without consulting a heightmap. */
    public static Vec3 nearestStandable(ServerLevel level, Mob mover, Vec3 desired) {
        Cell cell = nearestCell(level, mover, desired, 2, 4);
        return cell == null ? null : cell.position;
    }

    /**
     * Resolve a formation slot without silently moving it to the bottom of a hole.
     * Small shape-height differences are valid; a surface more than a step below the
     * formation plane is not. In that case an adjacent surface at the intended level
     * is preferred so the member routes around the opening.
     */
    public static Vec3 nearestFormationStandable(ServerLevel level, Mob mover, Vec3 desired) {
        Cell best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        BlockPos origin = BlockPos.containing(desired);
        for (int radius = 0; radius <= 2; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (int floorY = origin.getY() - 1; floorY <= origin.getY() + 2; floorY++) {
                        Cell cell = standingOn(level, mover,
                                new BlockPos(origin.getX() + dx, floorY, origin.getZ() + dz));
                        if (cell == null || cell.position.y < desired.y - FORMATION_SLOT_MAX_DROP) continue;
                        double vertical = Math.abs(cell.position.y - desired.y);
                        double score = horizontalDistanceSq(cell.position, desired) + vertical * vertical * 2.0D;
                        if (score < bestScore) {
                            best = cell;
                            bestScore = score;
                        }
                    }
                }
            }
        }
        return best == null ? null : best.position;
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
     * Finds an ordinary collision-backed rise immediately in front of a mover. Horses
     * touch a block face before their centre enters its column, so waiting for the normal
     * probe to change elevation can deadlock them against a one-block step. This helper
     * accepts only a dry, collision-free surface above the current feet and therefore
     * cannot turn holes, drops or water pits into jumpable transitions.
     */
    public static Vec3 supportedRiseAhead(ServerLevel level, Mob mover, Vec3 direction) {
        if (level == null || mover == null || direction == null) return null;
        Vec3 horizontal = new Vec3(direction.x, 0.0D, direction.z);
        if (horizontal.lengthSqr() < 1.0E-6D) return null;
        horizontal = horizontal.normalize();
        double first = Math.max(0.72D, mover.getBbWidth() * 0.58D);
        double[] distances = {first, Math.max(1.02D, first + 0.30D), Math.max(1.34D, first + 0.60D)};
        for (double distance : distances) {
            Vec3 sample = mover.position().add(horizontal.scale(distance)).add(0.0D, 0.95D, 0.0D);
            Vec3 surface = standableAt(level, mover, sample);
            if (surface == null) continue;
            double rise = surface.y - mover.getY();
            if (rise <= 0.42D || rise > MAX_STEP_UP) continue;
            // Prove every column leading to the upper surface. This excludes a raised
            // far bank across a hole and water-filled gaps while accepting the supported
            // lower approach followed by one ordinary block face.
            boolean supportedApproach = true;
            for (double along = 0.25D; along < distance; along += 0.25D) {
                Vec3 approach = standableAt(level, mover,
                        mover.position().add(horizontal.scale(along)));
                if (approach == null || approach.y < mover.getY() - 0.60D
                        || approach.y > mover.getY() + MAX_STEP_UP) {
                    supportedApproach = false;
                    break;
                }
            }
            if (supportedApproach) return surface;
        }
        return null;
    }

    /**
     * Cheap visibility test for local steering. Every sampled column must have a collision-shape-backed
     * standing point with a traversable height change; unsupported samples make a hole impassable.
     */
    public static boolean canSteerDirect(ServerLevel level, Mob mover, Vec3 destination) {
        return canSteerDirect(level, mover, destination, false);
    }

    /**
     * Variant used after a grounded formation slot has already proved that the immediate
     * destination is one supported level below the mover. The explicit flag prevents a
     * level route from treating the first block of a hole as a valid descent.
     */
    public static boolean canSteerDirect(
            ServerLevel level,
            Mob mover,
            Vec3 destination,
            boolean allowOrdinaryDescent
    ) {
        Vec3 start = mover.position();
        double horizontal = Math.hypot(destination.x - start.x, destination.z - start.z);
        int samples = Math.max(1, (int) Math.ceil(horizontal / 0.45D));
        double previousY = start.y;
        // A commanded lower destination may descend one ordinary supported block. A route
        // whose destination remains level may not dip through a one-block hole and climb
        // out again, preserving the existing pit/canyon rejection.
        double allowedDrop = allowOrdinaryDescent || destination.y < start.y - 0.60D
                ? FORMATION_SLOT_MAX_DROP : 0.60D;
        for (int i = 1; i <= samples; i++) {
            double fraction = i / (double) samples;
            Vec3 probe = start.lerp(destination, fraction);
            Cell cell = nearestCell(level, mover, probe, 0, 2);
            if (cell == null) return false;
            double change = cell.position.y - previousY;
            // Direct steering must never interpret the first block down into a pit as a
            // clear straight route. A* may still choose a supported descent when it is
            // genuinely required, but it can then prefer a level detour around the hole.
            if (!cell.climbable && (change > MAX_STEP_UP || change < -allowedDrop)) return false;
            previousY = cell.position.y;
        }
        return true;
    }

    public static boolean isClimbable(ServerLevel level, BlockPos position) {
        return level != null && position != null && level.getBlockState(position).is(BlockTags.CLIMBABLE);
    }

    /** Whether this immediate route cell is too narrow for formation frontage. */
    public static boolean requiresColumn(ServerLevel level, Mob mover, Vec3 position) {
        Cell cell = nearestCell(level, mover, position, 0, 2);
        return cell != null && (cell.climbable || openSides(level, mover, cell) <= 2);
    }

    private static void neighbors(ServerLevel level, Mob mover, Cell current, List<Cell> result) {
        result.clear();
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
        List<AABB> collisionBoxes = TacticalTerrainCache.collisionBoxes(level, mover, floor);
        if (collisionBoxes.isEmpty()) return null;

        AABB chosen = null;
        double chosenDistance = Double.POSITIVE_INFINITY;
        for (AABB box : collisionBoxes) {
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
        if (!dryAt(level, mover, feet)) return null;
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

    private static boolean dryAt(ServerLevel level, Mob mover, Vec3 feet) {
        AABB box = destinationBox(mover, feet).deflate(0.05D);
        int minX = (int) Math.floor(box.minX);
        int maxX = (int) Math.floor(box.maxX - 1.0E-5D);
        int minY = (int) Math.floor(box.minY);
        int maxY = (int) Math.floor(box.maxY - 1.0E-5D);
        int minZ = (int) Math.floor(box.minZ);
        int maxZ = (int) Math.floor(box.maxZ - 1.0E-5D);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!TacticalTerrainCache.fluidEmpty(level, new BlockPos(x, y, z))) return false;
                }
            }
        }
        return true;
    }

    private static AABB destinationBox(Mob mover, Vec3 feet) {
        AABB current = mover.getBoundingBox();
        return current.move(feet.x - mover.getX(), feet.y - mover.getY(), feet.z - mover.getZ());
    }

    private static int openSides(ServerLevel level, Mob mover, Cell cell) {
        int count = 0;
        for (int[] direction : CARDINALS) {
            if (columnPassable(level, mover, cell, direction[0], direction[1])) count++;
        }
        return count;
    }

    private static double movementCost(ServerLevel level, Mob mover, Cell a, Cell b,
                                       double endpointFloor) {
        double dx = a.position.x - b.position.x;
        double dy = a.position.y - b.position.y;
        double dz = a.position.z - b.position.z;
        double descentPenalty = !a.climbable && !b.climbable && dy > 0.60D ? 7.0D * dy : 0.0D;
        // A supported one-block rise is an ordinary horse jump, not a reason to route
        // cavalry around an entire ledge. This changes only the cost of an already-safe
        // collision-backed transition; dry/support/drop checks remain unchanged.
        double elevationCost = a.climbable || b.climbable ? 1.1D
                : mover instanceof AbstractHorse && dy < -0.40D && dy >= -MAX_STEP_UP ? 0.65D
                : 1.8D;
        // If both order endpoints are on the rim, descending into the canyon is almost
        // never the human route. Keep supported downhill travel valid when the destination
        // itself is lower, but strongly prefer a modest level detour between level endpoints.
        double canyonDepth = Math.max(0.0D, endpointFloor - b.position.y);
        double canyonPenalty = canyonDepth * canyonDepth * 12.0D;
        return Math.sqrt(dx * dx + dz * dz)
                + Math.abs(dy) * elevationCost
                + descentPenalty
                + canyonPenalty;
    }

    private static double heuristic(Cell a, Cell b) {
        double dx = a.position.x - b.position.x;
        double dz = a.position.z - b.position.z;
        // Euclidean horizontal distance is admissible with diagonal movement and avoids
        // the bend-heavy routes produced by a Manhattan estimate on an eight-way grid.
        return Math.sqrt(dx * dx + dz * dz) + Math.abs(a.position.y - b.position.y) * 0.65D;
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

    private static double horizontalDistanceSq(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private record Cell(BlockPos key, Vec3 position, boolean climbable) {}
    private record SearchNode(Cell cell, double score) {}

    /** Route reconstruction and line-of-sight smoothing retained across scheduler slices. */
    private static final class RouteBuildSession {
        private final ServerLevel level;
        private final Mob mover;
        private final Cell start;
        private final Map<BlockPos, BlockPos> parent;
        private final Map<BlockPos, Cell> cells;
        private final boolean complete;
        private final List<Cell> routeCells = new ArrayList<>();
        private final Set<BlockPos> requiredAnchors = new HashSet<>();
        private final List<Cell> simplified = new ArrayList<>();
        private final List<Vec3> points = new ArrayList<>();
        private final Map<Long, Long> terrainRevisions = new HashMap<>();
        private Cell cursor;
        private Cell previous;
        private int phase;
        private int scanIndex;
        private int from;
        private int candidate = -1;
        private int finalizeIndex;
        private boolean constrained;
        private SegmentProbe segmentProbe;
        private Route result = Route.EMPTY;

        private RouteBuildSession(
                ServerLevel level,
                Mob mover,
                Cell end,
                Cell start,
                Map<BlockPos, BlockPos> parent,
                Map<BlockPos, Cell> cells,
                boolean complete
        ) {
            this.level = level;
            this.mover = mover;
            this.cursor = end;
            this.start = start;
            this.parent = parent;
            this.cells = cells;
            this.complete = complete;
            routeCells.add(end);
        }

        private boolean advance(long deadlineNanos) {
            if (phase == 0 && !reconstruct(deadlineNanos)) return false;
            if (phase == 1 && !analyze(deadlineNanos)) return false;
            if (phase == 2 && !smooth(deadlineNanos)) return false;
            if (phase == 3 && !finalizeRoute(deadlineNanos)) return false;
            return phase == 4;
        }

        private boolean reconstruct(long deadlineNanos) {
            int work = 0;
            while (!cursor.key.equals(start.key)) {
                BlockPos previousKey = parent.get(cursor.key);
                if (previousKey == null) break;
                Cell next = cells.get(previousKey);
                if (next == null) break;
                cursor = next;
                routeCells.add(cursor);
                if ((++work & 31) == 0 && System.nanoTime() >= deadlineNanos) return false;
            }
            Collections.reverse(routeCells);
            phase = 1;
            return true;
        }

        private boolean analyze(long deadlineNanos) {
            while (scanIndex < routeCells.size()) {
                Cell cell = routeCells.get(scanIndex);
                int clearance = openSides(level, mover, cell);
                boolean narrow = cell.climbable;
                boolean changingLevel = previous != null
                        && Math.abs(cell.position.y - previous.position.y) > 0.40D;
                if (!changingLevel && scanIndex > 0 && scanIndex + 1 < routeCells.size() && clearance <= 2) {
                    narrow = true;
                }
                if (narrow) {
                    constrained = true;
                    requiredAnchors.add(cell.key);
                }
                previous = cell;
                scanIndex++;
                if ((scanIndex & 7) == 0 && System.nanoTime() >= deadlineNanos) return false;
            }
            if (!routeCells.isEmpty()) simplified.add(routeCells.get(0));
            phase = 2;
            return true;
        }

        private boolean smooth(long deadlineNanos) {
            if (routeCells.size() < 3) {
                simplified.clear();
                simplified.addAll(routeCells);
                phase = 3;
                return true;
            }
            while (from < routeCells.size() - 1) {
                if (candidate < 0) candidate = Math.min(routeCells.size() - 1, from + 48);
                if (candidate <= from + 1) {
                    accept(from + 1);
                    continue;
                }
                boolean containsAnchor = false;
                for (int index = from + 1; index < candidate; index++) {
                    if (requiredAnchors.contains(routeCells.get(index).key)) {
                        containsAnchor = true;
                        break;
                    }
                }
                if (containsAnchor) {
                    candidate--;
                    continue;
                }
                if (segmentProbe == null) {
                    segmentProbe = new SegmentProbe(routeCells.get(from).position, routeCells.get(candidate).position);
                }
                Boolean safe = segmentProbe.advance(level, mover, deadlineNanos);
                if (safe == null) return false;
                segmentProbe = null;
                if (safe) accept(candidate);
                else candidate--;
                if (System.nanoTime() >= deadlineNanos) return false;
            }
            phase = 3;
            return true;
        }

        private void accept(int index) {
            simplified.add(routeCells.get(index));
            from = index;
            candidate = -1;
            segmentProbe = null;
        }

        private boolean finalizeRoute(long deadlineNanos) {
            while (finalizeIndex < simplified.size()) {
                Vec3 point = simplified.get(finalizeIndex++).position;
                points.add(point);
                long region = TacticalTerrainRevisions.regionKey(point);
                terrainRevisions.putIfAbsent(region,
                        TacticalTerrainRevisions.revisionForRegion(level, region));
                if ((finalizeIndex & 31) == 0 && System.nanoTime() >= deadlineNanos) return false;
            }
            result = new Route(List.copyOf(points), complete, constrained, Map.copyOf(terrainRevisions));
            phase = 4;
            return true;
        }

        private Route result() {
            return result;
        }
    }

    /** Resumable safety probe used by route smoothing. Null means the current slice expired. */
    private static final class SegmentProbe {
        private final Vec3 from;
        private final Vec3 to;
        private final int samples;
        private int index = 1;
        private double previousY;

        private SegmentProbe(Vec3 from, Vec3 to) {
            this.from = from;
            this.to = to;
            this.samples = Math.max(1, (int) Math.ceil(Math.hypot(to.x - from.x, to.z - from.z) / 0.35D));
            this.previousY = from.y;
        }

        private Boolean advance(ServerLevel level, Mob mover, long deadlineNanos) {
            while (index <= samples) {
                Vec3 probe = from.lerp(to, index / (double) samples);
                Cell cell = nearestCell(level, mover, probe, 0, 2);
                if (cell == null || cell.climbable) return false;
                double change = cell.position.y - previousY;
                if (change > MAX_STEP_UP || change < -0.60D) return false;
                previousY = cell.position.y;
                index++;
                if ((index & 3) == 0 && System.nanoTime() >= deadlineNanos) return null;
            }
            return true;
        }
    }

    /** A* state retained across scheduler ticks so difficult terrain never owns one long server tick. */
    public static final class SearchSession {
        private final ServerLevel level;
        private final Mob mover;
        private final Vec3 startPosition;
        private final Vec3 destination;
        private final int maxVisited;
        private final PriorityQueue<SearchNode> open = new PriorityQueue<>(Comparator.comparingDouble(SearchNode::score));
        private final Map<BlockPos, Double> cost = new HashMap<>();
        private final Map<BlockPos, BlockPos> parent = new HashMap<>();
        private final Map<BlockPos, Cell> cells = new HashMap<>();
        private final Set<BlockPos> closed = new HashSet<>();
        private final List<Cell> neighborBuffer = new ArrayList<>(12);
        private Cell start;
        private Cell goal;
        private Cell closest;
        private double closestDistance;
        private int visited;
        private long searchNanos;
        private long terrainEpoch;
        private boolean initialized;
        private boolean finished;
        private RouteBuildSession routeBuild;
        private Route result = Route.EMPTY;

        private SearchSession(ServerLevel level, Mob mover, Vec3 startPosition, Vec3 destination, int maxVisited) {
            this.level = level;
            this.mover = mover;
            this.startPosition = startPosition;
            this.destination = destination;
            this.maxVisited = Math.max(128, maxVisited);
        }

        public boolean advance(long deadlineNanos) {
            if (finished) return true;
            RecruitRuntimeMetrics.PATH_SLICES.increment();
            if (initialized && TacticalTerrainRevisions.epoch(level) != terrainEpoch) {
                cancelForTerrainChange();
                return true;
            }
            if (routeBuild != null) return advanceBuild(deadlineNanos);
            long sliceStarted = System.nanoTime();
            if (!initialized && !initialize()) return true;

            while (!open.isEmpty() && visited < maxVisited) {
                if ((visited & 3) == 0 && System.nanoTime() >= deadlineNanos) {
                    searchNanos += System.nanoTime() - sliceStarted;
                    if (searchNanos >= SEARCH_TIME_BUDGET_NANOS) beginBuild(closest, false);
                    return finished;
                }
                visited++;
                SearchNode search = open.poll();
                Cell current = search.cell;
                if (!closed.add(current.key)) continue;
                double remaining = heuristic(current, goal);
                if (remaining < closestDistance) {
                    closestDistance = remaining;
                    closest = current;
                }
                if (reached(current, goal)) {
                    searchNanos += System.nanoTime() - sliceStarted;
                    beginBuild(current, true);
                    return advanceBuild(deadlineNanos);
                }
                neighbors(level, mover, current, neighborBuffer);
                double endpointFloor = Math.min(start.position.y, goal.position.y) - 0.55D;
                for (Cell neighbor : neighborBuffer) {
                    if (closed.contains(neighbor.key)) continue;
                    double next = cost.get(current.key)
                            + movementCost(level, mover, current, neighbor, endpointFloor);
                    if (next + 1.0E-6D >= cost.getOrDefault(neighbor.key, Double.POSITIVE_INFINITY)) continue;
                    cost.put(neighbor.key, next);
                    parent.put(neighbor.key, current.key);
                    cells.put(neighbor.key, neighbor);
                    open.add(new SearchNode(neighbor, next + heuristic(neighbor, goal)));
                }
            }
            searchNanos += System.nanoTime() - sliceStarted;
            beginBuild(closest, false);
            return advanceBuild(deadlineNanos);
        }

        private boolean initialize() {
            initialized = true;
            if (level == null || mover == null || startPosition == null || destination == null
                    || !finite(startPosition) || !finite(destination)) {
                finished = true;
                return false;
            }
            start = nearestCell(level, mover, startPosition, 1, 2);
            goal = nearestCell(level, mover, destination, 2, 5);
            if (start == null || goal == null) {
                finished = true;
                return false;
            }
            cost.put(start.key, 0.0D);
            cells.put(start.key, start);
            open.add(new SearchNode(start, heuristic(start, goal)));
            closest = start;
            closestDistance = heuristic(start, goal);
            terrainEpoch = TacticalTerrainRevisions.epoch(level);
            return true;
        }

        private void cancelForTerrainChange() {
            open.clear();
            cost.clear();
            parent.clear();
            cells.clear();
            closed.clear();
            neighborBuffer.clear();
            routeBuild = null;
            result = Route.EMPTY;
            finished = true;
        }

        private void beginBuild(Cell end, boolean complete) {
            if (finished || routeBuild != null) return;
            if (end == null || start == null) {
                finished = true;
                return;
            }
            routeBuild = new RouteBuildSession(level, mover, end, start, parent, cells, complete);
            open.clear();
            cost.clear();
            closed.clear();
        }

        private boolean advanceBuild(long deadlineNanos) {
            if (routeBuild == null) return finished;
            if (!routeBuild.advance(deadlineNanos)) return false;
            Route route = routeBuild.result();
            result = route.points().size() > 1 || route.complete() ? route : Route.EMPTY;
            routeBuild = null;
            parent.clear();
            cells.clear();
            neighborBuffer.clear();
            finished = true;
            return true;
        }

        public boolean finished() { return finished; }
        public Route result() { return finished ? result : Route.EMPTY; }
        public int visited() { return visited; }
    }

    public record Route(List<Vec3> points, boolean complete, boolean constrained,
                        Map<Long, Long> terrainRevisions) {
        public static final Route EMPTY = new Route(List.of(), false, false, Map.of());

        public boolean isValid(ServerLevel level) {
            return TacticalTerrainRevisions.valid(level, terrainRevisions);
        }
    }
}
