package ee.lastpagans.recruitsrtscamera.formation;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/** Stable entry point for the built-in formation executor and its runtime state. */
public final class FormationRuntime {
    private FormationRuntime() {
    }

    public static String currentShape(UUID groupId) {
        return NativeFormationController.currentShape(groupId);
    }

    public static boolean executePlan(ServerPlayer sender, List<AbstractRecruitEntity> recruits, FormationPlan plan) {
        return NativeFormationController.execute(sender, recruits, plan);
    }

    public static Vec3 formationCenter(UUID groupId) {
        return NativeFormationController.center(groupId);
    }

    public static boolean setFormationCenter(UUID groupId, Vec3 center) {
        return NativeFormationController.setCenter(groupId, center);
    }

    public static boolean translateFormation(UUID groupId, Vec3 delta) {
        return NativeFormationController.translate(groupId, delta);
    }

    /** Retarget an active formation without rebuilding slots or resetting member controllers. */
    public static boolean retargetFormation(UUID groupId, Vec3 destination) {
        return NativeFormationController.retarget(groupId, destination);
    }

    /** Explicit combat orders replace an older route without rebuilding formation slots. */
    public static boolean retargetFormationImmediately(UUID groupId, Vec3 destination) {
        return NativeFormationController.retargetImmediately(groupId, destination);
    }

    public static Vec3 advanceFormationCenter(UUID groupId, double extraStep) {
        return NativeFormationController.advance(groupId, extraStep);
    }

    public static Vec3 formationDestination(UUID groupId) {
        return NativeFormationController.destination(groupId);
    }

    public static int formationMemberCount(UUID groupId) {
        return NativeFormationController.memberCount(groupId);
    }

    public static int formationLaggards(UUID groupId) {
        return NativeFormationController.laggards(groupId);
    }

    public static boolean isFormationMemberLocked(UUID groupId, UUID recruitId) {
        return NativeFormationController.locked(groupId, recruitId);
    }

    public static void repairGroundSlots(ServerLevel level, UUID groupId, List<AbstractRecruitEntity> recruits) {
        NativeFormationController.refreshGroundSlots(groupId);
    }

    public static void preferMoraleBearerSlots(UUID groupId, List<AbstractRecruitEntity> recruits) {
        NativeFormationController.preferMoraleBearerSlots(groupId, recruits);
    }

    public static Float formationHeading(UUID groupId) {
        return NativeFormationController.heading(groupId);
    }

    public static boolean faceFormation(UUID groupId, float heading) {
        return NativeFormationController.face(groupId, heading);
    }

    public static Vec3 commanderSlot(ServerLevel level, ServerPlayer player, UUID groupId) {
        return NativeFormationController.commanderSlot(level, player, groupId);
    }

    public static void clearCommanderReservation(UUID groupId) {
        NativeFormationController.clearCommanderReservation(groupId);
    }

    public static void haltFormation(UUID groupId) {
        NativeFormationController.halt(groupId);
    }

    public static void releaseForAttack(ServerPlayer sender, UUID groupId) {
        NativeFormationController.release(sender, groupId);
    }
}
