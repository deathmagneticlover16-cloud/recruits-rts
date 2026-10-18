package ee.lastpagans.recruitsrtscamera.combat;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.HorsemanEntity;
import com.talhanation.recruits.entities.IRangedRecruit;
import com.talhanation.recruits.entities.IStrategicFire;
import com.talhanation.recruits.entities.SiegeEngineerEntity;
import com.talhanation.recruits.util.FormationUtils;
import com.talhanation.recruits.util.RecruitCommanderUtil;
import com.talhanation.recruits.world.RecruitsGroup;
import com.talhanation.recruits.world.RecruitsGroupsSaveData;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.formation.FormationRuntime;
import ee.lastpagans.recruitsrtscamera.compat.SiegeWeaponsCompat;
import ee.lastpagans.recruitsrtscamera.formation.FormationService;
import ee.lastpagans.recruitsrtscamera.movement.RTSMovementBoostManager;
import ee.lastpagans.recruitsrtscamera.persistence.RTSGroupTacticsSavedData;
import ee.lastpagans.recruitsrtscamera.server.RTSVision;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent Total War-style group-vs-group attack orders.
 *
 * <p>0.10.7 keeps every explicit RTS attack as a persistent formation-vs-formation order.
 * The attacking group UUID remains bound to the hostile group UUID, while individual soldiers
 * receive stable frontage-aware target locks that are reassigned only when the target dies,
 * disappears, routes, or becomes invalid. This same formation target field is reused by missiles.
 * </p>
 *
 * <p>The earlier two-phase system turns melee attacks into a two-phase command. Infantry and melee cavalry
 * first advance as a formation toward the hostile formation, then release into combat
 * only when the two fronts are actually close enough to engage. This prevents an
 * attacking army from feeding individual recruits into a stationary enemy line one at
 * a time. Ranged-heavy groups keep their normal ranged behaviour and engage directly.</p>
 *
 * <p>Horsemen are handed to {@link CavalryTacticalController} at engagement. The native
 * {@code HorsemanAttackAI} is deliberately suppressed for that explicit RTS order because
 * its orbit/retry state machine causes the characteristic cavalry stop/rubber-band behaviour.
 * Ordinary non-RTS horsemen still use the native goal.</p>
 */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RTSGroupAttackManager {
    private static final double MAX_TACTICAL_DISTANCE = 144.0D;
    private static final int RETARGET_INTERVAL_TICKS = 5;
    private static final int APPROACH_REPLAN_TICKS = 20;
    private static final int EMPTY_TARGET_GRACE_TICKS = 40;
    private static final int MAX_APPROACH_TICKS = 20 * 45;
    private static final int MAX_ORDER_TICKS = 20 * 60 * 10;

    // These are edge-to-edge gaps, not centre distances.
    private static final double INFANTRY_ENGAGE_GAP = 3.25D;
    // Give mounted lines room to leave formation and build a real run-up before the
    // first contact. Infantry releases much closer to the opposing frontage.
    private static final double CAVALRY_RELEASE_GAP = 20.0D;
    private static final double APPROACH_REPLAN_DISTANCE_SQ = 1.75D * 1.75D;
    private static final double CAVALRY_HEAVY_RATIO = 0.60D;
    private static final double MELEE_APPROACH_RATIO = 0.50D;

    private static final Map<UUID, AttackState> ACTIVE = new ConcurrentHashMap<>();
    private static final Map<UUID, SingleTargetState> SINGLE_TARGETS = new ConcurrentHashMap<>();
    private static int tickCounter;

    private RTSGroupAttackManager() {
    }

    public static void start(ServerPlayer sender, UUID attackingGroupId, UUID targetGroupId) {
        if (sender == null || attackingGroupId == null || targetGroupId == null
                || attackingGroupId.equals(targetGroupId)) {
            return;
        }
        cancel(List.of(attackingGroupId));
        ACTIVE.put(attackingGroupId, new AttackState(
                sender, attackingGroupId, targetGroupId, MAX_ORDER_TICKS
        ));
    }

    public static void markCharge(UUID groupId) {
        AttackState state = ACTIVE.get(groupId);
        if (state != null) state.charge = true;
    }

    @SubscribeEvent
    public static void onStop(net.minecraftforge.event.server.ServerStoppedEvent event) {
        java.util.Set<UUID> groups = new java.util.HashSet<>(ACTIVE.keySet());
        groups.addAll(SINGLE_TARGETS.keySet());
        cancel(groups);
        tickCounter = 0;
    }

    /** Immediate single-target fallback used for non-Recruits targets. */
    public static int engageSingleTarget(
            ServerPlayer sender,
            UUID attackingGroupId,
            List<AbstractRecruitEntity> recruits,
            LivingEntity target
    ) {
        if (sender == null || attackingGroupId == null || recruits == null || recruits.isEmpty()
                || target == null || !target.isAlive()) {
            return 0;
        }
        cancel(List.of(attackingGroupId));
        SINGLE_TARGETS.put(attackingGroupId, new SingleTargetState(
                sender, attackingGroupId, target.getId(), MAX_ORDER_TICKS
        ));
        releaseFormationForCombat(sender, attackingGroupId, recruits);
        int ordered = 0;
        for (AbstractRecruitEntity recruit : recruits) {
            if (assignCombatTarget(recruit, target, attackingGroupId)) {
                ordered++;
            }
        }
        return ordered;
    }

    public static void cancel(Collection<UUID> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return;
        }
        for (UUID groupId : groupIds) {
            if (groupId != null) {
                AttackState order = ACTIVE.get(groupId);
                SingleTargetState single = SINGLE_TARGETS.get(groupId);
                ServerPlayer owner = order != null ? order.sender : single != null ? single.sender : null;
                if (owner != null && owner.getServer() != null)
                    RTSGroupTacticsSavedData.get(owner.serverLevel()).setTimer(groupId, "chargeUntil", 0);
                ACTIVE.remove(groupId);
                SINGLE_TARGETS.remove(groupId);
                TacticalCombatManager.cancelGroup(groupId);
                CavalryTacticalController.cancelGroup(groupId);
            }
        }
    }

    public static void cancelOwned(ServerPlayer sender, Collection<UUID> ids) {
        if (sender == null || ids == null) return;
        List<UUID> owned = new ArrayList<>();
        for (UUID id : ids) {
            RecruitsGroup group = findGroup(sender.serverLevel(), id);
            if (group != null && sender.getUUID().equals(group.getPlayerUUID())) owned.add(id);
        }
        cancel(owned);
    }

    public static void cancelPlayer(UUID playerId) {
        Set<UUID> ids = new HashSet<>();
        for (AttackState state : ACTIVE.values())
            if (state.sender.getUUID().equals(playerId)) ids.add(state.sourceGroupId);
        for (SingleTargetState state : SINGLE_TARGETS.values())
            if (state.sender.getUUID().equals(playerId)) ids.add(state.sourceGroupId);
        cancel(ids);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || (ACTIVE.isEmpty() && SINGLE_TARGETS.isEmpty())) {
            return;
        }
        if (++tickCounter % RETARGET_INTERVAL_TICKS != 0) {
            return;
        }

        for (Map.Entry<UUID, AttackState> entry : List.copyOf(ACTIVE.entrySet())) {
            UUID sourceGroupId = entry.getKey();
            AttackState state = entry.getValue();
            ServerPlayer sender = state.sender;
            if (sender == null || sender.isRemoved() || sender.getServer() == null
                    || (state.remainingTicks -= RETARGET_INTERVAL_TICKS) <= 0) {
                cancel(List.of(sourceGroupId));
                continue;
            }

            ServerLevel level = sender.serverLevel();
            RecruitsGroup sourceGroup = findGroup(level, state.sourceGroupId);
            RecruitsGroup targetGroup = findGroup(level, state.targetGroupId);
            if (sourceGroup == null || targetGroup == null
                    || sourceGroup.removed || targetGroup.removed
                    || sourceGroup.members == null || sourceGroup.members.isEmpty()) {
                cancel(List.of(sourceGroupId));
                continue;
            }

            List<LivingEntity> candidates = loadedTargets(sender, targetGroup);
            if (candidates.isEmpty()) {
                state.emptyTicks += RETARGET_INTERVAL_TICKS;
                if (state.emptyTicks >= EMPTY_TARGET_GRACE_TICKS) {
                    cancel(List.of(sourceGroupId));
                }
                continue;
            }
            state.emptyTicks = 0;

            List<AbstractRecruitEntity> attackers = loadedAttackers(sender, sourceGroup);
            if (attackers.isEmpty()) {
                continue;
            }

            for (var attacker : attackers) TacticalCombatManager.applyMode(attacker);

            if (state.phase == Phase.NEW) {
                state.approachKind = classifyApproach(attackers);
                if (state.approachKind == ApproachKind.DIRECT || state.charge) {
                    beginEngagement(state, attackers, candidates);
                } else {
                    state.phase = Phase.APPROACH;
                    issueApproachOrder(state, attackers, candidates, true);
                }
                continue;
            }

            if (state.phase == Phase.APPROACH) {
                state.approachTicks += RETARGET_INTERVAL_TICKS;

                // Do not let Neutral/Aggressive target acquisition peel individuals away
                // while the formation is still closing on the enemy line.
                for (var attacker : attackers) TacticalCombatManager.lock(attacker, state.sourceGroupId, null);
                RecruitCommanderUtil.setRecruitsClearTargets(attackers);
                // Tagged spear/pilum infantry only throws when the player explicitly
                // enabled Use Missiles for this group. The default is Hold Missiles.
                if (useMissiles(state.sender.serverLevel(), state.sourceGroupId)) {
                    throwOpeningMissiles(attackers, candidates);
                }

                double edgeGap = formationEdgeGap(attackers, candidates);
                double releaseGap = state.approachKind == ApproachKind.CAVALRY
                        ? CAVALRY_RELEASE_GAP
                        : INFANTRY_ENGAGE_GAP;
                if (edgeGap <= releaseGap || state.approachTicks >= MAX_APPROACH_TICKS) {
                    beginEngagement(state, attackers, candidates);
                    continue;
                }

                state.replanTicks += RETARGET_INTERVAL_TICKS;
                if (state.replanTicks >= APPROACH_REPLAN_TICKS) {
                    issueApproachOrder(state, attackers, candidates, false);
                    state.replanTicks = 0;
                }
                continue;
            }

            retargetEngagedAttackers(state, attackers, candidates);
        }

        // Non-Recruits targets (zombies, players, animals, bosses, etc.) also keep a
        // lightweight persistent attack state. That gives tagged spear infantry a chance
        // to throw once they naturally close into missile range, so the system is fully
        // testable by one player without manufacturing a second Recruits group UUID.
        for (Map.Entry<UUID, SingleTargetState> entry : List.copyOf(SINGLE_TARGETS.entrySet())) {
            UUID sourceGroupId = entry.getKey();
            SingleTargetState state = entry.getValue();
            ServerPlayer sender = state.sender;
            if (sender == null || sender.isRemoved() || sender.getServer() == null
                    || (state.remainingTicks -= RETARGET_INTERVAL_TICKS) <= 0) {
                cancel(List.of(sourceGroupId));
                continue;
            }
            ServerLevel level = sender.serverLevel();
            Entity rawTarget = level.getEntity(state.targetEntityId);
            if (!(rawTarget instanceof LivingEntity target) || !target.isAlive() || target.isRemoved()) {
                cancel(List.of(sourceGroupId));
                continue;
            }
            RecruitsGroup sourceGroup = findGroup(level, state.sourceGroupId);
            if (sourceGroup == null || sourceGroup.removed || sourceGroup.members == null
                    || sourceGroup.members.isEmpty()) {
                cancel(List.of(sourceGroupId));
                continue;
            }
            List<AbstractRecruitEntity> attackers = loadedAttackers(sender, sourceGroup);
            if (attackers.isEmpty()) continue;
            boolean throwAllowed = useMissiles(level, sourceGroupId);
            for (AbstractRecruitEntity attacker : attackers) {
                if (throwAllowed && ThrowableWeaponManager.hasThrowable(attacker)
                        && ThrowableWeaponManager.tryThrow(attacker, target)) {
                    TacticalCombatManager.lock(attacker, state.sourceGroupId, target);
                    continue;
                }
                maintainPersistentAttack(attacker, target, state.sourceGroupId);
            }
        }
    }

    private static ApproachKind classifyApproach(List<AbstractRecruitEntity> attackers) {
        int alive = 0;
        int melee = 0;
        int horsemen = 0;
        for (AbstractRecruitEntity recruit : attackers) {
            if (recruit == null || !recruit.isAlive()) {
                continue;
            }
            alive++;
            if (!TacticalCombatManager.ranged(recruit) && !(recruit instanceof SiegeEngineerEntity)) {
                melee++;
                if (CavalryTacticalController.eligible(recruit)) {
                    horsemen++;
                }
            }
        }
        if (alive == 0 || melee / (double) alive < MELEE_APPROACH_RATIO) {
            return ApproachKind.DIRECT;
        }
        if (melee > 0 && horsemen / (double) melee >= CAVALRY_HEAVY_RATIO) {
            return ApproachKind.CAVALRY;
        }
        return ApproachKind.INFANTRY;
    }

    private static void issueApproachOrder(
            AttackState state,
            List<AbstractRecruitEntity> attackers,
            List<LivingEntity> targets,
            boolean force
    ) {
        Vec3 sourceCenter = averageControlledPosition(attackers);
        Vec3 targetCenter = averagePosition(targets);
        if (sourceCenter == null || targetCenter == null) {
            return;
        }

        Vec3 delta = new Vec3(targetCenter.x - sourceCenter.x, 0.0D, targetCenter.z - sourceCenter.z);
        double distance = Math.hypot(delta.x, delta.z);
        if (distance < 1.0E-5D) {
            return;
        }
        Vec3 direction = new Vec3(delta.x / distance, 0.0D, delta.z / distance);
        double sourceExtent = forwardExtent(attackers, sourceCenter, direction);
        double targetExtent = backwardExtent(targets, targetCenter, direction);
        double desiredGap = state.approachKind == ApproachKind.CAVALRY
                ? CAVALRY_RELEASE_GAP
                : INFANTRY_ENGAGE_GAP;
        double desiredCenterDistance = Math.max(1.0D, sourceExtent + targetExtent + desiredGap);
        Vec3 desired = new Vec3(
                targetCenter.x - direction.x * desiredCenterDistance,
                targetCenter.y,
                targetCenter.z - direction.z * desiredCenterDistance
        );

        if (!force && state.lastApproachDestination != null
                && state.lastApproachDestination.distanceToSqr(desired) < APPROACH_REPLAN_DISTANCE_SQ) {
            return;
        }

        ServerLevel level = state.sender.serverLevel();
        BlockPos surface = FormationUtils.getPositionOrSurface(level, BlockPos.containing(desired));
        Vec3 destination = new Vec3(desired.x, surface.getY(), desired.z);
        for (var attacker : attackers) TacticalCombatManager.lock(attacker, state.sourceGroupId, null);
        RecruitCommanderUtil.setRecruitsClearTargets(attackers);
        boolean preserved = FormationService.movePreserved(
                state.sender, state.sourceGroupId, attackers, destination
        );
        if (!preserved) {
            RecruitCommanderUtil.setRecruitsMove(attackers, surface);
        }
        RTSMovementBoostManager.registerFormationMove(
                state.sender, state.sourceGroupId, attackers
        );
        state.lastApproachDestination = destination;
    }

    private static void beginEngagement(
            AttackState state,
            List<AbstractRecruitEntity> attackers,
            List<LivingEntity> candidates
    ) {
        releaseFormationForCombat(state.sender, state.sourceGroupId, attackers);
        state.phase = Phase.ENGAGED;
        state.approachTicks = 0;
        state.replanTicks = 0;
        state.lastApproachDestination = null;
        distributeTargets(state, attackers, candidates, true);
    }

    private static void releaseFormationForCombat(
            ServerPlayer sender,
            UUID groupId,
            List<AbstractRecruitEntity> recruits
    ) {
        RTSMovementBoostManager.cancelForcedOrders(List.of(groupId));
        FormationRuntime.releaseForAttack(sender, groupId);
        FormationService.releaseCustomForAttack(sender, groupId, recruits);
        RecruitCommanderUtil.setRecruitsClearTargets(recruits);
        for (AbstractRecruitEntity recruit : recruits) {
            if (recruit == null || !recruit.isAlive()) {
                continue;
            }
            // Attack orders must not leave a stale RTS move/hold flag blocking Recruits'
            // native combat goals after the formation is released.
            recruit.setShouldMovePos(false);
            recruit.setShouldHoldPos(false);
            recruit.clearMovePos();
            recruit.clearHoldPos();
            recruit.reachedMovePos = false;
            recruit.holdFormation = false;
            recruit.isInFormation = false;
            recruit.getNavigation().stop();
            if (recruit instanceof HorsemanEntity) {
                // CavalryTacticalController sets followState 3 when its priority-0 lane goal
                // is activated. Leave native mounted AI eligible until that target lock is
                // installed on this tick.
                recruit.setFollowState(0);
            }
        }
    }

    private static void retargetEngagedAttackers(
            AttackState state,
            List<AbstractRecruitEntity> attackers,
            List<LivingEntity> candidates
    ) {
        Map<Integer, LivingEntity> candidateById = new HashMap<>();
        for (LivingEntity candidate : candidates) {
            if (candidate != null && candidate.isAlive() && !candidate.isRemoved()) {
                candidateById.put(candidate.getId(), candidate);
            }
        }

        Map<Integer, Integer> assignments = new HashMap<>();
        for (AbstractRecruitEntity attacker : attackers) {
            Integer lockedId = state.lockedTargets.get(attacker.getUUID());
            LivingEntity locked = lockedId == null ? null : candidateById.get(lockedId);
            if (locked != null && validTarget(attacker, locked)) {
                assignments.merge(locked.getId(), 1, Integer::sum);
            } else {
                state.lockedTargets.remove(attacker.getUUID());
            }
        }

        Map<UUID, LivingEntity> frontageAssignments = formationAssignments(attackers, candidates);
        boolean throwAllowed = useMissiles(state.sender.serverLevel(), state.sourceGroupId);
        for (AbstractRecruitEntity attacker : attackers) {
            LivingEntity locked = lockedTarget(attacker, candidateById, state.lockedTargets);
            if (locked == null) {
                // A dead front-rank target is replaced by the corresponding soldier in
                // the opposing frontage first. Only fall back to nearest/least-loaded
                // selection when that frontage slot is no longer valid.
                locked = frontageAssignments.get(attacker.getUUID());
                if (!validTarget(attacker, locked)
                        || assignments.getOrDefault(locked.getId(), 0) > minimumAssignment(candidates, assignments, attacker)) {
                    locked = chooseTarget(attacker, candidates, assignments);
                }
                if (locked == null) continue;
                state.lockedTargets.put(attacker.getUUID(), locked.getId());
                assignments.merge(locked.getId(), 1, Integer::sum);
            }

            if (throwAllowed && ThrowableWeaponManager.hasThrowable(attacker)
                    && ThrowableWeaponManager.tryThrow(attacker, locked)) {
                TacticalCombatManager.lock(attacker, state.sourceGroupId, locked);
                continue;
            }
            maintainPersistentAttack(attacker, locked, state.sourceGroupId);
            if (attacker instanceof SiegeEngineerEntity engineer) {
                SiegeWeaponsCompat.focusTarget(engineer, locked);
            }
        }
    }

    private static int minimumAssignment(
            List<LivingEntity> candidates,
            Map<Integer, Integer> assignments,
            AbstractRecruitEntity attacker
    ) {
        int minimum = Integer.MAX_VALUE;
        for (LivingEntity candidate : candidates) {
            if (validTarget(attacker, candidate)) {
                minimum = Math.min(minimum, assignments.getOrDefault(candidate.getId(), 0));
            }
        }
        return minimum == Integer.MAX_VALUE ? 0 : minimum;
    }

    private static LivingEntity lockedTarget(
            AbstractRecruitEntity attacker,
            Map<Integer, LivingEntity> candidateById,
            Map<UUID, Integer> lockedTargets
    ) {
        Integer id = lockedTargets.get(attacker.getUUID());
        if (id == null) return null;
        LivingEntity target = candidateById.get(id);
        if (!validTarget(attacker, target)) {
            lockedTargets.remove(attacker.getUUID());
            return null;
        }
        return target;
    }

    private static void distributeTargets(
            AttackState state,
            List<AbstractRecruitEntity> attackers,
            List<LivingEntity> candidates,
            boolean clearExisting
    ) {
        if (clearExisting) {
            state.lockedTargets.clear();
            for (AbstractRecruitEntity attacker : attackers) {
                if (attacker != null) {
                    attacker.setTarget(null);
                }
            }
        }
        // Assign front-most troops first, which makes the first contact less sensitive to
        // entity-list ordering and tends to spread the opening clash across the enemy line.
        List<AbstractRecruitEntity> orderedAttackers = new ArrayList<>(attackers);
        Vec3 targetCenter = averagePosition(candidates);
        if (targetCenter != null) {
            orderedAttackers.sort(Comparator.comparingDouble(a -> a.position().distanceToSqr(targetCenter)));
        }

        Map<Integer, Integer> assignments = new HashMap<>();
        Map<UUID, LivingEntity> frontageAssignments = formationAssignments(orderedAttackers, candidates);
        for (AbstractRecruitEntity attacker : orderedAttackers) {
            LivingEntity assigned = frontageAssignments.get(attacker.getUUID());
            if (!validTarget(attacker, assigned)) {
                assigned = chooseTarget(attacker, candidates, assignments);
            }
            if (assigned == null) continue;
            assignments.merge(assigned.getId(), 1, Integer::sum);
            state.lockedTargets.put(attacker.getUUID(), assigned.getId());
            maintainPersistentAttack(attacker, assigned, state.sourceGroupId);
        }
    }

    /**
     * RTS attack orders are explicit commitments. Native Recruits melee AI drops a target
     * immediately when line of sight flickers, which in a packed formation can make soldiers
     * repeatedly turn back toward follow/idle goals before reacquiring the same enemy. Keep
     * the assigned target locked until it dies or the player cancels the order with a movement
     * command. For melee troops we also keep navigation moving toward the locked target through
     * temporary LOS loss instead of allowing the return-home/follow goals to take over.
     */
    private static boolean maintainPersistentAttack(
            AbstractRecruitEntity attacker,
            LivingEntity assigned,
            UUID groupId
    ) {
        if (!validTarget(attacker, assigned)) return false;
        TacticalCombatManager.applyMode(attacker);
        TacticalCombatManager.lock(attacker, groupId, assigned);

        // Mounted melee is no longer handed to native HorsemanAttackAI. It receives a
        // stable frontage target, then follows a committed approach/charge/pass/disengage
        // lane so every rider crosses the enemy formation instead of orbiting one soldier.
        if (CavalryTacticalController.eligible(attacker)) {
            CavalryTacticalController.activate(attacker, assigned, groupId);
            return true;
        }

        if (attacker instanceof SiegeEngineerEntity engineer
                && SiegeWeaponsCompat.focusTarget(engineer, assigned)) {
            return true;
        }

        if (attacker instanceof IStrategicFire strategic) {
            strategic.setShouldStrategicFire(false);
            if (TacticalCombatManager.settings(attacker).combat() != TacticalSettings.CombatMode.MELEE
                    && !TacticalCombatManager.settings(attacker).holdFire()) attacker.setShouldRanged(true);
        }

        attacker.setShouldMovePos(false);
        attacker.setShouldHoldPos(false);
        attacker.clearMovePos();
        attacker.clearHoldPos();
        attacker.setShouldFollow(false);
        attacker.setIsFollowing(false);
        attacker.setFollowState(0);
        if (attacker.getTarget() != assigned) {
            attacker.setTarget(assigned);
        }

        // Do not override normal bow/crossbow standoff behavior. The forced path support is
        // specifically for melee troops whose native attack goal stopped because sight flickered.
        if (!TacticalCombatManager.ranged(attacker) && !(attacker instanceof SiegeEngineerEntity)) {
            double distanceSq = attacker.distanceToSqr(assigned);
            if (distanceSq > 2.75D * 2.75D
                    && (!attacker.hasLineOfSight(assigned) || attacker.getNavigation().isDone())) {
                attacker.getNavigation().moveTo(assigned, 1.0D);
            }
        }
        return true;
    }

    /**
     * Give one recruit a combat target without reintroducing the old movement-state bug.
     * Siege Engineers retain their native compatibility path; Horsemen are deliberately
     * handed to CavalryTacticalController so its committed lane goal owns movement.
     */
    private static boolean assignCombatTarget(
            AbstractRecruitEntity attacker,
            LivingEntity assigned,
            UUID groupId
    ) {
        if (!validTarget(attacker, assigned)) {
            return false;
        }
        TacticalCombatManager.applyMode(attacker);
        TacticalCombatManager.lock(attacker, groupId, assigned);
        if (CavalryTacticalController.eligible(attacker)) {
            CavalryTacticalController.activate(attacker, assigned, groupId);
            return true;
        }
        if (attacker instanceof SiegeEngineerEntity engineer
                && SiegeWeaponsCompat.focusTarget(engineer, assigned)) {
            return true;
        }

        // A right-click ranged attack is a tracked entity/group target, not another
        // static strategic-fire coordinate. Clear any old bombardment point so bows,
        // crossbows and other Recruits ranged roles follow the moving hostile group.
        if (attacker instanceof IStrategicFire strategic) {
            strategic.setShouldStrategicFire(false);
            if (TacticalCombatManager.settings(attacker).combat() != TacticalSettings.CombatMode.MELEE
                    && !TacticalCombatManager.settings(attacker).holdFire()) attacker.setShouldRanged(true);
        }

        attacker.setShouldMovePos(false);
        attacker.setShouldHoldPos(false);
        attacker.clearMovePos();
        attacker.clearHoldPos();
        attacker.setShouldFollow(false);
        attacker.setIsFollowing(false);
        attacker.setFollowState(0);
        attacker.setTarget(assigned);
        return true;
    }

    private static RecruitsGroup findGroup(ServerLevel level, UUID groupId) {
        if (level == null || groupId == null) {
            return null;
        }
        for (RecruitsGroup group : RecruitsGroupsSaveData.get(level).getAllGroups()) {
            if (group != null && groupId.equals(group.getUUID())) {
                return group;
            }
        }
        return null;
    }

    private static List<AbstractRecruitEntity> loadedAttackers(ServerPlayer sender, RecruitsGroup group) {
        List<AbstractRecruitEntity> result = new ArrayList<>();
        if (group.members == null) {
            return result;
        }
        ServerLevel level = sender.serverLevel();
        double maxSq = MAX_TACTICAL_DISTANCE * MAX_TACTICAL_DISTANCE;
        for (UUID memberId : group.members) {
            if (memberId == null) continue;
            Entity entity = level.getEntity(memberId);
            if (!(entity instanceof AbstractRecruitEntity recruit)
                    || !recruit.isAlive() || recruit.isRemoved()
                    || !sender.getUUID().equals(recruit.getOwnerUUID())
                    || recruit.distanceToSqr(sender) > maxSq) {
                continue;
            }
            result.add(recruit);
        }
        return result;
    }

    private static List<LivingEntity> loadedTargets(ServerPlayer sender, RecruitsGroup group) {
        List<LivingEntity> result = new ArrayList<>();
        if (group.members == null) {
            return result;
        }
        ServerLevel level = sender.serverLevel();
        double maxSq = MAX_TACTICAL_DISTANCE * MAX_TACTICAL_DISTANCE;
        for (UUID memberId : group.members) {
            if (memberId == null) continue;
            Entity entity = level.getEntity(memberId);
            if (!(entity instanceof LivingEntity living) || !living.isAlive() || living.isRemoved()
                    || living == sender || living.distanceToSqr(sender) > maxSq) {
                continue;
            }
            if (RTSVision.hiddenTargetChecksEnabled() && !RTSVision.canSee(sender, living.position())) {
                continue;
            }
            result.add(living);
        }
        return result;
    }

    /**
     * Explicitly balance target counts first, then use distance as the tie-breaker. This
     * prevents a geometrically convenient enemy from collecting most of the attacking
     * formation before the spread penalty has time to catch up.
     */

    private static boolean useMissiles(ServerLevel level, UUID groupId) {
        return level != null && groupId != null
                && RTSGroupTacticsSavedData.get(level).useMissiles(groupId);
    }

    private static void throwOpeningMissiles(
            List<AbstractRecruitEntity> attackers,
            List<LivingEntity> candidates
    ) {
        // A formation volley is aimed at the enemy FORMATION, not at one unlucky entity.
        // Reuse the same left-to-right frontage assignment as melee target locks so a line of
        // javelins/pila spreads across the hostile body instead of converging on its nearest man.
        Map<UUID, LivingEntity> assigned = formationAssignments(attackers, candidates);
        Map<Integer, Integer> fallbackCounts = new HashMap<>();
        for (AbstractRecruitEntity attacker : attackers) {
            if (!ThrowableWeaponManager.hasThrowable(attacker)) continue;
            LivingEntity target = assigned.get(attacker.getUUID());
            if (!validTarget(attacker, target)) {
                target = chooseTarget(attacker, candidates, fallbackCounts);
            }
            if (target != null) {
                fallbackCounts.merge(target.getId(), 1, Integer::sum);
                ThrowableWeaponManager.tryThrow(attacker, target);
            }
        }
    }

    /**
     * Build stable left-to-right target pairs between two formations. Attackers and targets are
     * projected onto the axis perpendicular to the line between the group centres, then mapped
     * proportionally. This greatly reduces criss-crossing and naturally distributes volleys.
     */
    private static Map<UUID, LivingEntity> formationAssignments(
            List<AbstractRecruitEntity> attackers,
            List<LivingEntity> candidates
    ) {
        Map<UUID, LivingEntity> result = new HashMap<>();
        if (attackers == null || attackers.isEmpty() || candidates == null || candidates.isEmpty()) return result;
        Vec3 sourceCenter = averageControlledPosition(attackers);
        Vec3 targetCenter = averagePosition(candidates);
        if (sourceCenter == null || targetCenter == null) return result;
        Vec3 forward = new Vec3(targetCenter.x - sourceCenter.x, 0.0D, targetCenter.z - sourceCenter.z);
        if (forward.lengthSqr() < 1.0E-5D) forward = new Vec3(0.0D, 0.0D, 1.0D);
        else forward = forward.normalize();
        final Vec3 side = new Vec3(-forward.z, 0.0D, forward.x);

        List<AbstractRecruitEntity> orderedAttackers = attackers.stream()
                .filter(a -> a != null && a.isAlive() && !a.isRemoved())
                .sorted(Comparator.comparingDouble(a -> lateral(a.position(), sourceCenter, side)))
                .toList();
        List<LivingEntity> orderedTargets = candidates.stream()
                .filter(t -> t != null && t.isAlive() && !t.isRemoved())
                .sorted(Comparator.comparingDouble(t -> lateral(t.position(), targetCenter, side)))
                .toList();
        if (orderedAttackers.isEmpty() || orderedTargets.isEmpty()) return result;

        int aCount = orderedAttackers.size();
        int tCount = orderedTargets.size();
        for (int i = 0; i < aCount; i++) {
            int targetIndex = aCount <= 1
                    ? tCount / 2
                    : (int) Math.round(i * (tCount - 1.0D) / (aCount - 1.0D));
            targetIndex = Math.max(0, Math.min(tCount - 1, targetIndex));
            result.put(orderedAttackers.get(i).getUUID(), orderedTargets.get(targetIndex));
        }
        return result;
    }

    private static double lateral(Vec3 position, Vec3 center, Vec3 side) {
        return (position.x - center.x) * side.x + (position.z - center.z) * side.z;
    }

    private static LivingEntity nearestValidTarget(
            AbstractRecruitEntity attacker,
            List<LivingEntity> candidates
    ) {
        LivingEntity best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (LivingEntity candidate : candidates) {
            if (!validTarget(attacker, candidate)) continue;
            double distance = attacker.distanceToSqr(candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private static LivingEntity chooseTarget(
            AbstractRecruitEntity attacker,
            List<LivingEntity> candidates,
            Map<Integer, Integer> assignments
    ) {
        int minimumAssignments = Integer.MAX_VALUE;
        for (LivingEntity candidate : candidates) {
            if (validTarget(attacker, candidate)) {
                minimumAssignments = Math.min(
                        minimumAssignments,
                        assignments.getOrDefault(candidate.getId(), 0)
                );
            }
        }
        if (minimumAssignments == Integer.MAX_VALUE) {
            return null;
        }

        LivingEntity best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (LivingEntity candidate : candidates) {
            if (!validTarget(attacker, candidate)
                    || assignments.getOrDefault(candidate.getId(), 0) != minimumAssignments) {
                continue;
            }
            double distance = attacker.distanceToSqr(candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private static boolean validTarget(AbstractRecruitEntity attacker, LivingEntity target) {
        return attacker != null && target != null && target != attacker && target.isAlive()
                && !attacker.isAlliedTo(target) && attacker.canAttack(target);
    }

    private static Vec3 averageControlledPosition(List<AbstractRecruitEntity> recruits) {
        double x = 0.0D, y = 0.0D, z = 0.0D;
        int count = 0;
        for (AbstractRecruitEntity recruit : recruits) {
            if (recruit == null || !recruit.isAlive() || recruit.isRemoved()) continue;
            Entity controlled = recruit.getVehicle() != null ? recruit.getVehicle() : recruit;
            Vec3 pos = controlled.position();
            x += pos.x; y += pos.y; z += pos.z; count++;
        }
        return count == 0 ? null : new Vec3(x / count, y / count, z / count);
    }

    private static Vec3 averagePosition(List<? extends LivingEntity> entities) {
        double x = 0.0D, y = 0.0D, z = 0.0D;
        int count = 0;
        for (LivingEntity entity : entities) {
            if (entity == null || !entity.isAlive() || entity.isRemoved()) continue;
            Vec3 pos = entity.getVehicle() != null ? entity.getVehicle().position() : entity.position();
            x += pos.x; y += pos.y; z += pos.z; count++;
        }
        return count == 0 ? null : new Vec3(x / count, y / count, z / count);
    }

    private static double formationEdgeGap(
            List<AbstractRecruitEntity> attackers,
            List<LivingEntity> targets
    ) {
        Vec3 sourceCenter = averageControlledPosition(attackers);
        Vec3 targetCenter = averagePosition(targets);
        if (sourceCenter == null || targetCenter == null) {
            return Double.POSITIVE_INFINITY;
        }
        Vec3 delta = new Vec3(targetCenter.x - sourceCenter.x, 0.0D, targetCenter.z - sourceCenter.z);
        double centerDistance = Math.hypot(delta.x, delta.z);
        if (centerDistance < 1.0E-5D) {
            return 0.0D;
        }
        Vec3 direction = new Vec3(delta.x / centerDistance, 0.0D, delta.z / centerDistance);
        return Math.max(0.0D,
                centerDistance
                        - forwardExtent(attackers, sourceCenter, direction)
                        - backwardExtent(targets, targetCenter, direction)
        );
    }

    private static double forwardExtent(
            List<AbstractRecruitEntity> recruits,
            Vec3 center,
            Vec3 direction
    ) {
        double extent = 0.0D;
        for (AbstractRecruitEntity recruit : recruits) {
            if (recruit == null || !recruit.isAlive() || recruit.isRemoved()) continue;
            Entity controlled = recruit.getVehicle() != null ? recruit.getVehicle() : recruit;
            Vec3 pos = controlled.position();
            extent = Math.max(extent,
                    (pos.x - center.x) * direction.x + (pos.z - center.z) * direction.z
            );
        }
        return extent;
    }

    private static double backwardExtent(
            List<LivingEntity> targets,
            Vec3 center,
            Vec3 direction
    ) {
        double extent = 0.0D;
        for (LivingEntity target : targets) {
            if (target == null || !target.isAlive() || target.isRemoved()) continue;
            Vec3 pos = target.getVehicle() != null ? target.getVehicle().position() : target.position();
            extent = Math.max(extent,
                    (center.x - pos.x) * direction.x + (center.z - pos.z) * direction.z
            );
        }
        return extent;
    }

    private enum Phase {
        NEW,
        APPROACH,
        ENGAGED
    }

    private enum ApproachKind {
        DIRECT,
        INFANTRY,
        CAVALRY
    }

    private static final class SingleTargetState {
        private final ServerPlayer sender;
        private final UUID sourceGroupId;
        private final int targetEntityId;
        private int remainingTicks;

        private SingleTargetState(ServerPlayer sender, UUID sourceGroupId, int targetEntityId, int remainingTicks) {
            this.sender = sender;
            this.sourceGroupId = sourceGroupId;
            this.targetEntityId = targetEntityId;
            this.remainingTicks = remainingTicks;
        }
    }

    private static final class AttackState {
        private final ServerPlayer sender;
        private final UUID sourceGroupId;
        private final UUID targetGroupId;
        private int remainingTicks;
        private boolean charge;
        private int emptyTicks;
        private int approachTicks;
        private int replanTicks;
        private Phase phase = Phase.NEW;
        private ApproachKind approachKind = ApproachKind.DIRECT;
        private Vec3 lastApproachDestination;
        private final Map<UUID, Integer> lockedTargets = new HashMap<>();

        private AttackState(
                ServerPlayer sender,
                UUID sourceGroupId,
                UUID targetGroupId,
                int remainingTicks
        ) {
            this.sender = sender;
            this.sourceGroupId = sourceGroupId;
            this.targetGroupId = targetGroupId;
            this.remainingTicks = remainingTicks;
        }
    }
}
