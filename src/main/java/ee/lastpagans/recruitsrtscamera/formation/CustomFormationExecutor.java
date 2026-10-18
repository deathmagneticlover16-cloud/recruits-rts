package ee.lastpagans.recruitsrtscamera.formation;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.util.FormationUtils;
import com.talhanation.recruits.util.RecruitCommanderUtil;
import ee.lastpagans.recruitsrtscamera.compat.BetterFormationsCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server-side executor for Alt+RMB custom/freehand formations. */
public final class CustomFormationExecutor {
    private CustomFormationExecutor() {
    }

    /**
     * Divide one drawn stroke into contiguous group sections and assign every recruit a
     * deterministic hold slot. Better Formations is deliberately released here because
     * arbitrary curves are outside its preset shape model.
     */
    public static Result deploy(
            ServerPlayer sender,
            Map<UUID, List<AbstractRecruitEntity>> groups,
            List<Vec3> worldStroke,
            int requestedRanks
    ) {
        if (sender == null || groups == null || groups.isEmpty() || worldStroke == null || worldStroke.size() < 2) {
            return Result.EMPTY;
        }

        List<CustomFormationMath.Point> path = new ArrayList<>(worldStroke.size());
        double averageY = 0.0D;
        for (Vec3 point : worldStroke) {
            path.add(new CustomFormationMath.Point(point.x, point.z));
            averageY += point.y;
        }
        final List<CustomFormationMath.Point> smoothedPath = CustomFormationMath.smooth(path, 1);
        double length = CustomFormationMath.length(smoothedPath);
        if (smoothedPath.size() < 2 || length < CustomFormationMath.MIN_STROKE_LENGTH) {
            return Result.EMPTY;
        }
        averageY /= worldStroke.size();

        // Preserve the army's current left-to-right progression using the overall
        // direction of the requested stroke. This works even when the new formation is
        // far away from the troops (where nearest-point sorting would collapse groups
        // onto the same endpoint and appear arbitrary).
        CustomFormationMath.Point firstPath = smoothedPath.get(0);
        CustomFormationMath.Point lastPath = smoothedPath.get(smoothedPath.size() - 1);
        double orderX = lastPath.x() - firstPath.x();
        double orderZ = lastPath.z() - firstPath.z();
        double orderLength = Math.hypot(orderX, orderZ);
        if (orderLength < 0.25D) {
            CustomFormationMath.Sample mid = CustomFormationMath.sample(smoothedPath, length * 0.5D);
            orderX = mid.tangentX();
            orderZ = mid.tangentZ();
            orderLength = 1.0D;
        }
        final double sortX = orderX / orderLength;
        final double sortZ = orderZ / orderLength;

        List<UUID> orderedIds = new ArrayList<>(groups.keySet());
        orderedIds.sort(Comparator
                .comparingDouble((UUID groupId) -> projection(groupCenter2D(groups.get(groupId)), sortX, sortZ))
                .thenComparing(UUID::toString));

        List<Integer> counts = new ArrayList<>(orderedIds.size());
        for (UUID groupId : orderedIds) {
            counts.add(groups.get(groupId).size());
        }
        List<CustomFormationMath.PathRange> ranges = CustomFormationMath.splitByCounts(counts, length);
        if (ranges.size() != orderedIds.size()) {
            return Result.EMPTY;
        }

        int ranks = Math.max(1, Math.min(CustomFormationMath.MAX_RANKS, requestedRanks));
        int appliedGroups = 0;
        int appliedRecruits = 0;
        ServerLevel level = sender.serverLevel();

        for (int groupIndex = 0; groupIndex < orderedIds.size(); groupIndex++) {
            UUID groupId = orderedIds.get(groupIndex);
            List<AbstractRecruitEntity> recruits = groups.get(groupId);
            if (recruits == null || recruits.isEmpty()) {
                continue;
            }

            List<CustomFormationMath.Slot> slots = CustomFormationMath.slotsForRange(
                    smoothedPath,
                    ranges.get(groupIndex),
                    recruits.size(),
                    ranks,
                    CustomFormationMath.DEFAULT_RANK_SPACING
            );
            if (slots.size() != recruits.size()) {
                continue;
            }

            // Preserve a deterministic left-to-right recruit order along the new formation's
            // overall axis. UUID is only a tie-breaker. We deliberately never perform
            // nearest-slot assignment: each recruit index receives exactly one ordered slot.
            List<AbstractRecruitEntity> orderedRecruits = new ArrayList<>(recruits);
            orderedRecruits.sort(Comparator
                    .comparingDouble((AbstractRecruitEntity recruit) -> recruit.getX() * sortX + recruit.getZ() * sortZ)
                    .thenComparing(recruit -> recruit.getUUID().toString()));

            BetterFormationsCompat.releaseForAttack(sender, groupId);
            RecruitCommanderUtil.setRecruitsClearTargets(orderedRecruits);

            List<PlacedSlot> placed = new ArrayList<>(slots.size());
            for (int i = 0; i < slots.size(); i++) {
                CustomFormationMath.Slot slot = slots.get(i);
                BlockPos surface = FormationUtils.getPositionOrSurface(
                        level,
                        BlockPos.containing(slot.x(), averageY, slot.z())
                );
                Vec3 position = new Vec3(slot.x(), surface.getY(), slot.z());
                placed.add(new PlacedSlot(orderedRecruits.get(i), position, slot.facingYaw()));
            }

            double centerX = placed.stream().mapToDouble(value -> value.position().x).average().orElse(0.0D);
            double centerZ = placed.stream().mapToDouble(value -> value.position().z).average().orElse(0.0D);
            Map<UUID, CustomFormationRegistry.RelativeSlot> memory = new LinkedHashMap<>();

            for (PlacedSlot placedSlot : placed) {
                AbstractRecruitEntity recruit = placedSlot.recruit();
                applyHoldSlot(recruit, placedSlot.position(), placedSlot.facingYaw());
                memory.put(
                        recruit.getUUID(),
                        new CustomFormationRegistry.RelativeSlot(
                                placedSlot.position().x - centerX,
                                placedSlot.position().z - centerZ,
                                placedSlot.facingYaw()
                        )
                );
                appliedRecruits++;
            }
            CustomFormationRegistry.put(level, groupId, new CustomFormationRegistry.GroupState(memory, ranks));
            FormationService.markCustom(level, groupId);
            appliedGroups++;
        }

        return new Result(appliedGroups, appliedRecruits, length, ranks);
    }

    /** Translate a remembered custom shape to a new group centre. */
    public static boolean moveRemembered(
            ServerPlayer sender,
            UUID groupId,
            List<AbstractRecruitEntity> recruits,
            Vec3 newGroupCenter
    ) {
        CustomFormationRegistry.GroupState state = CustomFormationRegistry.get(sender.serverLevel(), groupId);
        if (sender == null || state == null || recruits == null || recruits.isEmpty() || newGroupCenter == null) {
            return false;
        }

        // Casualties are fine: surviving recruits keep their remembered slots. A newly
        // added recruit has no deterministic slot in the old shape, so abandon that stale
        // custom memory instead of moving only part of the group and leaving a straggler.
        for (AbstractRecruitEntity recruit : recruits) {
            if (!state.slots().containsKey(recruit.getUUID())) {
                CustomFormationRegistry.clear(sender.serverLevel(), groupId);
                return false;
            }
        }

        BetterFormationsCompat.releaseForAttack(sender, groupId);
        RecruitCommanderUtil.setRecruitsClearTargets(recruits);
        int matched = 0;
        for (AbstractRecruitEntity recruit : recruits) {
            CustomFormationRegistry.RelativeSlot relative = state.slots().get(recruit.getUUID());
            double x = newGroupCenter.x + relative.dx();
            double z = newGroupCenter.z + relative.dz();
            BlockPos surface = FormationUtils.getPositionOrSurface(
                    sender.serverLevel(),
                    BlockPos.containing(x, newGroupCenter.y, z)
            );
            applyHoldSlot(recruit, new Vec3(x, surface.getY(), z), relative.facingYaw());
            matched++;
        }
        return matched > 0;
    }

    /** Rotate a remembered freehand shape and translate its centre in one deterministic step. */
    public static boolean rotateRemembered(
            ServerPlayer sender,
            UUID groupId,
            List<AbstractRecruitEntity> recruits,
            Vec3 newGroupCenter,
            double deltaDegrees
    ) {
        CustomFormationRegistry.GroupState state = CustomFormationRegistry.get(sender.serverLevel(), groupId);
        if (sender == null || state == null || recruits == null || recruits.isEmpty() || newGroupCenter == null) {
            return false;
        }
        for (AbstractRecruitEntity recruit : recruits) {
            if (!state.slots().containsKey(recruit.getUUID())) {
                CustomFormationRegistry.clear(sender.serverLevel(), groupId);
                return false;
            }
        }

        BetterFormationsCompat.releaseForAttack(sender, groupId);
        RecruitCommanderUtil.setRecruitsClearTargets(recruits);
        Map<UUID, CustomFormationRegistry.RelativeSlot> rotatedMemory = new LinkedHashMap<>();
        int matched = 0;
        for (AbstractRecruitEntity recruit : recruits) {
            CustomFormationRegistry.RelativeSlot relative = state.slots().get(recruit.getUUID());
            FormationRotationMath.Point rotated = FormationRotationMath.rotateOffset(
                    relative.dx(), relative.dz(), deltaDegrees
            );
            double x = newGroupCenter.x + rotated.x();
            double z = newGroupCenter.z + rotated.z();
            float yaw = FormationRotationMath.rotateYaw(relative.facingYaw(), deltaDegrees);
            BlockPos surface = FormationUtils.getPositionOrSurface(
                    sender.serverLevel(),
                    BlockPos.containing(x, newGroupCenter.y, z)
            );
            applyHoldSlot(recruit, new Vec3(x, surface.getY(), z), yaw);
            rotatedMemory.put(
                    recruit.getUUID(),
                    new CustomFormationRegistry.RelativeSlot(rotated.x(), rotated.z(), yaw)
            );
            matched++;
        }
        if (matched > 0) {
            CustomFormationRegistry.put(sender.serverLevel(), groupId, new CustomFormationRegistry.GroupState(rotatedMemory, state.ranks()));
        }
        return matched > 0;
    }

    /** Let custom-formation recruits fight while retaining the shape memory for the next move. */
    public static void releaseForAttack(UUID groupId, List<AbstractRecruitEntity> recruits) {
        if (recruits == null || recruits.isEmpty()) {
            return;
        }
        for (AbstractRecruitEntity recruit : recruits) {
            recruit.setShouldHoldPos(false);
            recruit.clearHoldPos();
            recruit.holdFormation = false;
            recruit.isInFormation = false;
        }
    }

    private static void applyHoldSlot(AbstractRecruitEntity recruit, Vec3 position, float facingYaw) {
        recruit.setTarget(null);
        recruit.setShouldMovePos(false);
        recruit.clearMovePos();
        recruit.setHoldPos(position);
        recruit.setShouldHoldPos(true);
        recruit.setFollowState(3);
        recruit.ownerRot = facingYaw;
        recruit.isInFormation = true;
        recruit.holdFormation = true;
    }

    private static double projection(CustomFormationMath.Point point, double axisX, double axisZ) {
        return point.x() * axisX + point.z() * axisZ;
    }

    private static CustomFormationMath.Point groupCenter2D(List<AbstractRecruitEntity> recruits) {
        if (recruits == null || recruits.isEmpty()) {
            return new CustomFormationMath.Point(0.0D, 0.0D);
        }
        double x = 0.0D;
        double z = 0.0D;
        for (AbstractRecruitEntity recruit : recruits) {
            x += recruit.getX();
            z += recruit.getZ();
        }
        return new CustomFormationMath.Point(x / recruits.size(), z / recruits.size());
    }

    private record PlacedSlot(AbstractRecruitEntity recruit, Vec3 position, float facingYaw) {
    }

    public record Result(int groups, int recruits, double strokeLength, int ranks) {
        public static final Result EMPTY = new Result(0, 0, 0.0D, 1);
    }
}
