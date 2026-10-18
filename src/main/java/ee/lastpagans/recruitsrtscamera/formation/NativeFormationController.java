package ee.lastpagans.recruitsrtscamera.formation;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.config.RTSServerConfig;
import ee.lastpagans.recruitsrtscamera.compat.TerrainSurfaceResolver;
import ee.lastpagans.recruitsrtscamera.navigation.TacticalPathfinder;
import ee.lastpagans.recruitsrtscamera.navigation.LocalFormationSteering;
import ee.lastpagans.recruitsrtscamera.navigation.TacticalTerrainRevisions;
import ee.lastpagans.recruitsrtscamera.registry.RTSTags;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitAIScheduler;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitRuntimeRegistry;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitRuntimeMetrics;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitRuntimeBrain;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Built-in server-authoritative formation runtime.
 *
 * <p>The controller owns slot assignment and execution. A formation follows one shared tactical
 * route on open ground. At ladders, stairs, and narrow passages, front-center pairs peel into
 * a distance-spaced snake while later ranks retain their approach slots. Members recover their
 * geometric slots after the anchor reaches its destination.</p>
 */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class NativeFormationController {
    private static final Map<UUID, State> STATES = new LinkedHashMap<>();
    private static final int MAX_FORMATION_SEARCH = 18_000;
    private static final int MAX_AGENT_SEARCH = 3_500;
    private static final double CENTER_SPEED = 0.20D;
    private static final double SLOT_LOCK_DISTANCE_SQ = 0.30D * 0.30D;
    private static final int QUEUE_BATCH_SIZE = 2;

    private NativeFormationController() {
    }

    public static boolean execute(ServerPlayer sender, List<AbstractRecruitEntity> source, FormationPlan plan) {
        if (!RTSServerConfig.NATIVE_FORMATION_RUNTIME.get() || !RTSServerConfig.RUNTIME_BRAIN.get()
                || !RTSServerConfig.TACTICAL_NAVIGATION.get()) return false;
        if (sender == null || source == null || source.isEmpty() || plan == null || plan.groupId() == null) return false;
        List<AbstractRecruitEntity> units = source.stream()
                .filter(unit -> unit != null && unit.isAlive() && !unit.isRemoved())
                .sorted(Comparator.comparing(unit -> unit.getUUID().toString()))
                .toList();
        if (units.isEmpty()) return false;

        FormationIntent intent = plan.intent();
        boolean mounted = units.stream().anyMatch(unit -> unit.getVehicle() != null);
        List<FormationSlotMath.Offset> offsets = FormationSlotMath.create(
                intent.kind(), units.size(), intent.spacing(), intent.ranks(), intent.frontageWidth(), mounted
        );
        float heading = Float.isFinite(intent.facingYaw()) ? intent.facingYaw() : sender.getYRot();
        Vec3 start = averageControlledPosition(units, plan.destination().y);
        List<FormationSlotMath.Point> unitPoints = units.stream()
                .map(unit -> new FormationSlotMath.Point(controlled(unit).getX(), controlled(unit).getZ()))
                .toList();
        List<FormationSlotMath.Point> slotPoints = worldSlotPoints(plan.destination(), heading, offsets);
        int[] assignment = FormationSlotMath.assign(unitPoints, slotPoints);
        Map<UUID, FormationSlotMath.Offset> assigned = new LinkedHashMap<>();
        for (int i = 0; i < units.size() && i < assignment.length; i++) {
            assigned.put(units.get(i).getUUID(), offsets.get(assignment[i]));
        }

        removeOverlapping(sender.getServer(), plan.groupId(), assigned.keySet());
        State old = STATES.get(plan.groupId());
        if (old != null) releaseMembers(sender.getServer(), old, false);
        State state = new State(plan.groupId(), sender.getUUID(), sender.serverLevel().dimension(), intent.kind(), heading, start,
                plan.destination(), assigned, Map.of(), intent.hold());
        STATES.put(plan.groupId(), state);
        for (AbstractRecruitEntity unit : units) {
            claim(unit, heading);
            FormationSlotMath.Offset offset = assigned.get(unit.getUUID());
            if (offset != null) RecruitRuntimeRegistry.claimFormation(unit, worldSlot(start, heading, offset), Vec3.ZERO);
        }
        return true;
    }

    /** Execute arbitrary absolute slots, including freehand formations, through the same navigator. */
    public static boolean executeSlots(
            ServerPlayer sender,
            UUID groupId,
            List<AbstractRecruitEntity> source,
            Map<UUID, Vec3> absoluteSlots,
            Map<UUID, Float> facings
    ) {
        if (!RTSServerConfig.NATIVE_FORMATION_RUNTIME.get() || !RTSServerConfig.RUNTIME_BRAIN.get()
                || !RTSServerConfig.TACTICAL_NAVIGATION.get()) return false;
        if (sender == null || groupId == null || source == null || source.isEmpty()
                || absoluteSlots == null || absoluteSlots.isEmpty()) return false;
        List<AbstractRecruitEntity> units = source.stream()
                .filter(unit -> unit != null && unit.isAlive() && absoluteSlots.containsKey(unit.getUUID()))
                .sorted(Comparator.comparing(unit -> unit.getUUID().toString()))
                .toList();
        if (units.isEmpty()) return false;
        Vec3 destination = average(absoluteSlots.values());
        Vec3 start = averageControlledPosition(units, destination.y);
        Map<UUID, FormationSlotMath.Offset> offsets = new LinkedHashMap<>();
        for (AbstractRecruitEntity unit : units) {
            Vec3 slot = absoluteSlots.get(unit.getUUID());
            offsets.put(unit.getUUID(), new FormationSlotMath.Offset(
                    slot.x - destination.x,
                    destination.z - slot.z
            ));
        }
        removeOverlapping(sender.getServer(), groupId, offsets.keySet());
        State old = STATES.get(groupId);
        if (old != null) releaseMembers(sender.getServer(), old, false);
        State state = new State(groupId, sender.getUUID(), sender.serverLevel().dimension(), FormationKind.MARCH, 0.0F, start,
                destination, offsets, facings == null ? Map.of() : facings, true);
        STATES.put(groupId, state);
        for (AbstractRecruitEntity unit : units) {
            claim(unit, state.facings.getOrDefault(unit.getUUID(), 0.0F));
            FormationSlotMath.Offset offset = offsets.get(unit.getUUID());
            if (offset != null) RecruitRuntimeRegistry.claimFormation(unit, worldSlot(start, 0.0F, offset), Vec3.ZERO);
        }
        return true;
    }

    public static String currentShape(UUID groupId) {
        State state = STATES.get(groupId);
        return state == null ? null : state.kind.name();
    }

    public static Vec3 center(UUID groupId) {
        State state = STATES.get(groupId);
        return state == null ? null : state.center;
    }

    public static Vec3 destination(UUID groupId) {
        State state = STATES.get(groupId);
        return state == null ? null : state.destination;
    }

    public static Float heading(UUID groupId) {
        State state = STATES.get(groupId);
        return state == null ? null : state.heading;
    }

    public static int memberCount(UUID groupId) {
        State state = STATES.get(groupId);
        return state == null ? -1 : state.offsets.size();
    }

    public static int laggards(UUID groupId) {
        State state = STATES.get(groupId);
        return state == null ? -1 : state.laggards;
    }

    public static boolean locked(UUID groupId, UUID recruitId) {
        State state = STATES.get(groupId);
        return state != null && state.locked.contains(recruitId);
    }

    public static boolean setCenter(UUID groupId, Vec3 center) {
        State state = STATES.get(groupId);
        if (state == null || center == null) return false;
        state.center = center;
        rememberTrail(state, center);
        return true;
    }

    public static boolean translate(UUID groupId, Vec3 delta) {
        State state = STATES.get(groupId);
        if (state == null || delta == null) return false;
        state.center = state.center.add(delta);
        state.destination = state.destination.add(delta);
        invalidateRoutes(state);
        return true;
    }

    public static Vec3 advance(UUID groupId, double step) {
        State state = STATES.get(groupId);
        if (state == null) return null;
        if (!state.route.points().isEmpty()) advanceAlongRoute(state, Math.max(0.0D, step));
        return state.center;
    }

    public static void halt(UUID groupId) {
        State state = STATES.get(groupId);
        if (state == null) return;
        state.destination = state.center;
        state.route = TacticalPathfinder.Route.EMPTY;
        state.routeIndex = 0;
        state.dispersed = false;
        state.releasedToQueue = 0;
        state.stagingCenter = null;
        state.agents.values().forEach(Agent::clearPath);
    }

    public static void release(ServerPlayer sender, UUID groupId) {
        release(sender == null ? null : sender.getServer(), groupId, true);
    }

    static void release(MinecraftServer server, UUID groupId, boolean stop) {
        State state = STATES.remove(groupId);
        if (state != null) releaseMembers(server, state, stop);
    }

    public static void refreshGroundSlots(UUID groupId) {
        // Slots are resolved from current collision shapes every tick; there is no stale
        // heightmap cache to repair.
    }

    public static void requestRecovery(UUID groupId, UUID recruitId) {
        State state = STATES.get(groupId);
        if (state == null || recruitId == null) return;
        Agent agent = state.agents.get(recruitId);
        if (agent != null) {
            agent.clearPath();
            agent.nextPlanAt = 0L;
        }
        state.locked.remove(recruitId);
    }

    public static void preferMoraleBearerSlots(UUID groupId, List<AbstractRecruitEntity> recruits) {
        State state = STATES.get(groupId);
        if (state == null || recruits == null) return;
        List<AbstractRecruitEntity> bearers = recruits.stream().filter(NativeFormationController::carriesMoraleItem).toList();
        if (bearers.isEmpty()) return;
        List<Map.Entry<UUID, FormationSlotMath.Offset>> preferred = state.offsets.entrySet().stream()
                .sorted(Comparator.comparingDouble(entry -> Math.abs(entry.getValue().right()) + entry.getValue().back() * 0.35D))
                .toList();
        for (int i = 0; i < bearers.size() && i < preferred.size(); i++) {
            UUID bearer = bearers.get(i).getUUID();
            UUID occupant = preferred.get(i).getKey();
            if (bearer.equals(occupant)) continue;
            FormationSlotMath.Offset bearerOffset = state.offsets.get(bearer);
            FormationSlotMath.Offset preferredOffset = state.offsets.get(occupant);
            if (bearerOffset != null && preferredOffset != null) {
                state.offsets.put(bearer, preferredOffset);
                state.offsets.put(occupant, bearerOffset);
            }
        }
    }

    public static Vec3 commanderSlot(ServerLevel level, ServerPlayer player, UUID groupId) {
        State state = STATES.get(groupId);
        if (state == null || player == null) return null;
        double rear = state.offsets.values().stream().mapToDouble(FormationSlotMath.Offset::back).max().orElse(0.0D) + 1.6D;
        double yaw = Math.toRadians(state.heading);
        Vec3 forward = new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw));
        Vec3 desired = state.center.add(forward.scale(-rear));
        return TerrainSurfaceResolver.resolve(level, player, desired);
    }

    public static void clearCommanderReservation(UUID groupId) {
        // Commander slots are derived from live formation bounds and retain no separate state.
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null || STATES.isEmpty()) return;
        if (!RTSServerConfig.NATIVE_FORMATION_RUNTIME.get() || !RTSServerConfig.RUNTIME_BRAIN.get()
                || !RTSServerConfig.TACTICAL_NAVIGATION.get()) {
            for (State state : List.copyOf(STATES.values())) releaseMembers(event.getServer(), state, false);
            STATES.clear();
            return;
        }
        for (State state : List.copyOf(STATES.values())) {
            try {
                tick(event.getServer(), state);
                state.consecutiveFailures = 0;
            } catch (RuntimeException exception) {
                RecruitsRTSCameraMod.LOGGER.error("Native formation {} update failed", state.groupId, exception);
                if (++state.consecutiveFailures >= 3) {
                    STATES.remove(state.groupId);
                    releaseMembers(event.getServer(), state, false);
                    RecruitsRTSCameraMod.LOGGER.error(
                            "Released formation {} to native Recruits control after repeated failures", state.groupId);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        STATES.clear();
    }

    @SubscribeEvent
    public static void onRecruitDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof AbstractRecruitEntity recruit)) return;
        UUID id = recruit.getUUID();
        for (State state : STATES.values()) {
            state.offsets.remove(id);
            state.facings.remove(id);
            state.agents.remove(id);
            state.locked.remove(id);
            state.order.remove(id);
        }
    }

    private static void tick(MinecraftServer server, State state) {
        List<AbstractRecruitEntity> members = resolveMembers(server, state);
        if (members.isEmpty()) {
            if (++state.emptyTicks > 20 * 300) STATES.remove(state.groupId);
            return;
        }
        state.emptyTicks = 0;

        // A normal Recruits move/follow order takes ownership back immediately.  Check
        // before claim() clears the move flag so the formation controller cannot swallow
        // an order issued by another Recruits command path or another compatible mod.
        boolean externalOrder = members.stream().anyMatch(member -> member.getFollowState() != 3
                || member.getShouldMovePos() || member.getShouldFollow());
        if (externalOrder) {
            if (STATES.remove(state.groupId, state)) releaseMembers(server, state, false);
            return;
        }

        ServerLevel level = (ServerLevel) members.get(0).level();
        Mob representative = controlledMob(members.get(0));
        if (representative == null) return;

        if (!state.route.isValid(level)) {
            RecruitRuntimeMetrics.TERRAIN_INVALIDATIONS.increment();
            invalidateRoutes(state);
        }

        if (state.route.points().isEmpty() && (horizontalDistanceSq(state.center, state.destination) > 0.16D
                || Math.abs(state.center.y - state.destination.y) > 0.65D)
                && !state.routePending && level.getGameTime() >= state.nextRouteAt) {
            state.routePending = RecruitAIScheduler.submit(
                    RecruitAIScheduler.Priority.HIGH,
                    RecruitAIScheduler.WorkType.FORMATION_PATH,
                    state.groupId,
                    () -> {
                        try {
                            if (STATES.get(state.groupId) != state || !representative.isAlive()) return;
                            state.route = TacticalPathfinder.find(
                                    level, representative, state.center, state.destination, MAX_FORMATION_SEARCH);
                            RecruitRuntimeMetrics.FORMATION_PATHS.increment();
                            if (state.route.points().isEmpty()) RecruitRuntimeMetrics.PATH_FAILURES.increment();
                            if (state.route.constrained()) RecruitRuntimeMetrics.CONSTRAINED_ROUTES.increment();
                            state.routeIndex = Math.min(1, Math.max(0, state.route.points().size() - 1));
                            state.dispersed = false;
                            state.releasedToQueue = 0;
                            state.stagingCenter = null;
                            state.trail.clear();
                            rememberTrail(state, state.center);
                            state.nextRouteAt = level.getGameTime() + (state.route.points().isEmpty() ? 20L : 5L);
                        } finally {
                            state.routePending = false;
                        }
                    }
            );
        }

        Vec3 effectiveDestination = effectiveDestination(state);
        boolean travelling = horizontalDistanceSq(state.center, effectiveDestination) > 0.16D
                || Math.abs(state.center.y - effectiveDestination.y) > 0.65D;
        if (travelling) beginQueueWhenReady(state, members.size(), level.getGameTime());
        Vec3 previousCenter = state.center;
        // The anchor never waits for one member. Cohesion is recovered by accelerating
        // laggards and giving their personal replans priority, so one failed mover cannot
        // freeze or pulse the entire formation.
        if (travelling && !state.route.points().isEmpty()) {
            advanceAlongRoute(state, CENTER_SPEED);
        } else if (!travelling) {
            state.dispersed = false;
            state.releasedToQueue = 0;
            state.stagingCenter = null;
        }
        state.velocity = state.center.subtract(previousCenter);

        int laggards = 0;
        int queuedNotReady = 0;
        int index = 0;
        for (AbstractRecruitEntity recruit : members) {
            FormationSlotMath.Offset offset = state.offsets.get(recruit.getUUID());
            if (offset == null) continue;
            RecruitRuntimeMetrics.BRAIN_UPDATES.increment();
            float memberHeading = state.facings.getOrDefault(recruit.getUUID(), state.heading);
            claim(recruit, memberHeading);
            Mob mover = controlledMob(recruit);
            if (mover == null) continue;
            int transitIndex = index++;
            boolean released = state.dispersed && travelling && transitIndex < state.releasedToQueue;
            Vec3 desired = released
                    ? trailPosition(state, transitIndex)
                    : worldSlot(state.dispersed && state.stagingCenter != null ? state.stagingCenter : state.center,
                    state.heading, offset);
            Agent agent = state.agents.computeIfAbsent(recruit.getUUID(), Agent::new);
            Vec3 grounded = agent.grounded(level, mover, desired);
            if (grounded != null) desired = grounded;
            double slotError = horizontalDistanceSq(mover.position(), desired);
            double heightError = Math.abs(mover.getY() - desired.y);
            double catchUpScale = slotError > 4.0D * 4.0D || heightError > 1.35D ? 1.55D
                    : slotError > 1.55D * 1.55D || heightError > 0.85D ? 1.30D : 1.0D;
            recruit.setHoldPos(desired);
            RecruitRuntimeRegistry.claimFormation(recruit, desired, state.velocity);
            boolean arrived = drive(level, recruit, mover, desired, agent, released, memberHeading,
                    state.velocity, catchUpScale);
            if (arrived) {
                state.locked.add(recruit.getUUID());
                RecruitRuntimeRegistry.brain(recruit).settle(level.getGameTime());
            }
            else {
                state.locked.remove(recruit.getUUID());
                RecruitRuntimeRegistry.brain(recruit).markMoving(level.getGameTime());
                if (slotError > 1.55D * 1.55D || heightError > 0.85D) laggards++;
            }
            if (released && transitIndex >= Math.max(0, state.releasedToQueue - QUEUE_BATCH_SIZE)
                    && horizontalDistanceSq(mover.position(), desired) > 1.35D * 1.35D) queuedNotReady++;
        }
        state.laggards = laggards;
        releaseNextQueueBatch(state, members.size(), queuedNotReady, level.getGameTime());
    }

    private static boolean drive(
            ServerLevel level,
            AbstractRecruitEntity recruit,
            Mob mover,
            Vec3 desired,
            Agent agent,
            boolean dispersed,
            float finalHeading,
            Vec3 slotVelocity,
            double catchUpScale
    ) {
        double horizontal = horizontalDistanceSq(mover.position(), desired);
        double vertical = Math.abs(mover.getY() - desired.y);
        boolean slotMoving = slotVelocity != null
                && (slotVelocity.x * slotVelocity.x + slotVelocity.z * slotVelocity.z > 0.0025D);
        if (!slotMoving && horizontal <= SLOT_LOCK_DISTANCE_SQ && vertical <= 0.70D) {
            mover.getNavigation().stop();
            Vec3 velocity = mover.getDeltaMovement();
            mover.setDeltaMovement(0.0D, velocity.y, 0.0D);
            agent.clearPath();
            recruit.ownerRot = finalHeading;
            mover.setYRot(finalHeading);
            return true;
        }

        long now = level.getGameTime();
        boolean localCandidate = RTSServerConfig.LOCAL_STEERING.get()
                && horizontal <= 8.0D * 8.0D && vertical <= 2.0D;
        Boolean directSafety = localCandidate ? agent.canSteerDirect(level, mover, desired, now) : Boolean.FALSE;
        boolean fallenBehind = desired.y - mover.getY() > 0.72D;
        boolean direct = Boolean.TRUE.equals(directSafety);
        if (direct && !fallenBehind && !agent.stuck(mover.position(), desired, now)) {
            agent.clearPath();
            LocalFormationSteering.steer(level, recruit, mover, desired,
                    RecruitRuntimeRegistry.brain(recruit).desiredVelocity(), catchUpScale);
            faceMovement(recruit, mover, desired);
            return false;
        }

        boolean goalChanged = agent.goal == null || agent.goal.distanceToSqr(desired) > (dispersed ? 1.0D : 0.75D * 0.75D);
        boolean needsPath = agent.path.points().isEmpty() || agent.index >= agent.path.points().size()
                || goalChanged || !agent.path.isValid(level);
        if (needsPath && directSafety != null && !agent.pathPending && now >= agent.nextPlanAt) {
            Vec3 pathGoal = desired;
            RecruitAIScheduler.Priority priority = catchUpScale > 1.0D
                    ? RecruitAIScheduler.Priority.HIGH : RecruitAIScheduler.Priority.MEDIUM;
            agent.pathPending = RecruitAIScheduler.submit(
                    priority,
                    RecruitAIScheduler.WorkType.AGENT_PATH,
                    recruit.getUUID(),
                    () -> {
                        try {
                            if (!recruit.isAlive() || recruit.isRemoved() || mover.isRemoved()
                                    || RecruitRuntimeRegistry.brain(recruit).authority()
                                    != RecruitRuntimeBrain.MovementAuthority.RTS_FORMATION) return;
                            agent.path = TacticalPathfinder.find(level, mover, pathGoal, MAX_AGENT_SEARCH);
                            RecruitRuntimeMetrics.AGENT_PATHS.increment();
                            RecruitRuntimeMetrics.RECOVERY_PATHS.increment();
                            if (agent.path.points().isEmpty()) RecruitRuntimeMetrics.PATH_FAILURES.increment();
                            agent.index = Math.min(1, Math.max(0, agent.path.points().size() - 1));
                            agent.goal = pathGoal;
                            agent.plannedAt = level.getGameTime();
                            agent.resetProgress(mover.position(), pathGoal, level.getGameTime());
                            agent.nextPlanAt = level.getGameTime() + (agent.path.points().isEmpty() ? 20L : 8L);
                        } finally {
                            agent.pathPending = false;
                        }
                    }
            );
        }

        if (agent.path.points().isEmpty()) {
            // Use Minecraft's navigator while the tactical replan is queued or when a
            // bounded tactical search has no result. The grounded goal is supported, so
            // this fallback keeps the recruit moving without steering toward open air.
            mover.getNavigation().moveTo(desired.x, desired.y, desired.z,
                    (recruit.getVehicle() == null ? 1.05D : 1.0D) * catchUpScale);
            faceMovement(recruit, mover, desired);
            return false;
        }

        Vec3 waypoint = desired;
        if (!agent.path.points().isEmpty() && agent.index < agent.path.points().size()) {
            waypoint = agent.path.points().get(agent.index);
            if (horizontalDistanceSq(mover.position(), waypoint) < 0.42D * 0.42D
                    && Math.abs(mover.getY() - waypoint.y) < 0.75D) {
                agent.index++;
                if (agent.index < agent.path.points().size()) waypoint = agent.path.points().get(agent.index);
                else waypoint = desired;
            }
        }

        BlockPos feet = BlockPos.containing(mover.position());
        BlockPos waypointBlock = BlockPos.containing(waypoint);
        boolean climbing = TacticalPathfinder.isClimbable(level, feet)
                || TacticalPathfinder.isClimbable(level, waypointBlock);
        if (climbing && waypoint.y > mover.getY() + 0.15D) {
            double dx = waypoint.x - mover.getX();
            double dz = waypoint.z - mover.getZ();
            Vec3 velocity = mover.getDeltaMovement();
            mover.getNavigation().stop();
            mover.setDeltaMovement(clamp(dx * 0.22D * catchUpScale, -0.18D, 0.18D),
                    Math.max(0.20D * catchUpScale, velocity.y),
                    clamp(dz * 0.22D * catchUpScale, -0.18D, 0.18D));
        } else {
            if (waypoint.y > mover.getY() + 0.45D
                    && horizontalDistanceSq(mover.position(), waypoint) < 1.45D * 1.45D) {
                mover.getJumpControl().jump();
            }
            mover.getNavigation().stop();
            mover.getMoveControl().setWantedPosition(waypoint.x, waypoint.y, waypoint.z,
                    (recruit.getVehicle() == null ? 1.05D : 1.0D) * catchUpScale);
        }
        faceMovement(recruit, mover, waypoint);
        return false;
    }

    private static void advanceAlongRoute(State state, double step) {
        if (state.route.points().isEmpty()) return;
        while (step > 1.0E-5D && state.routeIndex < state.route.points().size()) {
            Vec3 target = state.route.points().get(state.routeIndex);
            Vec3 delta = target.subtract(state.center);
            double distance = delta.length();
            if (distance <= step) {
                state.center = target;
                step -= distance;
                state.routeIndex++;
            } else {
                state.center = state.center.add(delta.scale(step / distance));
                step = 0.0D;
            }
            rememberTrail(state, state.center);
        }
        if (state.routeIndex >= state.route.points().size() && state.route.complete()) {
            state.center = effectiveDestination(state);
            rememberTrail(state, state.center);
        } else if (state.routeIndex >= state.route.points().size()) {
            state.route = TacticalPathfinder.Route.EMPTY;
            state.routeIndex = 0;
        }
    }

    private static Vec3 trailPosition(State state, int memberIndex) {
        if (state.trail.isEmpty()) return state.center;
        double behind = memberIndex * 0.90D;
        Vec3 middle = sampleTrail(state, behind);
        Vec3 ahead = sampleTrail(state, Math.max(0.0D, behind - 0.32D));
        Vec3 back = sampleTrail(state, behind + 0.32D);
        if (Math.abs(ahead.y - middle.y) < 0.80D && Math.abs(back.y - middle.y) < 0.80D) {
            return ahead.scale(0.20D).add(middle.scale(0.60D)).add(back.scale(0.20D));
        }
        return middle;
    }

    private static Vec3 sampleTrail(State state, double distanceBehind) {
        if (state.trail.isEmpty()) return state.center;
        double remaining = distanceBehind;
        for (int i = state.trail.size() - 1; i > 0; i--) {
            Vec3 newer = state.trail.get(i);
            Vec3 older = state.trail.get(i - 1);
            double length = newer.distanceTo(older);
            if (length >= remaining && length > 1.0E-5D) return newer.lerp(older, remaining / length);
            remaining -= length;
        }
        double yaw = Math.toRadians(state.heading);
        Vec3 forward = new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw));
        return state.trail.get(0).add(forward.scale(-remaining));
    }

    private static void beginQueueWhenReady(State state, int memberCount, long now) {
        if (state.dispersed || !state.route.constrained() || state.route.constrainedStart() < 0
                || state.route.points().isEmpty()) return;
        int entryIndex = Math.min(state.route.constrainedStart(), state.route.points().size() - 1);
        Vec3 entry = state.route.points().get(entryIndex);
        if (state.routeIndex < entryIndex && horizontalDistanceSq(state.center, entry) > 1.75D * 1.75D) return;
        state.dispersed = true;
        state.stagingCenter = state.center;
        state.releasedToQueue = Math.min(QUEUE_BATCH_SIZE, memberCount);
        state.nextQueueReleaseAt = now + 20L;
    }

    private static void releaseNextQueueBatch(State state, int memberCount, int queuedNotReady, long now) {
        if (!state.dispersed || state.releasedToQueue >= memberCount) return;
        if (queuedNotReady > 0 && now < state.nextQueueReleaseAt) return;
        state.releasedToQueue = Math.min(memberCount, state.releasedToQueue + QUEUE_BATCH_SIZE);
        state.nextQueueReleaseAt = now + 20L;
    }

    private static Vec3 effectiveDestination(State state) {
        if (state.route.complete() && !state.route.points().isEmpty()) {
            return state.route.points().get(state.route.points().size() - 1);
        }
        return state.destination;
    }

    private static void rememberTrail(State state, Vec3 point) {
        if (!state.trail.isEmpty() && state.trail.get(state.trail.size() - 1).distanceToSqr(point) < 0.025D) return;
        state.trail.add(point);
        int maximum = Math.max(32, state.offsets.size() * 6);
        while (state.trail.size() > maximum) state.trail.remove(0);
    }

    private static List<AbstractRecruitEntity> resolveMembers(MinecraftServer server, State state) {
        List<AbstractRecruitEntity> result = new ArrayList<>();
        ServerLevel level = server.getLevel(state.levelKey);
        if (level == null) return result;
        for (UUID id : state.offsets.keySet()) {
            Entity entity = level.getEntity(id);
            if (entity instanceof AbstractRecruitEntity recruit && recruit.isAlive() && !recruit.isRemoved()) {
                result.add(recruit);
            }
        }
        result.sort(Comparator.comparingInt(unit -> state.order.getOrDefault(unit.getUUID(), Integer.MAX_VALUE)));
        return result;
    }

    private static AbstractRecruitEntity resolve(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity instanceof AbstractRecruitEntity recruit && recruit.isAlive() && !recruit.isRemoved()) return recruit;
        }
        return null;
    }

    private static void claim(AbstractRecruitEntity recruit, float heading) {
        recruit.setShouldMovePos(false);
        recruit.clearMovePos();
        recruit.setShouldHoldPos(true);
        recruit.setFollowState(3);
        recruit.ownerRot = heading;
        recruit.isInFormation = true;
        recruit.holdFormation = true;
    }

    private static void releaseMembers(MinecraftServer server, State state, boolean stop) {
        if (server == null) return;
        for (UUID id : state.offsets.keySet()) {
            AbstractRecruitEntity recruit = resolve(server, id);
            if (recruit == null) continue;
            recruit.setShouldHoldPos(false);
            recruit.clearHoldPos();
            recruit.holdFormation = false;
            recruit.isInFormation = false;
            RecruitRuntimeRegistry.releaseFormation(recruit);
            if (stop) {
                Mob mover = controlledMob(recruit);
                if (mover != null) mover.getNavigation().stop();
            }
        }
    }

    private static void removeOverlapping(MinecraftServer server, UUID replacement, Set<UUID> members) {
        for (Map.Entry<UUID, State> entry : List.copyOf(STATES.entrySet())) {
            if (entry.getKey().equals(replacement) || java.util.Collections.disjoint(entry.getValue().offsets.keySet(), members)) {
                continue;
            }
            if (STATES.remove(entry.getKey(), entry.getValue())) releaseMembers(server, entry.getValue(), false);
        }
    }

    private static void invalidateRoutes(State state) {
        state.route = TacticalPathfinder.Route.EMPTY;
        state.routeIndex = 0;
        state.dispersed = false;
        state.releasedToQueue = 0;
        state.stagingCenter = null;
        state.agents.values().forEach(Agent::clearPath);
    }

    private static Entity controlled(AbstractRecruitEntity recruit) {
        return recruit.getVehicle() instanceof Mob ? recruit.getVehicle() : recruit;
    }

    private static Mob controlledMob(AbstractRecruitEntity recruit) {
        Entity controlled = controlled(recruit);
        return controlled instanceof Mob mob ? mob : null;
    }

    private static Vec3 averageControlledPosition(List<AbstractRecruitEntity> units, double fallbackY) {
        double x = 0.0D, y = 0.0D, z = 0.0D;
        for (AbstractRecruitEntity unit : units) {
            Entity controlled = controlled(unit);
            x += controlled.getX();
            y += controlled.getY();
            z += controlled.getZ();
        }
        double inverse = 1.0D / units.size();
        double averageY = y * inverse;
        return new Vec3(x * inverse, Double.isFinite(averageY) ? averageY : fallbackY, z * inverse);
    }

    private static Vec3 average(Iterable<Vec3> positions) {
        double x = 0.0D, y = 0.0D, z = 0.0D;
        int count = 0;
        for (Vec3 position : positions) {
            if (position == null) continue;
            x += position.x;
            y += position.y;
            z += position.z;
            count++;
        }
        return count == 0 ? Vec3.ZERO : new Vec3(x / count, y / count, z / count);
    }

    private static List<FormationSlotMath.Point> worldSlotPoints(
            Vec3 center,
            float heading,
            List<FormationSlotMath.Offset> offsets
    ) {
        return offsets.stream().map(offset -> {
            Vec3 slot = worldSlot(center, heading, offset);
            return new FormationSlotMath.Point(slot.x, slot.z);
        }).toList();
    }

    private static Vec3 worldSlot(Vec3 center, float heading, FormationSlotMath.Offset offset) {
        double yaw = Math.toRadians(heading);
        Vec3 forward = new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw));
        Vec3 right = new Vec3(forward.z, 0.0D, -forward.x);
        return center.add(right.scale(offset.right())).add(forward.scale(-offset.back()));
    }

    private static void faceMovement(AbstractRecruitEntity recruit, Mob mover, Vec3 target) {
        double dx = target.x - mover.getX();
        double dz = target.z - mover.getZ();
        if (dx * dx + dz * dz < 1.0E-5D) return;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        mover.setYRot(yaw);
        recruit.ownerRot = yaw;
    }

    private static boolean carriesMoraleItem(AbstractRecruitEntity recruit) {
        return recruit.getMainHandItem().is(RTSTags.MORALE_ITEMS)
                || recruit.getOffhandItem().is(RTSTags.MORALE_ITEMS);
    }

    private static double horizontalDistanceSq(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class State {
        final UUID groupId;
        final UUID ownerId;
        final ResourceKey<Level> levelKey;
        final FormationKind kind;
        final Map<UUID, FormationSlotMath.Offset> offsets;
        final Map<UUID, Float> facings;
        final Map<UUID, Integer> order = new HashMap<>();
        final Map<UUID, Agent> agents = new HashMap<>();
        final Set<UUID> locked = new HashSet<>();
        final List<Vec3> trail = new ArrayList<>();
        final boolean hold;
        final float heading;
        Vec3 center;
        Vec3 destination;
        Vec3 velocity = Vec3.ZERO;
        TacticalPathfinder.Route route = TacticalPathfinder.Route.EMPTY;
        int routeIndex;
        int laggards;
        int emptyTicks;
        int consecutiveFailures;
        boolean dispersed;
        int releasedToQueue;
        Vec3 stagingCenter;
        long nextQueueReleaseAt;
        boolean routePending;
        long nextRouteAt;

        State(UUID groupId, UUID ownerId, ResourceKey<Level> levelKey, FormationKind kind,
              float heading, Vec3 center, Vec3 destination,
              Map<UUID, FormationSlotMath.Offset> offsets, Map<UUID, Float> facings, boolean hold) {
            this.groupId = groupId;
            this.ownerId = ownerId;
            this.levelKey = levelKey;
            this.kind = kind;
            this.heading = heading;
            this.center = center;
            this.destination = destination;
            this.offsets = new LinkedHashMap<>(offsets);
            this.facings = new HashMap<>(facings);
            this.hold = hold;
            List<Map.Entry<UUID, FormationSlotMath.Offset>> transitOrder = new ArrayList<>(offsets.entrySet());
            transitOrder.sort(Comparator
                    .comparingDouble((Map.Entry<UUID, FormationSlotMath.Offset> entry) -> entry.getValue().back())
                    .thenComparingDouble(entry -> Math.abs(entry.getValue().right()))
                    .thenComparing(entry -> entry.getKey().toString()));
            int index = 0;
            for (Map.Entry<UUID, FormationSlotMath.Offset> entry : transitOrder) order.put(entry.getKey(), index++);
            rememberTrail(this, center);
        }
    }

    private static final class Agent {
        final int steeringPhase;
        TacticalPathfinder.Route path = TacticalPathfinder.Route.EMPTY;
        Vec3 goal;
        int index;
        long plannedAt;
        long nextPlanAt;
        boolean pathPending;
        Vec3 lastProgressPosition;
        Vec3 lastProgressGoal;
        double lastProgressDistanceSq = Double.POSITIVE_INFINITY;
        long lastProgressAt;
        Vec3 directGoal;
        Boolean directSafe;
        long directStartRevision = Long.MIN_VALUE;
        long directGoalRevision = Long.MIN_VALUE;
        Vec3 groundingGoal;
        Vec3 groundedGoal;
        long groundingRevision = Long.MIN_VALUE;

        Agent(UUID recruitId) {
            steeringPhase = Math.floorMod(recruitId.hashCode(), 10);
        }

        void clearPath() {
            path = TacticalPathfinder.Route.EMPTY;
            goal = null;
            index = 0;
        }

        void resetProgress(Vec3 position, Vec3 destination, long now) {
            lastProgressPosition = position;
            lastProgressGoal = destination;
            lastProgressDistanceSq = position.distanceToSqr(destination);
            lastProgressAt = now;
        }

        boolean stuck(Vec3 position, Vec3 destination, long now) {
            double distanceSq = position.distanceToSqr(destination);
            if (lastProgressPosition == null || lastProgressGoal == null
                    || lastProgressGoal.distanceToSqr(destination) > 0.25D
                    || lastProgressPosition.distanceToSqr(position) > 0.08D
                    || distanceSq + 0.08D < lastProgressDistanceSq) {
                lastProgressPosition = position;
                lastProgressGoal = destination;
                lastProgressDistanceSq = distanceSq;
                lastProgressAt = now;
                return false;
            }
            return now - lastProgressAt >= 14L;
        }

        Boolean canSteerDirect(ServerLevel level, Mob mover, Vec3 destination, long now) {
            long startRevision = TacticalTerrainRevisions.revisionAt(level, mover.position());
            long goalRevision = TacticalTerrainRevisions.revisionAt(level, destination);
            boolean goalMoved = directGoal == null || directGoal.distanceToSqr(destination) > 1.5D * 1.5D;
            boolean terrainChanged = startRevision != directStartRevision || goalRevision != directGoalRevision;
            if (goalMoved || terrainChanged) {
                directGoal = destination;
                directSafe = TacticalPathfinder.canSteerDirect(level, mover, destination);
                directStartRevision = startRevision;
                directGoalRevision = goalRevision;
            } else if (Math.floorMod(now, 10L) == steeringPhase) {
                directSafe = TacticalPathfinder.canSteerDirect(level, mover, destination);
            }
            return directSafe;
        }

        Vec3 grounded(ServerLevel level, Mob mover, Vec3 desired) {
            long revision = TacticalTerrainRevisions.revisionAt(level, desired);
            boolean movedColumn = groundingGoal == null
                    || horizontalDistanceSq(groundingGoal, desired) > 0.35D * 0.35D
                    || Math.abs(groundingGoal.y - desired.y) > 0.55D;
            if (movedColumn || revision != groundingRevision) {
                groundingGoal = desired;
                groundingRevision = revision;
                groundedGoal = TacticalPathfinder.standableAt(level, mover, desired);
                if (groundedGoal != null && groundedGoal.y < desired.y - 0.70D) groundedGoal = null;
                if (groundedGoal == null) {
                    groundedGoal = TacticalPathfinder.nearestFormationStandable(level, mover, desired);
                }
            }
            return groundedGoal;
        }
    }
}
