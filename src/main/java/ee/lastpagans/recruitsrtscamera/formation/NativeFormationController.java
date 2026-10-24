package ee.lastpagans.recruitsrtscamera.formation;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.animation.CommanderAnimationHints;
import ee.lastpagans.recruitsrtscamera.config.RTSServerConfig;
import ee.lastpagans.recruitsrtscamera.compat.TerrainSurfaceResolver;
import ee.lastpagans.recruitsrtscamera.mixin.MobGoalAccess;
import ee.lastpagans.recruitsrtscamera.navigation.TacticalPathfinder;
import ee.lastpagans.recruitsrtscamera.navigation.LocalFormationSteering;
import ee.lastpagans.recruitsrtscamera.navigation.TacticalTerrainRevisions;
import ee.lastpagans.recruitsrtscamera.movement.RTSMovementBoostManager;
import ee.lastpagans.recruitsrtscamera.movement.RTSMountControlRegistry;
import ee.lastpagans.recruitsrtscamera.network.FormationRouteSyncPacket;
import ee.lastpagans.recruitsrtscamera.network.RTSCameraNetwork;
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
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.EnumSet;

/**
 * Built-in server-authoritative formation runtime.
 *
 * <p>The controller owns slot assignment and execution. A formation follows one shared tactical
 * route on open ground. At ladders, stairs, and narrow passages, the group immediately enters
 * the original distance-spaced single column and follows centered support-shape waypoints.
 * Members recover their geometric slots after the anchor reaches its destination.</p>
 */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class NativeFormationController {
    private static final Map<UUID, State> STATES = new LinkedHashMap<>();
    private static final int MAX_FORMATION_SEARCH = 18_000;
    private static final int QUICK_FORMATION_SEARCH = 2_500;
    private static final long QUICK_PATH_BUDGET_NANOS = 20_000_000L;
    private static final int MAX_AGENT_SEARCH = 3_500;
    private static final double CENTER_SPEED = 0.21D;
    private static final double SLOT_LOCK_DISTANCE_SQ = 0.30D * 0.30D;
    private static final double FOOT_NAV_SPEED = 1.17D;
    private static final double MOUNTED_NAV_SPEED = 0.72D;

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
        // Assign against the live formation, never the distant destination. Destination-
        // based matching could send a horse to the opposite end of the line after a
        // reverse order even though the same nearby physical slot was available.
        List<FormationSlotMath.Point> slotPoints = worldSlotPoints(start, heading, offsets);
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
            claim(unit);
            FormationSlotMath.Offset offset = assigned.get(unit.getUUID());
            if (offset != null) RecruitRuntimeRegistry.claimFormation(unit,
                    worldSlot(start, heading, offset), Vec3.ZERO);
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
            claim(unit);
            FormationSlotMath.Offset offset = offsets.get(unit.getUUID());
            if (offset != null) RecruitRuntimeRegistry.claimFormation(unit,
                    worldSlot(start, 0.0F, offset), Vec3.ZERO);
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

    /** Face every member without rotating or rebuilding the formation's slot geometry. */
    public static boolean face(UUID groupId, float heading) {
        State state = STATES.get(groupId);
        if (state == null || !Float.isFinite(heading)) return false;
        for (UUID recruitId : state.offsets.keySet()) state.facings.put(recruitId, heading);
        return true;
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
        // A drag is a replacement move order from the formation's live location. Moving
        // the virtual centre itself would make every slot jump, and translating an older
        // destination would make an already-moving formation continue beyond the preview.
        state.destination = state.center.add(delta);
        invalidateRoutes(state);
        return true;
    }

    public static boolean retarget(UUID groupId, Vec3 destination) {
        State state = STATES.get(groupId);
        if (state == null || destination == null) return false;
        state.destination = destination;
        // Keep following the already-safe route instead of resetting every member. Once
        // its published segment is consumed, advanceAlongRoute requests the continuation.
        if (state.route.points().isEmpty() || state.routeIndex >= state.route.points().size()) {
            invalidateRoutes(state);
        }
        return true;
    }

    /** Replace an active route immediately for an explicit attack/charge order. */
    public static boolean retargetImmediately(UUID groupId, Vec3 destination) {
        State state = STATES.get(groupId);
        if (state == null || destination == null) return false;
        state.destination = destination;
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
        invalidateRoutes(state);
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
            MountControl mountControl = state.mountControls.remove(id);
            if (mountControl != null) mountControl.release();
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
            if (++state.emptyTicks > 20 * 300 && STATES.remove(state.groupId, state)) {
                releaseMembers(server, state, false);
            }
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
            Vec3 requestedDestination = state.destination;
            long routeGeneration = state.routeGeneration;
            state.routePending = RecruitAIScheduler.submit(
                    RecruitAIScheduler.Priority.HIGH,
                    RecruitAIScheduler.WorkType.FORMATION_PATH,
                    state.groupId,
                    () -> {
                        try {
                            if (STATES.get(state.groupId) != state || !representative.isAlive()
                                    || state.routeGeneration != routeGeneration) return;
                            long started = System.nanoTime();
                            TacticalPathfinder.Route planned = TacticalPathfinder.find(
                                    level, representative, state.center, requestedDestination, QUICK_FORMATION_SEARCH);
                            long quickElapsed = System.nanoTime() - started;
                            // Easy routes are usually complete inside the quick pass. If a
                            // partial pass stayed under 20 ms, finish the difficult route
                            // before movement. A pass that already consumed the budget is
                            // published as a safe partial route and continued on the run.
                            if (!planned.complete() && quickElapsed < QUICK_PATH_BUDGET_NANOS) {
                                planned = TacticalPathfinder.find(
                                        level, representative, state.center, requestedDestination, MAX_FORMATION_SEARCH);
                            }
                            if (state.routeGeneration != routeGeneration) return;
                            state.route = planned;
                            state.routeGoal = requestedDestination;
                            RecruitRuntimeMetrics.FORMATION_PATHS.increment();
                            if (state.route.points().isEmpty()) RecruitRuntimeMetrics.PATH_FAILURES.increment();
                            if (state.route.constrained()) RecruitRuntimeMetrics.CONSTRAINED_ROUTES.increment();
                            state.routeIndex = Math.min(1, Math.max(0, state.route.points().size() - 1));
                            // Elevation alone is not a reason to rotate a line into a column.
                            // Preserve frontage over ordinary slopes and broad stairs; only a
                            // genuinely narrow route may temporarily use the trail queue.
                            state.dispersed = false;
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
        if (travelling) {
            // Enter a queue only when the anchor reaches the narrow cell. A ladder later
            // in the route must not rotate a line while it is still crossing a courtyard.
            state.dispersed = state.route.constrained() && constraintImmediatelyAhead(level, representative, state);
        }
        Vec3 previousCenter = state.center;
        // Keep the body coherent without allowing one conscientious objector to freeze it.
        // A growing laggard share elastically slows the anchor; it never stops entirely.
        if (travelling && !state.route.points().isEmpty()) {
            double laggardShare = state.offsets.isEmpty() ? 0.0D
                    : state.laggards / (double) state.offsets.size();
            double cohesionTarget = Math.max(0.20D, 1.0D - laggardShare * 1.35D);
            // Let the formation assemble before its virtual centre outruns the physical
            // mounts. Ease this value so gaining/losing one laggard cannot pulse the pace.
            state.cohesionScale += clamp(cohesionTarget - state.cohesionScale, -0.04D, 0.04D);
            // Pace is applied before slots are calculated. Adjusting the centre after this
            // tick made every member chase a forward point and then snap back together.
            advanceAlongRoute(state, RTSMovementBoostManager.formationCenterStep(state.groupId, CENTER_SPEED)
                    * state.cohesionScale);
        } else if (!travelling) {
            state.dispersed = false;
        }
        state.velocity = state.center.subtract(previousCenter);
        if (travelling) {
            float desiredTravelHeading = movementHeading(state);
            if (Math.abs(angleDifference(state.travelHeading, desiredTravelHeading)) > 0.75F) {
                state.travelHeading = approachHeading(state.travelHeading, desiredTravelHeading, 4.5F);
            }
        }
        updateSlotHeading(state);
        float slotHeading = state.slotHeading;

        int laggards = 0;
        int index = 0;
        for (AbstractRecruitEntity recruit : members) {
            FormationSlotMath.Offset offset = state.offsets.get(recruit.getUUID());
            if (offset == null) continue;
            RecruitRuntimeMetrics.BRAIN_UPDATES.increment();
            float memberHeading = state.facings.getOrDefault(recruit.getUUID(), state.heading);
            claim(recruit);
            Mob mover = controlledMob(recruit);
            if (mover == null) continue;
            syncMountControl(state, recruit, mover);
            int transitIndex = index++;
            boolean columnTransit = state.dispersed && travelling;
            Vec3 desired = columnTransit
                    ? trailPosition(state, transitIndex)
                    : worldSlot(state.center, slotHeading, offset);
            Agent agent = state.agents.computeIfAbsent(recruit.getUUID(), Agent::new);
            Vec3 grounded = agent.grounded(level, mover, desired);
            if (grounded != null) desired = grounded;
            double slotError = horizontalDistanceSq(mover.position(), desired);
            double heightError = Math.abs(mover.getY() - desired.y);
            boolean mountedMember = mover != recruit;
            double catchUpScale = mountedMember
                    ? slotError > 4.0D * 4.0D || heightError > 1.35D ? 1.08D
                    : slotError > 0.85D * 0.85D || heightError > 0.85D ? 1.04D : 1.0D
                    : slotError > 4.0D * 4.0D || heightError > 1.35D ? 1.40D
                    : slotError > 1.55D * 1.55D || heightError > 0.85D ? 1.20D : 1.0D;
            recruit.setHoldPos(desired);
            RecruitRuntimeRegistry.claimFormation(recruit, desired, state.velocity);
            boolean arrived = drive(level, recruit, mover, desired, agent, columnTransit,
                    state.velocity, catchUpScale);
            CommanderAnimationHints.updateMovement(recruit, travelling || !arrived,
                    RTSMovementBoostManager.isHastyActive(state.groupId));
            // Formation movement owns rotation in exactly one place. Native MoveControl,
            // ownerRot and mount/rider body rotation previously received competing values
            // during the same tick, producing constant alternating yaw. Keep every moving
            // body on the shared route tangent; restore the ordered per-member facing only
            // after both the anchor and this member have settled.
            // Every moving body follows the route tangent. The authored slot stencil stays
            // fixed, so a route bend cannot throw outside members across the formation.
            float controlledHeading;
            if (mover != recruit && (travelling || !arrived)) {
                // Every rider/mount pair receives the same group-owned travel yaw. Per-member
                // easing allowed tiny differences to survive after repeated angled orders.
                controlledHeading = state.travelHeading;
            } else if (travelling) {
                controlledHeading = state.travelHeading;
            } else {
                controlledHeading = arrived
                        ? memberHeading : headingTo(mover.position(), desired, memberHeading);
            }
            applyFormationFacing(recruit, mover, controlledHeading);
            if (arrived) {
                state.locked.add(recruit.getUUID());
                RecruitRuntimeRegistry.brain(recruit).settle(level.getGameTime());
            }
            else {
                state.locked.remove(recruit.getUUID());
                RecruitRuntimeRegistry.brain(recruit).markMoving(level.getGameTime());
                double cohesionDistance = mountedMember ? 0.85D : 1.55D;
                if (slotError > cohesionDistance * cohesionDistance || heightError > 0.85D) laggards++;
            }
        }
        state.laggards = laggards;
        if (!travelling) RTSMovementBoostManager.completeMovement(state.groupId);
        syncRoute(server, state, travelling);
    }

    private static void syncRoute(MinecraftServer server, State state, boolean travelling) {
        ServerLevel stateLevel = server.getLevel(state.levelKey);
        if (stateLevel == null) return;
        long now = stateLevel.getGameTime();
        if (state.lastRouteSyncAt != Long.MIN_VALUE && !travelling && !state.lastSyncedMoving) return;
        if (state.lastRouteSyncAt != Long.MIN_VALUE
                && travelling == state.lastSyncedMoving && now - state.lastRouteSyncAt < 5L) return;
        ServerPlayer owner = server.getPlayerList().getPlayer(state.ownerId);
        if (owner == null || !owner.level().dimension().equals(state.levelKey)) return;
        List<Vec3> remaining = new ArrayList<>();
        List<Vec3> points = state.route.points();
        for (int i = Math.max(0, state.routeIndex); i < points.size() && remaining.size() < 255; i++) {
            Vec3 point = points.get(i);
            if (horizontalDistanceSq(state.center, point) > 0.20D * 0.20D) remaining.add(point);
        }
        Vec3 routeEnd = effectiveDestination(state);
        if (travelling && (remaining.isEmpty()
                || remaining.get(remaining.size() - 1).distanceToSqr(routeEnd) > 0.25D)) {
            remaining.add(routeEnd);
        }
        RTSCameraNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> owner),
                new FormationRouteSyncPacket(state.groupId, state.center, state.destination, remaining, travelling));
        state.lastRouteSyncAt = now;
        state.lastSyncedMoving = travelling;
    }

    private static float movementHeading(State state) {
        Vec3 direction = Vec3.ZERO;
        if (state.routeIndex < state.route.points().size()) {
            direction = state.route.points().get(state.routeIndex).subtract(state.center);
        }
        if (direction.x * direction.x + direction.z * direction.z < 1.0E-6D) direction = state.velocity;
        if (direction.x * direction.x + direction.z * direction.z < 1.0E-6D) {
            direction = effectiveDestination(state).subtract(state.center);
        }
        if (direction.x * direction.x + direction.z * direction.z < 1.0E-6D) return state.heading;
        return (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
    }

    private static void updateSlotHeading(State state) {
        // The ordered stencil is immutable in transit. Rotating a wide stencil around every
        // route bend moves its outside slots by several blocks and makes units chase a shape
        // that no longer resembles the authored frontage. Bodies still face travel below.
        state.slotHeading = state.heading;
    }

    private static void rebuildTransitOrder(State state) {
        List<Map.Entry<UUID, FormationSlotMath.Offset>> transitOrder = new ArrayList<>(state.offsets.entrySet());
        transitOrder.sort(Comparator
                .comparingDouble((Map.Entry<UUID, FormationSlotMath.Offset> entry) -> entry.getValue().back())
                .thenComparingDouble(entry -> Math.abs(entry.getValue().right()))
                .thenComparing(entry -> entry.getKey().toString()));
        state.order.clear();
        int index = 0;
        for (Map.Entry<UUID, FormationSlotMath.Offset> entry : transitOrder) {
            state.order.put(entry.getKey(), index++);
        }
    }

    private static float approachHeading(float current, float target, float maximumStep) {
        float delta = angleDifference(current, target);
        if (Math.abs(delta) <= maximumStep) return target;
        return current + Math.copySign(maximumStep, delta);
    }

    private static float angleDifference(float current, float target) {
        float delta = (target - current) % 360.0F;
        if (delta > 180.0F) delta -= 360.0F;
        if (delta < -180.0F) delta += 360.0F;
        return delta;
    }

    private static boolean constraintImmediatelyAhead(ServerLevel level, Mob mover, State state) {
        List<Vec3> points = state.route.points();
        if (points.isEmpty()) return false;
        int start = Math.max(0, state.routeIndex - 1);
        int end = Math.min(points.size(), state.routeIndex + 4);
        for (int i = start; i < end; i++) {
            Vec3 point = points.get(i);
            if (horizontalDistanceSq(state.center, point) > 4.0D * 4.0D) continue;
            if (TacticalPathfinder.requiresColumn(level, mover, point)) return true;
        }
        return false;
    }

    private static boolean drive(
            ServerLevel level,
            AbstractRecruitEntity recruit,
            Mob mover,
            Vec3 desired,
            Agent agent,
            boolean dispersed,
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
            agent.manualControl = false;
            return true;
        }

        long now = level.getGameTime();
        // On stairs/ladders use the shape-aware tactical waypoints exclusively. Local
        // steering is deliberately disabled there because its lateral correction can
        // pull a member toward the outside edge of a stair.
        boolean localCandidate = !dispersed && RTSServerConfig.LOCAL_STEERING.get()
                && horizontal <= 8.0D * 8.0D && vertical <= 2.0D;
        Boolean directSafety = localCandidate ? agent.canSteerDirect(level, mover, desired, now) : Boolean.FALSE;
        boolean fallenBehind = desired.y - mover.getY() > 0.72D;
        boolean direct = Boolean.TRUE.equals(directSafety);
        // A supported one-block rise is ordinary terrain, not evidence that the member
        // fell off the formation. Sending every rider/footman through PathNavigation at
        // the lip caused a controller handoff, bounce, random yaw and abandoned slots.
        // The direct proof already checks support, clearance, fluids and bounded drops.
        boolean ordinarySupportedRise = fallenBehind && direct
                && desired.y - mover.getY() <= 1.25D;
        if (direct && (!fallenBehind || ordinarySupportedRise)
                && !agent.stuck(mover.position(), desired, now)) {
            agent.clearPath();
            agent.beginManual(mover);
            boolean manual = LocalFormationSteering.steer(level, recruit, mover, desired,
                    RecruitRuntimeRegistry.brain(recruit).desiredVelocity(), catchUpScale);
            agent.manualControl = manual;
            if (!manual) {
                agent.directSafe = false;
                agent.directBlockedUntil = now + 12L;
            }
            return false;
        }

        boolean fellOffConstrainedRoute = dispersed && fallenBehind
                && !agent.path.points().isEmpty()
                && agent.pathDeviationSq(mover.position()) > 0.85D * 0.85D;
        if (fellOffConstrainedRoute) {
            // A member knocked off stairs must start a route from its landing point.
            // Continuing toward an upper stale waypoint makes it jump against the side.
            agent.clearPath();
            agent.nextPlanAt = 0L;
        }
        // Moving slots must not invalidate individual paths every few ticks. Replan only
        // after a material route change; the final desired slot is still followed live.
        double goalTolerance = dispersed ? 1.50D : 2.50D;
        boolean goalChanged = agent.goal == null || agent.goal.distanceToSqr(desired) > goalTolerance * goalTolerance;
        boolean needsPath = agent.path.points().isEmpty() || agent.index >= agent.path.points().size()
                || goalChanged || !agent.path.isValid(level);
        if (needsPath && directSafety != null && !agent.pathPending && now >= agent.nextPlanAt) {
            Vec3 pathGoal = desired;
            long pathGeneration = agent.pathGeneration;
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
                                    != RecruitRuntimeBrain.MovementAuthority.RTS_FORMATION
                                    || agent.pathGeneration != pathGeneration) return;
                            TacticalPathfinder.Route planned = TacticalPathfinder.find(
                                    level, mover, pathGoal, MAX_AGENT_SEARCH);
                            if (agent.pathGeneration != pathGeneration) return;
                            agent.path = planned;
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
            agent.manualControl = false;
            mover.getNavigation().moveTo(desired.x, desired.y, desired.z,
                    navigationSpeed(recruit) * catchUpScale);
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
        agent.beginManual(mover);
        if (climbing && waypoint.y > mover.getY() + 0.15D) {
            double dx = waypoint.x - mover.getX();
            double dz = waypoint.z - mover.getZ();
            Vec3 velocity = mover.getDeltaMovement();
            mover.setDeltaMovement(clamp(dx * 0.16D * catchUpScale, -0.14D, 0.14D),
                    Math.max(0.14D * catchUpScale, velocity.y),
                    clamp(dz * 0.16D * catchUpScale, -0.14D, 0.14D));
        } else {
            if (waypoint.y > mover.getY() + 0.45D
                    && horizontalDistanceSq(mover.position(), waypoint) < 1.45D * 1.45D) {
                mover.getJumpControl().jump();
                if (recruit.getVehicle() == mover && mover.onGround()) {
                    Vec3 motion = mover.getDeltaMovement();
                    mover.setDeltaMovement(motion.x, Math.max(motion.y, 0.42D), motion.z);
                    mover.hasImpulse = true;
                }
            }
            mover.getMoveControl().setWantedPosition(waypoint.x, waypoint.y, waypoint.z,
                    navigationSpeed(recruit) * catchUpScale);
        }
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
            if (horizontalDistanceSq(state.center, state.destination) > 0.16D
                    || Math.abs(state.center.y - state.destination.y) > 0.65D) {
                state.route = TacticalPathfinder.Route.EMPTY;
                state.routeIndex = 0;
                state.routeGoal = null;
            }
        } else if (state.routeIndex >= state.route.points().size()) {
            state.route = TacticalPathfinder.Route.EMPTY;
            state.routeIndex = 0;
            state.routeGoal = null;
        }
    }

    private static Vec3 trailPosition(State state, int memberIndex) {
        if (state.trail.isEmpty()) return state.center;
        int behind = memberIndex * 5;
        int trailIndex = state.trail.size() - 1 - behind;
        if (trailIndex >= 0) return state.trail.get(trailIndex);
        double yaw = Math.toRadians(state.heading);
        Vec3 forward = new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw));
        return state.trail.get(0).add(forward.scale(trailIndex * 0.20D));
    }

    private static Vec3 effectiveDestination(State state) {
        if (state.route.complete() && !state.route.points().isEmpty()) {
            // Path cells are centered for safe traversal, but a translated formation may
            // have a sub-block destination. Preserve the resolved safe elevation while
            // completing the order at its exact commanded horizontal position.
            Vec3 resolved = state.route.points().get(state.route.points().size() - 1);
            Vec3 goal = state.routeGoal == null ? state.destination : state.routeGoal;
            return new Vec3(goal.x, resolved.y, goal.z);
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

    private static void claim(AbstractRecruitEntity recruit) {
        recruit.setShouldMovePos(false);
        recruit.clearMovePos();
        recruit.setShouldHoldPos(true);
        recruit.setFollowState(3);
        recruit.isInFormation = true;
        recruit.holdFormation = true;
    }

    private static void releaseMembers(MinecraftServer server, State state, boolean stop) {
        if (server == null) return;
        releaseMountControls(state);
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
                CommanderAnimationHints.clearMovement(recruit);
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
        state.routeGeneration++;
        state.route = TacticalPathfinder.Route.EMPTY;
        state.routeGoal = null;
        state.routeIndex = 0;
        state.dispersed = false;
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

    private static float headingTo(Vec3 from, Vec3 to, float fallback) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return dx * dx + dz * dz < 1.0E-6D
                ? fallback : (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /**
     * A mounted formation owns the horse's locomotion. This high-priority, flag-only goal
     * prevents horse wandering and panic goals from competing with the rider's formation
     * brain while leaving MoveControl, JumpControl and animation ticking available.
     */
    private static void syncMountControl(State state, AbstractRecruitEntity rider, Mob mover) {
        UUID riderId = rider.getUUID();
        MountControl lease = state.mountControls.get(riderId);
        if (mover == rider) {
            if (lease != null) {
                lease.release();
                state.mountControls.remove(riderId);
            }
            return;
        }
        if (lease == null || lease.mount != mover) {
            if (lease != null) lease.release();
            lease = new MountControl(rider, mover);
            state.mountControls.put(riderId, lease);
            ((MobGoalAccess) mover).rts$getGoals().addGoal(-10, lease.goal);
        }
        mover.setTarget(null);
    }

    private static void releaseMountControls(State state) {
        for (MountControl control : state.mountControls.values()) control.release();
        state.mountControls.clear();
    }

    /** Single rotation owner for both stable route travel and ordered final facing. */
    private static void applyFormationFacing(AbstractRecruitEntity recruit, Mob mover, float yaw) {
        float previousYaw = mover.getYRot();
        recruit.ownerRot = yaw;
        recruit.setYRot(yaw);
        recruit.setYBodyRot(yaw);
        recruit.setYHeadRot(yaw);
        recruit.yRotO = previousYaw;
        recruit.yBodyRotO = previousYaw;
        recruit.yHeadRotO = previousYaw;
        if (mover != recruit) {
            mover.setYRot(yaw);
            mover.setYBodyRot(yaw);
            mover.setYHeadRot(yaw);
            mover.yRotO = previousYaw;
            mover.yBodyRotO = previousYaw;
            mover.yHeadRotO = previousYaw;
        }
    }

    private static double navigationSpeed(AbstractRecruitEntity recruit) {
        return recruit.getVehicle() == null ? FOOT_NAV_SPEED : MOUNTED_NAV_SPEED;
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
        final Map<UUID, MountControl> mountControls = new HashMap<>();
        final Set<UUID> locked = new HashSet<>();
        final List<Vec3> trail = new ArrayList<>();
        final boolean hold;
        final float heading;
        Vec3 center;
        Vec3 destination;
        Vec3 velocity = Vec3.ZERO;
        TacticalPathfinder.Route route = TacticalPathfinder.Route.EMPTY;
        Vec3 routeGoal;
        int routeIndex;
        int laggards;
        float travelHeading;
        int emptyTicks;
        int consecutiveFailures;
        boolean dispersed;
        boolean routePending;
        long routeGeneration;
        long nextRouteAt;
        long lastRouteSyncAt = Long.MIN_VALUE;
        boolean lastSyncedMoving;
        float slotHeading;
        double cohesionScale = 0.20D;

        State(UUID groupId, UUID ownerId, ResourceKey<Level> levelKey, FormationKind kind,
              float heading, Vec3 center, Vec3 destination,
              Map<UUID, FormationSlotMath.Offset> offsets, Map<UUID, Float> facings, boolean hold) {
            this.groupId = groupId;
            this.ownerId = ownerId;
            this.levelKey = levelKey;
            this.kind = kind;
            this.heading = heading;
            this.travelHeading = heading;
            this.slotHeading = heading;
            this.center = center;
            this.destination = destination;
            this.offsets = new LinkedHashMap<>(offsets);
            this.facings = new HashMap<>(facings);
            this.hold = hold;
            rebuildTransitOrder(this);
            rememberTrail(this, center);
        }
    }

    private static final class MountControl {
        final AbstractRecruitEntity rider;
        final Mob mount;
        final Goal goal;
        boolean released;

        MountControl(AbstractRecruitEntity rider, Mob mount) {
            this.rider = rider;
            this.mount = mount;
            RTSMountControlRegistry.claim(mount);
            this.goal = new Goal() {
                {
                    setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP, Flag.TARGET));
                }

                @Override
                public boolean canUse() {
                    return rider.isAlive() && !rider.isRemoved() && rider.getVehicle() == mount;
                }

                @Override
                public boolean canContinueToUse() {
                    return canUse();
                }

                @Override
                public void tick() {
                    mount.setTarget(null);
                }
            };
        }

        void release() {
            if (released) return;
            released = true;
            ((MobGoalAccess) mount).rts$getGoals().removeGoal(goal);
            RTSMountControlRegistry.release(mount);
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
        boolean manualControl;
        long pathGeneration;
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
        long directBlockedUntil;

        Agent(UUID recruitId) {
            steeringPhase = Math.floorMod(recruitId.hashCode(), 10);
        }

        void beginManual(Mob mover) {
            if (!manualControl) {
                Vec3 momentum = mover.getDeltaMovement();
                mover.getNavigation().stop();
                // Navigation ownership changes must not create a one-tick zero velocity
                // pulse that restarts custom walking or galloping animations.
                mover.setDeltaMovement(momentum);
                manualControl = true;
            }
        }

        void clearPath() {
            pathGeneration++;
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

        double pathDeviationSq(Vec3 position) {
            if (path.points().isEmpty()) return Double.POSITIVE_INFINITY;
            double nearest = Double.POSITIVE_INFINITY;
            int start = Math.max(0, index - 1);
            int end = Math.min(path.points().size(), index + 3);
            for (int i = start; i < end; i++) {
                nearest = Math.min(nearest, horizontalDistanceSq(position, path.points().get(i)));
            }
            return nearest;
        }

        Boolean canSteerDirect(ServerLevel level, Mob mover, Vec3 destination, long now) {
            if (now < directBlockedUntil) return Boolean.FALSE;
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
