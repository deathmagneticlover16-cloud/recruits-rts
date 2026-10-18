package ee.lastpagans.recruitsrtscamera.formation;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.persistence.RTSFormationSavedData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Single server-side entry point for formation orders.
 *
 * 0.7 moves formation memory out of process-local maps and into SavedData. Custom,
 * frontage and preset state therefore survive reconnects and complete world restarts.
 */
public final class FormationService {
    private FormationService() {
    }

    public static FormationKind preferred(ServerLevel level, UUID groupId) {
        RTSFormationSavedData.GroupState state = state(level, groupId);
        if (state != null) {
            return state.preferred();
        }
        FormationKind external = FormationKind.fromExternalName(FormationRuntime.currentShape(groupId));
        return external == null ? FormationKind.MARCH : external;
    }

    public static FormationKind activeKind(ServerLevel level, UUID groupId) {
        RTSFormationSavedData.GroupState state = state(level, groupId);
        if (state != null) {
            return state.mode() == FormationMode.CUSTOM ? state.preferred() : state.active();
        }
        FormationKind external = FormationKind.fromExternalName(FormationRuntime.currentShape(groupId));
        return external == null ? FormationKind.MARCH : external;
    }

    public static boolean movePreserved(
            ServerPlayer sender,
            UUID groupId,
            List<AbstractRecruitEntity> recruits,
            Vec3 destination
    ) {
        if (sender == null) {
            return false;
        }
        ServerLevel level = sender.serverLevel();
        if (CustomFormationRegistry.has(level, groupId)) {
            return CustomFormationExecutor.moveRemembered(sender, groupId, recruits, destination);
        }

        RTSFormationSavedData.GroupState memory = state(level, groupId);
        FormationKind kind = memory != null ? memory.active() : activeKind(level, groupId);
        double spacing = memory != null ? memory.spacing() : 1.0D;
        int ranks = memory != null ? memory.ranks() : 0;
        float facingYaw = memory != null && Float.isFinite(memory.facingYaw())
                ? memory.facingYaw()
                : averageFormationYaw(recruits, sender.getYRot());
        FormationPlan plan = FormationPlanner.movePreserved(
                groupId, destination, kind, facingYaw, spacing, ranks
        );
        return FormationRuntime.executePlan(sender, recruits, plan);
    }

    public static boolean move(
            ServerPlayer sender,
            UUID groupId,
            List<AbstractRecruitEntity> recruits,
            Vec3 destination
    ) {
        return movePreserved(sender, groupId, recruits, destination);
    }

    public static boolean frontage(
            ServerPlayer sender,
            UUID groupId,
            List<AbstractRecruitEntity> recruits,
            Vec3 destination,
            double width,
            float facingYaw,
            int ranks,
            double spacing
    ) {
        if (sender == null || groupId == null) {
            return false;
        }
        ServerLevel level = sender.serverLevel();
        FormationKind preferred = preferred(level, groupId);
        RTSFormationSavedData.get(level).put(groupId, new RTSFormationSavedData.GroupState(
                FormationMode.FRONTAGE,
                preferred,
                FormationKind.LINE,
                spacing,
                ranks,
                facingYaw,
                Map.of(),
                1
        ));
        FormationPlan plan = FormationPlanner.frontage(
                groupId, destination, width, facingYaw, ranks, spacing
        );
        return FormationRuntime.executePlan(sender, recruits, plan);
    }

    public static boolean rotatePreserved(
            ServerPlayer sender,
            UUID groupId,
            List<AbstractRecruitEntity> recruits,
            Vec3 destination,
            double deltaDegrees
    ) {
        if (sender == null || groupId == null || recruits == null || recruits.isEmpty() || destination == null) {
            return false;
        }
        ServerLevel level = sender.serverLevel();
        if (CustomFormationRegistry.has(level, groupId)) {
            return CustomFormationExecutor.rotateRemembered(sender, groupId, recruits, destination, deltaDegrees);
        }

        RTSFormationSavedData.GroupState memory = state(level, groupId);
        FormationKind kind = memory != null ? memory.active() : activeKind(level, groupId);
        double spacing = memory != null ? memory.spacing() : 1.0D;
        int ranks = memory != null ? memory.ranks() : 0;
        FormationKind preferred = memory != null ? memory.preferred() : preferred(level, groupId);
        FormationMode mode = memory != null ? memory.mode() : FormationMode.PRESET;
        float oldYaw = memory != null && Float.isFinite(memory.facingYaw())
                ? memory.facingYaw()
                : averageFormationYaw(recruits, sender.getYRot());
        float newYaw = FormationRotationMath.rotateYaw(oldYaw, deltaDegrees);
        RTSFormationSavedData.get(level).put(groupId, new RTSFormationSavedData.GroupState(
                mode, preferred, kind, spacing, ranks, newYaw, Map.of(), 1
        ));
        FormationPlan plan = FormationPlanner.movePreserved(
                groupId, destination, kind, newYaw, spacing, ranks
        );
        return FormationRuntime.executePlan(sender, recruits, plan);
    }

    public static float currentFacing(
            ServerLevel level,
            UUID groupId,
            List<AbstractRecruitEntity> recruits,
            float fallback
    ) {
        RTSFormationSavedData.GroupState memory = state(level, groupId);
        if (memory != null && Float.isFinite(memory.facingYaw())) {
            return memory.facingYaw();
        }
        return FormationRotationMath.normalizeYaw(averageFormationYaw(recruits, fallback));
    }

    /** Persist a facing update even when a group is being moved by an optional non-runtime controller. */
    public static void rememberFacing(ServerLevel level, UUID groupId, float facingYaw) {
        if (level == null || groupId == null || !Float.isFinite(facingYaw)) {
            return;
        }
        RTSFormationSavedData data = RTSFormationSavedData.get(level);
        RTSFormationSavedData.GroupState old = data.get(groupId);
        if (old == null) {
            data.put(groupId, RTSFormationSavedData.GroupState.preset(
                    FormationKind.MARCH, FormationRotationMath.normalizeYaw(facingYaw)
            ));
        } else {
            data.put(groupId, old.withFacing(facingYaw));
        }
    }

    public static boolean deployPreset(
            ServerPlayer sender,
            UUID groupId,
            List<AbstractRecruitEntity> recruits,
            Vec3 destination,
            float facingYaw
    ) {
        if (sender == null || groupId == null || recruits == null || recruits.isEmpty() || destination == null) {
            return false;
        }
        ServerLevel level = sender.serverLevel();
        float desiredYaw = FormationRotationMath.normalizeYaw(facingYaw);
        float oldYaw = currentFacing(level, groupId, recruits, sender.getYRot());
        double delta = FormationRotationMath.normalizeYaw(desiredYaw - oldYaw);

        if (CustomFormationRegistry.has(level, groupId)) {
            return CustomFormationExecutor.rotateRemembered(sender, groupId, recruits, destination, delta);
        }

        RTSFormationSavedData.GroupState memory = state(level, groupId);
        FormationKind kind = memory != null ? memory.active() : activeKind(level, groupId);
        FormationKind preferred = memory != null ? memory.preferred() : preferred(level, groupId);
        FormationMode mode = memory != null ? memory.mode() : FormationMode.PRESET;
        double spacing = memory != null ? memory.spacing() : 1.0D;
        int ranks = memory != null ? memory.ranks() : 0;
        RTSFormationSavedData.get(level).put(groupId, new RTSFormationSavedData.GroupState(
                mode, preferred, kind, spacing, ranks, desiredYaw, Map.of(), 1
        ));
        FormationPlan plan = FormationPlanner.movePreserved(
                groupId, destination, kind, desiredYaw, spacing, ranks
        );
        return FormationRuntime.executePlan(sender, recruits, plan);
    }

    public static CustomFormationExecutor.Result custom(
            ServerPlayer sender,
            Map<UUID, List<AbstractRecruitEntity>> groups,
            List<Vec3> worldStroke,
            int ranks
    ) {
        return CustomFormationExecutor.deploy(sender, groups, worldStroke, ranks);
    }

    public static boolean setAndReform(
            ServerPlayer sender,
            UUID groupId,
            List<AbstractRecruitEntity> recruits,
            FormationKind kind
    ) {
        if (sender == null || groupId == null || recruits == null || recruits.isEmpty() || kind == null) {
            return false;
        }
        ServerLevel level = sender.serverLevel();
        RTSFormationSavedData.GroupState old = state(level, groupId);
        double spacing = old == null ? 1.0D : old.spacing();
        float facingYaw = old != null && Float.isFinite(old.facingYaw())
                ? old.facingYaw()
                : averageFormationYaw(recruits, sender.getYRot());
        RTSFormationSavedData.get(level).put(groupId, new RTSFormationSavedData.GroupState(
                FormationMode.PRESET, kind, kind, spacing, 0, facingYaw, Map.of(), 1
        ));
        Vec3 destination = averageCenter(recruits);
        FormationPlan plan = FormationPlanner.movePreserved(groupId, destination, kind, facingYaw, spacing, 0);
        return FormationRuntime.executePlan(sender, recruits, plan);
    }

    /**
     * Toggle preset spacing without changing the selected formation. Custom freehand formations
     * keep their authored slots and deliberately ignore this toggle.
     */
    public static boolean setSpacingAndReform(
            ServerPlayer sender,
            UUID groupId,
            List<AbstractRecruitEntity> recruits,
            double spacing
    ) {
        if (sender == null || groupId == null || recruits == null || recruits.isEmpty()) {
            return false;
        }
        ServerLevel level = sender.serverLevel();
        if (CustomFormationRegistry.has(level, groupId)) {
            return false;
        }
        RTSFormationSavedData.GroupState old = state(level, groupId);
        FormationKind kind = old != null ? old.active() : activeKind(level, groupId);
        FormationKind preferred = old != null ? old.preferred() : kind;
        FormationMode mode = old != null ? old.mode() : FormationMode.PRESET;
        int ranks = old != null ? old.ranks() : 0;
        float facingYaw = old != null && Float.isFinite(old.facingYaw())
                ? old.facingYaw()
                : averageFormationYaw(recruits, sender.getYRot());
        double safeSpacing = Math.max(0.65D, Math.min(2.25D, spacing));
        RTSFormationSavedData.get(level).put(groupId, new RTSFormationSavedData.GroupState(
                mode, preferred, kind, safeSpacing, ranks, facingYaw, Map.of(), 1
        ));
        FormationPlan plan = FormationPlanner.movePreserved(
                groupId, averageCenter(recruits), kind, facingYaw, safeSpacing, ranks
        );
        return FormationRuntime.executePlan(sender, recruits, plan);
    }

    private static Vec3 averageCenter(List<AbstractRecruitEntity> recruits) {
        double x = 0.0D, y = 0.0D, z = 0.0D;
        int count = 0;
        for (AbstractRecruitEntity recruit : recruits) {
            if (recruit == null || !recruit.isAlive()) continue;
            x += recruit.getX();
            y += recruit.getY();
            z += recruit.getZ();
            count++;
        }
        return count <= 0 ? Vec3.ZERO : new Vec3(x / count, y / count, z / count);
    }

    public static void remember(ServerLevel level, UUID groupId, FormationKind kind) {
        if (level == null || groupId == null || kind == null) {
            return;
        }
        RTSFormationSavedData data = RTSFormationSavedData.get(level);
        RTSFormationSavedData.GroupState old = data.get(groupId);
        if (old == null) {
            data.put(groupId, RTSFormationSavedData.GroupState.preset(kind, Float.NaN));
        } else {
            data.put(groupId, old.withPreferred(kind));
        }
    }

    public static void clearCustom(ServerLevel level, UUID groupId) {
        CustomFormationRegistry.clear(level, groupId);
    }

    public static void markCustom(ServerLevel level, UUID groupId) {
        // CustomFormationRegistry.put already persists mode=CUSTOM. This method is kept as
        // an explicit semantic hook for callers and future migration logic.
    }

    public static boolean hasCustom(ServerLevel level, UUID groupId) {
        return CustomFormationRegistry.has(level, groupId);
    }

    public static void releaseCustomForAttack(ServerPlayer sender, UUID groupId, List<AbstractRecruitEntity> recruits) {
        if (sender != null && hasCustom(sender.serverLevel(), groupId)) {
            CustomFormationExecutor.releaseForAttack(groupId, recruits);
        }
    }

    public static RTSFormationSavedData.GroupState state(ServerLevel level, UUID groupId) {
        return level == null || groupId == null ? null : RTSFormationSavedData.get(level).get(groupId);
    }

    private static float averageFormationYaw(List<AbstractRecruitEntity> recruits, float fallback) {
        if (recruits == null || recruits.isEmpty()) {
            return fallback;
        }
        double sin = 0.0D;
        double cos = 0.0D;
        int count = 0;
        for (AbstractRecruitEntity recruit : recruits) {
            float yaw = recruit.isInFormation ? recruit.ownerRot : recruit.getYRot();
            if (!Float.isFinite(yaw)) {
                continue;
            }
            double radians = Math.toRadians(yaw);
            sin += Math.sin(radians);
            cos += Math.cos(radians);
            count++;
        }
        if (count == 0 || Math.abs(sin) + Math.abs(cos) < 1.0E-7D) {
            return fallback;
        }
        return (float) Math.toDegrees(Math.atan2(sin, cos));
    }
}
