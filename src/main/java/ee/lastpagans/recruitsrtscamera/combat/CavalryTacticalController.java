package ee.lastpagans.recruitsrtscamera.combat;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.util.AttackUtil;
import com.talhanation.recruits.util.FormationUtils;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.battle.RoutedGroupManager;
import ee.lastpagans.recruitsrtscamera.animation.CommanderAnimationHints;
import ee.lastpagans.recruitsrtscamera.mixin.MobGoalAccess;
import ee.lastpagans.recruitsrtscamera.navigation.TacticalPathfinder;
import ee.lastpagans.recruitsrtscamera.movement.RTSMountControlRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.*;

/** Committed cavalry manoeuvres above vanilla terrain navigation; no teleport or velocity steering. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID)
public final class CavalryTacticalController {
    private static final Map<UUID, State> STATES = new HashMap<>();
    private CavalryTacticalController() {}
    public static boolean eligible(AbstractRecruitEntity rider) {
        return rider != null && rider.level() instanceof ServerLevel && rider.isAlive() && !rider.isRemoved()
                && rider.getVehicle() instanceof AbstractHorse horse && horse.isAlive() && !horse.isRemoved()
                && !TacticalCombatManager.ranged(rider) && !RoutedGroupManager.isRouting(rider)
                && !rider.getShouldMount();
    }
    public static boolean isActive(AbstractRecruitEntity rider) {
        State s = rider == null ? null : STATES.get(rider.getUUID());
        return s != null && s.active;
    }
    public static void activate(AbstractRecruitEntity rider, LivingEntity target, UUID group) {
        if (!eligible(rider) || !valid(rider, target)) { deactivate(rider); return; }
        State s = STATES.get(rider.getUUID());
        if (s != null && s.mount != rider.getVehicle()) { deactivate(rider); s = null; }
        if (s == null) {
            s = new State(rider, (AbstractHorse) rider.getVehicle());
            STATES.put(rider.getUUID(), s);
            ((MobGoalAccess) rider).rts$getGoals().addGoal(-5, s.goal);
            ((MobGoalAccess) s.mount).rts$getGoals().addGoal(-5, s.mountGoal);
        }
        s.target = target; s.group = group; s.refreshed = rider.level().getGameTime();
        // Replacement defender changes the combat lock, never an already committed lane.
        if (!s.active) { s.active = true; preparePass(s); }
        rider.setFollowState(3); rider.setShouldFollow(false); rider.setIsFollowing(false);
        rider.reachedMovePos = false; rider.setShouldMovePos(false); rider.setShouldHoldPos(false);
        rider.setTarget(target);
    }
    public static void deactivate(AbstractRecruitEntity rider) {
        if (rider == null) return;
        State s = STATES.remove(rider.getUUID());
        if (s == null) return;
        s.active = false;
        ((MobGoalAccess) rider).rts$getGoals().removeGoal(s.goal);
        ((MobGoalAccess) s.mount).rts$getGoals().removeGoal(s.mountGoal);
        RTSMountControlRegistry.release(s.mount);
        s.mount.getNavigation().stop();
        CommanderAnimationHints.clearMovement(rider);
        if (!rider.isRemoved() && !RoutedGroupManager.isRouting(rider)) {
            rider.setFollowState(0); rider.setTarget(null);
        }
    }
    public static void cancelGroup(UUID group) {
        for (State s : List.copyOf(STATES.values())) if (group.equals(s.group)) deactivate(s.rider);
    }
    public static void cancelAll() { for (State s : List.copyOf(STATES.values())) deactivate(s.rider); }
    @SubscribeEvent public static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        for (State s : List.copyOf(STATES.values()))
            if (!eligible(s.rider) || s.mount != s.rider.getVehicle() || s.rider.level().getGameTime() - s.refreshed > 30)
                deactivate(s.rider);
    }
    @SubscribeEvent public static void onLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof AbstractRecruitEntity rider) deactivate(rider);
    }
    @SubscribeEvent public static void onStop(ServerStoppedEvent event) { cancelAll(); }
    private static boolean valid(AbstractRecruitEntity rider, LivingEntity target) {
        return target != null && target.isAlive() && !target.isRemoved() && target.level() == rider.level()
                && !rider.isAlliedTo(target) && rider.canAttack(target);
    }
    private static Vec3 flat(Vec3 v) { return v.multiply(1, 0, 1); }
    private static CavalryManeuver.Point point(Vec3 v) { return new CavalryManeuver.Point(v.x, v.z); }
    private static Vec3 targetPosition(State s) {
        return s.target.getVehicle() != null ? s.target.getVehicle().position() : s.target.position();
    }
    private static void preparePass(State s) {
        s.maneuver = new CavalryManeuver(point(s.mount.position()), point(targetPosition(s)),
                (s.group.getLeastSignificantBits() & 1) == 0 ? 1 : -1);
        s.lastNavigationGoal = null;
        s.navigationAccepted = false;
        s.lastNavigationAt = Long.MIN_VALUE;
        s.lastPosition = s.mount.position();
        s.directPassGoal = null;
        s.directPassSafe = false;
        s.directPassControl = false;
        s.appliedHeading = s.mount.getYRot();
        s.hasAppliedHeading = true;
    }
    private static void tick(State s) {
        if (!eligible(s.rider)) return;
        s.rider.setFollowState(3); s.rider.reachedMovePos = false; s.mount.setTarget(null);
        Vec3 liveTarget = valid(s.rider, s.target) ? targetPosition(s) : null;
        if (liveTarget == null && s.maneuver.phase() == CavalryManeuver.Phase.REFORM) {
            s.mount.getNavigation().stop();
            return;
        }
        CavalryManeuver.Point destination = s.maneuver.tick(point(s.mount.position()),
                liveTarget == null ? null : point(liveTarget));
        // A combat pass is one continuous gallop, including staging and the hairpin.
        CommanderAnimationHints.updateMovement(s.rider, true, true);
        if (s.maneuver.phase() == CavalryManeuver.Phase.CHARGE
                || s.maneuver.phase() == CavalryManeuver.Phase.PASS) attack(s);
        if (++s.samples % 20 == 0) {
            if (flat(s.mount.position().subtract(s.lastPosition)).lengthSqr() < 0.25) s.stallTicks += 20;
            else s.stallTicks = 0;
            s.lastPosition = s.mount.position();
            if (s.stallTicks >= 80) {
                // Change the maneuver lane without issuing a zero-speed stop. Keeping
                // the current horizontal motion avoids a visible animation reset.
                s.maneuver.blocked(point(s.mount.position()));
                s.stallTicks = 0;
                s.lastNavigationGoal = null;
                s.navigationAccepted = false;
                s.lastNavigationAt = Long.MIN_VALUE;
            }
        }
        // Surface resolution uses local altitude so bridges/hills remain navigation's responsibility.
        BlockPos ground = FormationUtils.getPositionOrSurface((ServerLevel) s.rider.level(),
                BlockPos.containing(destination.x(), s.mount.getY(), destination.z()));
        Vec3 navigationGoal = new Vec3(destination.x(), ground.getY(), destination.z());
        long now = s.rider.level().getGameTime();
        boolean committedPass = s.maneuver.phase() == CavalryManeuver.Phase.CHARGE
                || s.maneuver.phase() == CavalryManeuver.Phase.PASS;
        boolean directPass = committedPass && directPassSafe(s, navigationGoal);
        if (directPass) {
            if (!s.directPassControl) {
                // Navigation can declare an occupied enemy frontage to be the end of its
                // path. Hand a terrain-checked pass to MoveControl and preserve the current
                // impulse so entering direct control cannot create a stopped animation tick.
                Vec3 momentum = s.mount.getDeltaMovement();
                s.mount.getNavigation().stop();
                s.mount.setDeltaMovement(momentum);
                s.directPassControl = true;
            }
            s.mount.getMoveControl().setWantedPosition(
                    navigationGoal.x, navigationGoal.y, navigationGoal.z, 1.15D);
            s.lastNavigationGoal = navigationGoal;
            s.navigationAccepted = false;
            s.lastNavigationAt = now;
        } else {
            s.directPassControl = false;
        }
        if (!directPass && (s.lastNavigationGoal == null
                || s.lastNavigationGoal.distanceToSqr(navigationGoal) > 2.5D * 2.5D
                || s.navigationAccepted && s.mount.getNavigation().isDone()
                || !s.navigationAccepted && now - s.lastNavigationAt >= 5L)) {
            boolean accepted = s.mount.getNavigation().moveTo(destination.x(), ground.getY(), destination.z(),
                    s.maneuver.phase() == CavalryManeuver.Phase.CHARGE ? 1.15 : 1.0);
            s.lastNavigationGoal = navigationGoal;
            s.navigationAccepted = accepted;
            s.lastNavigationAt = now;
        }
        matchRiderAndMountFacing(s, destination);
    }

    private static boolean directPassSafe(State state, Vec3 destination) {
        if (state.directPassGoal == null
                || state.directPassGoal.distanceToSqr(destination) > 1.0D) {
            state.directPassGoal = destination;
            state.directPassSafe = TacticalPathfinder.canSteerDirect(
                    (ServerLevel) state.rider.level(), state.mount, destination);
        }
        return state.directPassSafe;
    }

    private static void matchRiderAndMountFacing(State state, CavalryManeuver.Point destination) {
        double dx = destination.x() - state.mount.getX();
        double dz = destination.z() - state.mount.getZ();
        if (dx * dx + dz * dz < 1.0E-5D) return;
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        if (!state.hasAppliedHeading) {
            state.appliedHeading = state.mount.getYRot();
            state.hasAppliedHeading = true;
        }
        float yaw = approachHeading(state.appliedHeading, desiredYaw, 4.5F);
        state.appliedHeading = yaw;
        state.rider.ownerRot = yaw;
        state.rider.setYRot(yaw);
        state.rider.setYBodyRot(yaw);
        state.rider.setYHeadRot(yaw);
        state.mount.setYRot(yaw);
        state.mount.setYBodyRot(yaw);
        state.mount.setYHeadRot(yaw);
    }
    private static float approachHeading(float current, float target, float maximumStep) {
        float difference = (target - current) % 360.0F;
        if (difference >= 180.0F) difference -= 360.0F;
        if (difference < -180.0F) difference += 360.0F;
        if (difference > maximumStep) difference = maximumStep;
        else if (difference < -maximumStep) difference = -maximumStep;
        return current + difference;
    }
    private static void attack(State s) {
        if (valid(s.rider, s.target)) {
            double reach = AttackUtil.getAttackReachSqr(s.rider), distance = s.rider.distanceToSqr(s.target);
            if (distance <= Math.max(reach, 12.25)) AttackUtil.checkAndPerformAttack(distance, reach, s.rider, s.target);
        }
    }
    private static final class State {
        final AbstractRecruitEntity rider;
        final AbstractHorse mount;
        final CavalryGoal goal;
        final MountControlGoal mountGoal;
        LivingEntity target;
        UUID group;
        CavalryManeuver maneuver;
        Vec3 lastPosition;
        Vec3 lastNavigationGoal;
        Vec3 directPassGoal;
        int samples, stallTicks;
        long lastNavigationAt = Long.MIN_VALUE;
        boolean navigationAccepted;
        boolean directPassSafe;
        boolean directPassControl;
        float appliedHeading;
        boolean hasAppliedHeading;
        long refreshed;
        boolean active;
        State(AbstractRecruitEntity rider, AbstractHorse mount) {
            this.rider = rider; this.mount = mount; this.goal = new CavalryGoal(this);
            this.mountGoal = new MountControlGoal(this);
            RTSMountControlRegistry.claim(mount);
        }
    }
    private static final class MountControlGoal extends Goal {
        final State state;
        MountControlGoal(State state) {
            this.state = state;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP, Flag.TARGET));
        }
        @Override public boolean canUse() { return state.active && eligible(state.rider) && state.rider.getVehicle() == state.mount; }
        @Override public boolean canContinueToUse() { return canUse(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { state.mount.setTarget(null); }
        @Override public void stop() { state.mount.getNavigation().stop(); }
    }
    private static final class CavalryGoal extends Goal {
        final State state;
        CavalryGoal(State state) { this.state = state; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return state.active && eligible(state.rider) && state.rider.getVehicle() == state.mount; }
        @Override public boolean canContinueToUse() { return canUse(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { CavalryTacticalController.tick(state); }
        @Override public void stop() { state.mount.getNavigation().stop(); }
    }
}
