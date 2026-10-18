package ee.lastpagans.recruitsrtscamera.combat;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.util.FormationUtils;
import ee.lastpagans.recruitsrtscamera.mixin.MobGoalAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Owns movement only while changing range. Native bow/crossbow goals fire in the safe band. */
public final class SkirmishController {
    private static final Map<UUID, SkirmishGoal> GOALS = new HashMap<>();
    private SkirmishController() {}
    public static void attach(AbstractRecruitEntity recruit) {
        if (GOALS.containsKey(recruit.getUUID())) return;
        var goal = new SkirmishGoal(recruit);
        GOALS.put(recruit.getUUID(), goal);
        ((MobGoalAccess) recruit).rts$getGoals().addGoal(-5, goal);
    }
    public static void remove(AbstractRecruitEntity recruit) {
        var goal = GOALS.remove(recruit.getUUID());
        if (goal != null) ((MobGoalAccess) recruit).rts$getGoals().removeGoal(goal);
    }
    public static void clear() {
        for (var goal : List.copyOf(GOALS.values())) remove(goal.recruit);
    }
    private static final class SkirmishGoal extends Goal {
        final AbstractRecruitEntity recruit;
        LivingEntity threat;
        Vec3 destination;
        Vec3 escapeDirection;
        boolean retreat, advance;
        int repath;
        Mob controlledMount;
        Goal mountGuard;
        long nextDecision;
        SkirmishGoal(AbstractRecruitEntity recruit) {
            this.recruit = recruit;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }
        private Mob mover() { return recruit.getVehicle() instanceof Mob mob ? mob : recruit; }
        private boolean enabled() {
            return TacticalCombatManager.ownsTarget(recruit) && TacticalCombatManager.ranged(recruit)
                    && TacticalCombatManager.settings(recruit).skirmish()
                    && TacticalCombatManager.assignedTarget(recruit) != null
                    && !recruit.getShouldMovePos() && !recruit.getShouldHoldPos();
        }
        private void decide() {
            long now = recruit.level().getGameTime();
            if (now < nextDecision) return;
            nextDecision = now + 10;
            LivingEntity assigned = TacticalCombatManager.assignedTarget(recruit);
            threat = assigned;
            // Search only the local danger radius, twice a second per engaged skirmisher.
            double closest = assigned == null ? Double.MAX_VALUE : mover().distanceToSqr(assigned);
            for (LivingEntity enemy : recruit.level().getEntitiesOfClass(LivingEntity.class,
                    mover().getBoundingBox().inflate(14, 5, 14),
                    e -> e.isAlive() && recruit.canAttack(e) && !recruit.isAlliedTo(e)
                            && (e instanceof Mob mob && mob.getTarget() != null || e == assigned))) {
                if (enemy == recruit || enemy == recruit.getVehicle()) continue;
                double distance = mover().distanceToSqr(enemy);
                if (distance < closest) { threat = enemy; closest = distance; }
            }
            if (threat == null) { retreat = advance = false; return; }
            boolean mounted = mover() != recruit;
            boolean wasRetreat = retreat;
            retreat = TacticalMath.retreat(retreat, Math.sqrt(closest), mounted);
            advance = !retreat && assigned != null && TacticalMath.advance(advance,
                    Math.sqrt(mover().distanceToSqr(assigned)), mounted);
            if (retreat && !wasRetreat) {
                Vec3 delta = mover().position().subtract(threat.position()).multiply(1, 0, 1);
                escapeDirection = delta.lengthSqr() < 0.01 ? new Vec3(0, 0, 1) : delta.normalize();
                destination = null;
            }
            if (!retreat) escapeDirection = null;
        }
        @Override public boolean canUse() {
            if (!enabled()) return false;
            decide();
            return retreat || advance;
        }
        @Override public boolean canContinueToUse() {
            return (controlledMount == null || mover() == controlledMount) && canUse();
        }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void start() {
            repath = 10;
            recruit.stopUsingItem();
            if (mover() != recruit) {
                controlledMount = mover();
                mountGuard = new Goal() {
                    { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
                    @Override public boolean canUse() { return enabled() && (retreat || advance) && mover() == controlledMount; }
                    @Override public boolean canContinueToUse() { return canUse(); }
                };
                ((MobGoalAccess) controlledMount).rts$getGoals().addGoal(-5, mountGuard);
            }
        }
        @Override public void tick() {
            decide();
            Mob mover = mover();
            LivingEntity assigned = TacticalCombatManager.assignedTarget(recruit);
            if (assigned == null) return;
            if (retreat) {
                if (destination == null || mover.position().distanceToSqr(destination) < 4)
                    destination = mover.position().add(escapeDirection.scale(12));
            } else if (advance) {
                Vec3 away = mover.position().subtract(assigned.position()).multiply(1, 0, 1).normalize();
                destination = assigned.position().add(away.scale(mover == recruit ? 21 : 25));
            }
            if (destination == null || ++repath < 10) return;
            repath = 0;
            BlockPos surface = FormationUtils.getPositionOrSurface((ServerLevel) recruit.level(), BlockPos.containing(destination));
            // Repath is bounded even when the native navigator cannot find a route.
            mover.getNavigation().moveTo(destination.x, surface.getY(), destination.z, 1.0);
            mover.getLookControl().setLookAt(destination.x, surface.getY(), destination.z);
        }
        @Override public void stop() {
            if (controlledMount != null && mountGuard != null) {
                ((MobGoalAccess) controlledMount).rts$getGoals().removeGoal(mountGuard);
                controlledMount.getNavigation().stop();
            }
            controlledMount = null; mountGuard = null;
            mover().getNavigation().stop();
            retreat = advance = false;
            destination = escapeDirection = null;
            nextDecision = 0;
        }
    }
}
