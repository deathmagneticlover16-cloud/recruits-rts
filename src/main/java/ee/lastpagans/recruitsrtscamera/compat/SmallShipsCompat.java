package ee.lastpagans.recruitsrtscamera.compat;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.CaptainEntity;
import com.talhanation.recruits.util.RecruitCommanderUtil;
import ee.lastpagans.recruitsrtscamera.formation.FormationKind;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Optional Small Ships integration through Recruits' own Captain/SmallShips bridge.
 *
 * No classes from the Small Ships mod are referenced directly here. If Small Ships is
 * absent, all methods return immediately and RTS Camera behaves as a land-only addon.
 */
public final class SmallShipsCompat {
    public static final String MOD_ID = "smallships";
    public static final double DEFAULT_SPACING = 9.0D;
    public static final double MIN_SPACING = 6.0D;
    public static final double MAX_SPACING = 18.0D;
    private static final double SHIP_CLEARANCE = 2.25D;
    private static final int WATER_SLOT_SEARCH_RADIUS = 6;

    private SmallShipsCompat() {
    }

    public static boolean isAvailable() {
        return ModList.get().isLoaded(MOD_ID) && Main.isSmallShipsLoaded && Main.isSmallShipsCompatible;
    }

    public static boolean isCaptainAboard(CaptainEntity captain) {
        return isAvailable()
                && captain != null
                && captain.smallShipsController != null
                && captain.smallShipsController.ship != null
                && captain.smallShipsController.ship.getBoat() != null;
    }

    public static boolean isShipRecruit(AbstractRecruitEntity recruit) {
        return recruit instanceof CaptainEntity captain && isCaptainAboard(captain);
    }

    public static List<CaptainEntity> attachedCaptains(List<AbstractRecruitEntity> recruits) {
        if (!isAvailable() || recruits == null || recruits.isEmpty()) {
            return List.of();
        }
        List<CaptainEntity> result = new ArrayList<>();
        for (AbstractRecruitEntity recruit : recruits) {
            if (recruit instanceof CaptainEntity captain && isCaptainAboard(captain)) {
                result.add(captain);
            }
        }
        return List.copyOf(result);
    }

    public static List<AbstractRecruitEntity> withoutAttachedShips(List<AbstractRecruitEntity> recruits) {
        if (!isAvailable() || recruits == null || recruits.isEmpty()) {
            return recruits == null ? List.of() : List.copyOf(recruits);
        }
        List<AbstractRecruitEntity> result = new ArrayList<>(recruits.size());
        for (AbstractRecruitEntity recruit : recruits) {
            if (!(recruit instanceof CaptainEntity captain) || !isCaptainAboard(captain)) {
                result.add(recruit);
            }
        }
        return List.copyOf(result);
    }

    public static double currentFleetWidth(List<AbstractRecruitEntity> recruits, float facingYaw) {
        List<CaptainEntity> captains = attachedCaptains(recruits);
        if (captains.size() <= 1) {
            return 0.0D;
        }
        List<Vec3> positions = new ArrayList<>(captains.size());
        for (CaptainEntity captain : captains) {
            positions.add(shipPosition(captain));
        }
        positions.sort(Comparator.comparingDouble(pos -> projection(pos, facingYaw)));
        double total = 0.0D;
        for (int i = 1; i < positions.size(); i++) {
            total += Math.abs(projection(positions.get(i), facingYaw) - projection(positions.get(i - 1), facingYaw));
        }
        double natural = recommendedSpacing(captains);
        double observed = total / Math.max(1, positions.size() - 1);
        // Preserve a sensible existing fleet width, but do not perpetuate the enormous
        // 0.8.1-era 18-32 block spacing after one move order.
        double spacing = clamp(observed, Math.max(MIN_SPACING, natural * 0.85D), Math.min(MAX_SPACING, natural * 1.45D));
        return spacing * (captains.size() - 1.0D);
    }

    /**
     * Give each Captain its own water destination. Recruits then converts the generic
     * move order into Captain#setSailPos and its native SmallShipsController does the
     * actual steering/sailing. RTS Camera only owns fleet geometry.
     */
    public static FleetOrder commandFleetLine(
            ServerPlayer sender,
            List<AbstractRecruitEntity> recruits,
            Vec3 center,
            float facingYaw,
            Double requestedWidth
    ) {
        return commandFleetFormation(sender, recruits, center, facingYaw, FormationKind.LINE, requestedWidth);
    }

    /**
     * Naval counterpart to infantry presets. It only calculates ship destinations;
     * Recruits' native Captain/SmallShipsController remains responsible for sailing.
     * A frontage width always forces a line-abreast deployment.
     */
    public static FleetOrder commandFleetFormation(
            ServerPlayer sender,
            List<AbstractRecruitEntity> recruits,
            Vec3 center,
            float facingYaw,
            FormationKind kind,
            Double requestedWidth
    ) {
        if (!isAvailable() || sender == null || recruits == null || center == null) {
            return FleetOrder.EMPTY;
        }
        List<CaptainEntity> captains = new ArrayList<>(attachedCaptains(recruits));
        if (captains.isEmpty()) {
            return FleetOrder.EMPTY;
        }
        FormationKind safeKind = kind == null ? FormationKind.LINE : kind;
        boolean explicitFrontage = requestedWidth != null && Double.isFinite(requestedWidth);

        double naturalSpacing = recommendedSpacing(captains);
        double spacing;
        if (explicitFrontage && captains.size() > 1) {
            spacing = clamp(
                    Math.abs(requestedWidth) / (captains.size() - 1.0D),
                    naturalSpacing,
                    MAX_SPACING
            );
            safeKind = FormationKind.LINE;
        } else if (captains.size() > 1) {
            double width = currentFleetWidth(recruits, facingYaw);
            spacing = width > 0.0D
                    ? clamp(width / (captains.size() - 1.0D), Math.max(MIN_SPACING, naturalSpacing * 0.85D), Math.min(MAX_SPACING, naturalSpacing * 1.45D))
                    : naturalSpacing;
        } else {
            spacing = naturalSpacing;
        }

        // Stable ordering prevents ships from needlessly crossing through one another.
        captains.sort(Comparator
                .comparingDouble((CaptainEntity captain) -> projection(shipPosition(captain), facingYaw))
                .thenComparing(captain -> captain.getUUID().toString()));

        List<Vec3> slots = fleetSlots(captains.size(), center, facingYaw, spacing, safeKind);
        if (slots.size() != captains.size()) {
            return FleetOrder.EMPTY;
        }
        List<BlockPos> waterSlots = resolveWaterSlots(sender, slots, center.y, spacing);

        // Near coasts or islands a wide inherited layout may put one ideal slot on land.
        // Retry once at the vessel-aware natural spacing rather than issuing a partial
        // order that visibly tears the formation apart.
        if (waterSlots.size() != captains.size() && !explicitFrontage && Math.abs(spacing - naturalSpacing) > 0.25D) {
            spacing = naturalSpacing;
            slots = fleetSlots(captains.size(), center, facingYaw, spacing, safeKind);
            waterSlots = resolveWaterSlots(sender, slots, center.y, spacing);
        }
        if (waterSlots.size() != captains.size()) {
            return FleetOrder.EMPTY;
        }

        for (int i = 0; i < captains.size(); i++) {
            CaptainEntity captain = captains.get(i);
            BlockPos water = waterSlots.get(i);
            captain.setShouldStrategicFire(false);
            captain.setTarget(null);
            RecruitCommanderUtil.setRecruitsMove(List.of(captain), water);
        }

        return new FleetOrder(captains.size(), spacing, fleetExtent(slots, facingYaw));
    }

    public static Vec3 fleetCenter(List<AbstractRecruitEntity> recruits) {
        List<CaptainEntity> captains = attachedCaptains(recruits);
        if (captains.isEmpty()) {
            return Vec3.ZERO;
        }
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        for (CaptainEntity captain : captains) {
            Vec3 pos = shipPosition(captain);
            x += pos.x;
            y += pos.y;
            z += pos.z;
        }
        double inv = 1.0D / captains.size();
        return new Vec3(x * inv, y * inv, z * inv);
    }

    private static List<Vec3> fleetSlots(int count, Vec3 center, float facingYaw, double spacing, FormationKind kind) {
        if (count <= 0) {
            return List.of();
        }
        double yaw = Math.toRadians(facingYaw);
        double rightX = Math.cos(yaw);
        double rightZ = Math.sin(yaw);
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        List<Vec3> raw = new ArrayList<>(count);

        switch (kind) {
            case MARCH -> {
                for (int i = 0; i < count; i++) {
                    double back = (i - (count - 1) * 0.5D) * spacing;
                    raw.add(new Vec3(center.x - forwardX * back, center.y, center.z - forwardZ * back));
                }
            }
            case TRIANGLE, VEE -> {
                raw.add(center);
                int placed = 1;
                for (int rank = 1; placed < count; rank++) {
                    double back = rank * spacing * 0.9D;
                    double side = rank * spacing;
                    raw.add(new Vec3(center.x - forwardX * back - rightX * side, center.y,
                            center.z - forwardZ * back - rightZ * side));
                    placed++;
                    if (placed < count) {
                        raw.add(new Vec3(center.x - forwardX * back + rightX * side, center.y,
                                center.z - forwardZ * back + rightZ * side));
                        placed++;
                    }
                }
                recenter(raw, center);
            }
            case SQUARE, HOLLOW_SQUARE -> {
                int rows = Math.min(2, count);
                int columns = (int) Math.ceil(count / (double) rows);
                int placed = 0;
                for (int row = 0; row < rows && placed < count; row++) {
                    for (int col = 0; col < columns && placed < count; col++) {
                        double right = (col - (columns - 1) * 0.5D) * spacing;
                        double back = (row - (rows - 1) * 0.5D) * spacing * 1.15D;
                        raw.add(new Vec3(center.x + rightX * right - forwardX * back, center.y,
                                center.z + rightZ * right - forwardZ * back));
                        placed++;
                    }
                }
            }
            case RING, CIRCLE, BUBBLE -> {
                double radius = Math.max(spacing, count * spacing / (Math.PI * 2.0D));
                for (int i = 0; i < count; i++) {
                    double a = Math.PI * 2.0D * i / count;
                    double right = Math.cos(a) * radius;
                    double forward = Math.sin(a) * radius;
                    raw.add(new Vec3(center.x + rightX * right + forwardX * forward, center.y,
                            center.z + rightZ * right + forwardZ * forward));
                }
            }
            default -> {
                double half = (count - 1) * spacing * 0.5D;
                for (int i = 0; i < count; i++) {
                    double offset = i * spacing - half;
                    raw.add(new Vec3(center.x + rightX * offset, center.y, center.z + rightZ * offset));
                }
            }
        }
        return List.copyOf(raw);
    }

    private static void recenter(List<Vec3> points, Vec3 desiredCenter) {
        if (points.isEmpty()) return;
        double x = 0.0D;
        double z = 0.0D;
        for (Vec3 point : points) {
            x += point.x;
            z += point.z;
        }
        x /= points.size();
        z /= points.size();
        double dx = desiredCenter.x - x;
        double dz = desiredCenter.z - z;
        for (int i = 0; i < points.size(); i++) {
            Vec3 point = points.get(i);
            points.set(i, new Vec3(point.x + dx, point.y, point.z + dz));
        }
    }

    private static double fleetExtent(List<Vec3> slots, float facingYaw) {
        if (slots.size() <= 1) return 0.0D;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (Vec3 slot : slots) {
            double p = projection(slot, facingYaw);
            min = Math.min(min, p);
            max = Math.max(max, p);
        }
        return Math.max(0.0D, max - min);
    }

    public static void haltCaptain(CaptainEntity captain) {
        if (!isCaptainAboard(captain)) {
            return;
        }
        Vec3 pos = shipPosition(captain);
        captain.clearMovePos();
        captain.setShouldMovePos(false);
        captain.setHoldPos(pos);
        captain.setFollowState(2);
        captain.setSailPos(java.util.Optional.empty());
        captain.getNavigation().stop();
    }

    public static Vec3 shipPosition(CaptainEntity captain) {
        if (isCaptainAboard(captain)) {
            Boat boat = captain.smallShipsController.ship.getBoat();
            if (boat != null) {
                return boat.position();
            }
        }
        return captain == null ? Vec3.ZERO : captain.position();
    }

    private static double recommendedSpacing(List<CaptainEntity> captains) {
        double largestFootprint = 0.0D;
        for (CaptainEntity captain : captains) {
            if (!isCaptainAboard(captain)) {
                continue;
            }
            Boat boat = captain.smallShipsController.ship.getBoat();
            if (boat == null) {
                continue;
            }
            largestFootprint = Math.max(
                    largestFootprint,
                    Math.max(boat.getBoundingBox().getXsize(), boat.getBoundingBox().getZsize())
            );
        }
        double vesselAware = largestFootprint > 0.0D
                ? largestFootprint * 1.15D + SHIP_CLEARANCE
                : DEFAULT_SPACING;
        return clamp(Math.max(DEFAULT_SPACING, vesselAware), MIN_SPACING, MAX_SPACING);
    }

    private static List<BlockPos> resolveWaterSlots(
            ServerPlayer sender,
            List<Vec3> slots,
            double fallbackY,
            double spacing
    ) {
        List<BlockPos> resolved = new ArrayList<>(slots.size());
        double minimumSeparation = Math.max(3.0D, spacing * 0.55D);
        double minimumSeparationSq = minimumSeparation * minimumSeparation;
        for (Vec3 slot : slots) {
            BlockPos water = findWaterSurfaceNear(
                    sender,
                    (int) Math.floor(slot.x),
                    (int) Math.floor(slot.z),
                    (int) Math.floor(fallbackY),
                    resolved,
                    minimumSeparationSq
            );
            if (water == null || !sender.serverLevel().hasChunkAt(water)) {
                return List.of();
            }
            resolved.add(water);
        }
        return List.copyOf(resolved);
    }

    private static BlockPos findWaterSurfaceNear(
            ServerPlayer sender,
            int x,
            int z,
            int fallbackY,
            List<BlockPos> occupied,
            double minimumSeparationSq
    ) {
        BlockPos best = null;
        double bestDistanceSq = Double.POSITIVE_INFINITY;
        for (int dz = -WATER_SLOT_SEARCH_RADIUS; dz <= WATER_SLOT_SEARCH_RADIUS; dz++) {
            for (int dx = -WATER_SLOT_SEARCH_RADIUS; dx <= WATER_SLOT_SEARCH_RADIUS; dx++) {
                double horizontalSq = dx * (double) dx + dz * (double) dz;
                if (horizontalSq > WATER_SLOT_SEARCH_RADIUS * (double) WATER_SLOT_SEARCH_RADIUS) {
                    continue;
                }
                BlockPos candidate = waterSurfaceAt(sender, x + dx, z + dz, fallbackY);
                if (candidate == null) {
                    continue;
                }
                boolean overlaps = false;
                for (BlockPos used : occupied) {
                    double sx = candidate.getX() - used.getX();
                    double sz = candidate.getZ() - used.getZ();
                    if (sx * sx + sz * sz < minimumSeparationSq) {
                        overlaps = true;
                        break;
                    }
                }
                if (!overlaps && horizontalSq < bestDistanceSq) {
                    best = candidate;
                    bestDistanceSq = horizontalSq;
                }
            }
        }
        return best;
    }

    private static BlockPos waterSurfaceAt(ServerPlayer sender, int x, int z, int fallbackY) {
        int y = sender.serverLevel().getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        BlockPos pos = new BlockPos(x, y, z);
        if (sender.serverLevel().getFluidState(pos).is(FluidTags.WATER)) {
            return pos;
        }
        for (int d = 1; d <= 8; d++) {
            BlockPos down = new BlockPos(x, fallbackY - d, z);
            if (sender.serverLevel().getFluidState(down).is(FluidTags.WATER)) {
                return down;
            }
            BlockPos up = new BlockPos(x, fallbackY + d, z);
            if (sender.serverLevel().getFluidState(up).is(FluidTags.WATER)) {
                return up;
            }
        }
        return null;
    }

    private static double projection(Vec3 pos, float facingYaw) {
        double yaw = Math.toRadians(facingYaw);
        return pos.x * Math.cos(yaw) + pos.z * Math.sin(yaw);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public record FleetOrder(int ships, double spacing, double width) {
        public static final FleetOrder EMPTY = new FleetOrder(0, 0.0D, 0.0D);

        public boolean movedAny() {
            return ships > 0;
        }
    }
}
