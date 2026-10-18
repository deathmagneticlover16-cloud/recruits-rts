package ee.lastpagans.recruitsrtscamera.movement;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.HorsemanEntity;
import com.talhanation.recruits.entities.NomadEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.compat.BetterFormationsCompat;
import ee.lastpagans.recruitsrtscamera.compat.SmallShipsCompat;
import ee.lastpagans.recruitsrtscamera.formation.FormationService;
import ee.lastpagans.recruitsrtscamera.config.RTSServerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RTS movement pacing.
 *
 * Better Formations moves its
 * formation centre by a fixed 0.2 blocks/tick and directly glides every member by at
 * most 0.3 blocks/tick. Attribute/navigation multipliers alone therefore could never
 * make a mounted formation materially faster. We now accelerate the Better Formations
 * centre for cavalry groups and, after Better Formations has driven the horse, add a
 * collision-aware mounted stride in the same direction so the horses can keep up.
 *
 * No permanent entity attributes are changed. Hasty movement remains formation-
 * preserving and keeps the deterministic +/-6% individual pace variation. Rotation
 * orders use a separate damping pass so frontage pivots visibly wheel/reform instead
 * of snapping through Better Formations' normal 0.3-block member glide.
 */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RTSMovementBoostManager {
    private static final double REGULAR_CAVALRY_NAV_SPEED = 1.50D;
    private static final double REGULAR_CAVALRY_MOUNT_MULTIPLIER = 0.35D;
    private static final double HASTY_INFANTRY_SPEED = 1.08D;
    private static final double HASTY_INFANTRY_ATTRIBUTE_MULTIPLIER = 0.08D;
    private static final double HASTY_CAVALRY_NAV_SPEED = 1.62D;
    private static final double HASTY_CAVALRY_MOUNT_MULTIPLIER = 0.45D;

    // Better Formations centre STEP is 0.2 blocks/tick. These are *additional* steps.
    private static double regularCavalryExtra() { return RTSServerConfig.CAVALRY_FORMATION_SPEED.get() - 0.2D; }
    private static final double GROUP_EXTRA_HASTY_INFANTRY = 0.04D;   // ~0.24 total
    private static double hastyCavalryExtra() { return RTSServerConfig.CAVALRY_HASTY_SPEED.get() - 0.2D; }

    // Better Formations direct member glide is capped around 0.3 blocks/tick.
    private static final double HASTY_VARIATION = 0.06D;
    private static final double SLOW_MARCH_FACTOR = 0.55D;
    private static final double SLOW_MARCH_NAV_SPEED = 0.68D;
    private static final double SLOW_MARCH_ATTRIBUTE_MULTIPLIER = -0.22D;
    private static final double CAVALRY_GROUP_RATIO = 0.70D;
    private static final int MAX_FORMATION_TICKS = 20 * 55;
    private static final int MAX_HASTY_TICKS = 20 * 50;
    private static final int MAX_SLOW_MARCH_TICKS = 20 * 70;
    private static final int MAX_ROTATION_TICKS = 20 * 20;
    private static final double ROTATION_REPOSITION_FACTOR = 0.40D;
    private static final double ARRIVAL_DISTANCE_SQ = 1.25D * 1.25D;
    private static final UUID ARMOR_WEIGHT_MODIFIER_ID = UUID.fromString("8d6f7c24-4fe2-4dc7-a36e-79dd83f5fb31");
    private static final int FORCED_ORDER_MAX_TICKS = 20 * 70;
    private static final int FORCED_ORDER_REPAIR_COOLDOWN = 60;
    private static final double FORCED_ORDER_ARRIVAL_DISTANCE = 0.45D;

    private static final Map<UUID, BoostState> ACTIVE = new ConcurrentHashMap<>();
    private static final Map<UUID, GroupBoostState> ACTIVE_GROUPS = new ConcurrentHashMap<>();
    private static final Map<UUID, RotationState> ACTIVE_ROTATIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, ForcedOrderState> ACTIVE_ORDERS = new ConcurrentHashMap<>();

    private RTSMovementBoostManager() {
    }

    /** Track mounted members and, when appropriate, the Better Formations group centre. */
    public static int registerFormationMove(ServerPlayer sender, UUID groupId, List<AbstractRecruitEntity> recruits) {
        if (recruits == null) {
            return 0;
        }
        clearRotationState(groupId, recruits);
        int cavalry = 0;
        int alive = 0;
        for (AbstractRecruitEntity recruit : recruits) {
            if (recruit == null || !recruit.isAlive()) {
                continue;
            }
            alive++;
            if (isCavalry(recruit)) {
                cavalry++;
                replaceState(recruit, groupId, Mode.FORMATION_CAVALRY, MAX_FORMATION_TICKS, 1.0D);
            } else {
                // Better Formations directly glides infantry, bypassing movement-speed
                // attributes. Track ordinary infantry too so Armor Weight can damp that
                // direct step instead of being ignored during RTS formation movement.
                replaceState(recruit, groupId, Mode.FORMATION_INFANTRY, MAX_FORMATION_TICKS, 1.0D);
            }
        }
        if (groupId != null && alive > 0) {
            registerGroup(
                    groupId,
                    cavalry / (double) alive >= CAVALRY_GROUP_RATIO
                            ? GroupMode.FORMATION_CAVALRY
                            : GroupMode.FORMATION_INFANTRY,
                    MAX_FORMATION_TICKS,
                    recruits
            );
        }
        registerForcedOrder(sender, groupId, recruits);
        return cavalry;
    }

    /** Backward-compatible helper for callers that do not have a player/group id. */
    public static int registerFormationMove(UUID groupId, List<AbstractRecruitEntity> recruits) {
        return registerFormationMove(null, groupId, recruits);
    }

    public static int registerFormationMove(List<AbstractRecruitEntity> recruits) {
        return registerFormationMove(null, null, recruits);
    }

    /**
     * Slow March is an explicit orderly movement pace. Unlike Formation Doctrine's passive
     * modifiers, this is an order-level pace: Better Formations' group centre and direct member
     * glide are both damped so the whole body visibly advances more slowly.
     */
    public static void registerSlowMove(ServerPlayer sender, UUID groupId, List<AbstractRecruitEntity> recruits) {
        if (recruits == null || recruits.isEmpty()) {
            return;
        }
        clearRotationState(groupId, recruits);
        for (AbstractRecruitEntity recruit : recruits) {
            if (recruit == null || !recruit.isAlive()) {
                continue;
            }
            replaceState(recruit, groupId, Mode.SLOW_MARCH, MAX_SLOW_MARCH_TICKS, 1.0D);
        }
        if (groupId != null) {
            registerGroup(groupId, GroupMode.SLOW_MARCH, MAX_SLOW_MARCH_TICKS, recruits);
        }
        registerForcedOrder(sender, groupId, recruits);
    }

    /**
     * Rotation/reorientation deliberately uses a slower visual pace than an ordinary march.
     * Better Formations directly glides members, so lowering vanilla movement attributes alone
     * does not solve the snap. We instead remember each controlled body's pre-BF position and
     * keep only 40% of Better Formations' horizontal step while the group is settling.
     */
    public static void registerRotationMove(ServerPlayer sender, UUID groupId, List<AbstractRecruitEntity> recruits) {
        if (recruits == null || recruits.isEmpty()) {
            return;
        }
        if (groupId != null) {
            // A pivot should be orderly even for cavalry; do not let the normal cavalry centre
            // accelerator fight the rotation pacing pass.
            ACTIVE_GROUPS.remove(groupId);
        }
        for (AbstractRecruitEntity recruit : recruits) {
            if (recruit == null || !recruit.isAlive()) {
                continue;
            }
            UUID id = recruit.getUUID();
            BoostState oldBoost = ACTIVE.remove(id);
            if (oldBoost != null) {
                cleanup(oldBoost);
            }
            ACTIVE_ROTATIONS.put(id, new RotationState(recruit, groupId, MAX_ROTATION_TICKS));
        }
        registerForcedOrder(sender, groupId, recruits);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void captureFormationDrivenPositions(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        for (Map.Entry<UUID, RotationState> entry : List.copyOf(ACTIVE_ROTATIONS.entrySet())) {
            RotationState state = entry.getValue();
            AbstractRecruitEntity recruit = state.recruit;
            if (recruit == null || !recruit.isAlive() || recruit.isRemoved() || state.remainingTicks <= 0) {
                ACTIVE_ROTATIONS.remove(entry.getKey(), state);
                continue;
            }
            Entity controlled = controlledEntity(recruit);
            state.beforeFormationStep = controlled == null || controlled.isRemoved()
                    ? null
                    : controlled.position();
        }

        // Better Formations moves members directly, which previously hid most of the
        // per-recruit +/-6% Hasty Move variation. Capture the real pre-BF position so
        // the LOWEST tick pass can preserve a small individual difference in that step.
        for (BoostState state : List.copyOf(ACTIVE.values())) {
            if (state == null) {
                continue;
            }
            AbstractRecruitEntity recruit = state.recruit;
            Entity controlled = controlledEntity(recruit);
            state.beforeFormationStep = controlled == null || controlled.isRemoved()
                    ? null
                    : controlled.position();
        }
    }

    /**
     * Hasty movement keeps every recruit assigned to its formation slot while allowing
     * a slightly quicker formation centre and small stable per-recruit pace differences.
     */
    public static int registerHastyMove(ServerPlayer sender, UUID groupId, List<AbstractRecruitEntity> recruits) {
        if (recruits == null) {
            return 0;
        }
        clearRotationState(groupId, recruits);
        int cavalry = 0;
        int alive = 0;
        for (AbstractRecruitEntity recruit : recruits) {
            if (recruit == null || !recruit.isAlive()) {
                continue;
            }
            alive++;
            boolean mounted = isCavalry(recruit);
            if (mounted) {
                cavalry++;
            }
            replaceState(
                    recruit,
                    groupId,
                    mounted ? Mode.HASTY_CAVALRY : Mode.HASTY_INFANTRY,
                    MAX_HASTY_TICKS,
                    hastyRunnerFactor(recruit.getUUID())
            );
        }
        if (groupId != null && alive > 0) {
            boolean cavalryGroup = cavalry / (double) alive >= CAVALRY_GROUP_RATIO;
            registerGroup(
                    groupId,
                    cavalryGroup ? GroupMode.HASTY_CAVALRY : GroupMode.HASTY_INFANTRY,
                    MAX_HASTY_TICKS,
                    recruits
            );
        }
        registerForcedOrder(sender, groupId, recruits);
        return cavalry;
    }

    public static int registerHastyMove(UUID groupId, List<AbstractRecruitEntity> recruits) {
        return registerHastyMove(null, groupId, recruits);
    }

    public static int registerHastyMove(List<AbstractRecruitEntity> recruits) {
        return registerHastyMove(null, null, recruits);
    }

    private static void clearRotationState(UUID groupId, List<AbstractRecruitEntity> recruits) {
        if (recruits == null) {
            return;
        }
        for (AbstractRecruitEntity recruit : recruits) {
            if (recruit != null) {
                ACTIVE_ROTATIONS.remove(recruit.getUUID());
            }
        }
    }

    private static void registerGroup(UUID groupId, GroupMode mode, int ticks, List<AbstractRecruitEntity> recruits) {
        Vec3 center = BetterFormationsCompat.formationCenter(groupId);
        if (center != null) {
            ACTIVE_GROUPS.put(groupId, new GroupBoostState(mode, ticks, center, averageArmorWeightScale(recruits)));
        }
    }

    /**
     * Explicit RTS movement temporarily outranks ordinary combat acquisition.  This state is
     * intentionally short lived and is cleared by HALT/ATTACK; the recruit's underlying
     * Neutral/Aggressive stance is never changed.
     */
    private static void registerForcedOrder(ServerPlayer sender, UUID groupId, List<AbstractRecruitEntity> recruits) {
        if (sender == null || groupId == null || recruits == null || recruits.isEmpty()) {
            return;
        }
        Vec3 center = BetterFormationsCompat.formationCenter(groupId);
        if (center == null) {
            center = averageControlledPosition(recruits);
        }
        Vec3 destination = BetterFormationsCompat.formationDestination(groupId);
        if (destination == null) {
            destination = averageCommandDestination(recruits);
        }
        if (center == null || destination == null) {
            return;
        }
        ACTIVE_ORDERS.put(groupId, new ForcedOrderState(
                sender, groupId, List.copyOf(recruits), destination, center, FORCED_ORDER_MAX_TICKS
        ));
    }

    /** Stop forced movement/recovery state when a group is halted or explicitly sent to attack. */
    public static void cancelForcedOrders(Collection<UUID> groupIds) {
        if (groupIds == null) {
            return;
        }
        for (UUID groupId : groupIds) {
            if (groupId != null) {
                ACTIVE_ORDERS.remove(groupId);
                ACTIVE_GROUPS.remove(groupId);
                for (Map.Entry<UUID, BoostState> entry : List.copyOf(ACTIVE.entrySet())) {
                    if (groupId.equals(entry.getValue().groupId)) stop(entry.getKey(), entry.getValue());
                }
                ACTIVE_ROTATIONS.entrySet().removeIf(entry -> groupId.equals(entry.getValue().groupId));
            }
        }
    }

    private static void replaceState(
            AbstractRecruitEntity recruit,
            UUID groupId,
            Mode mode,
            int ticks,
            double runnerFactor
    ) {
        UUID id = recruit.getUUID();
        BoostState old = ACTIVE.remove(id);
        if (old != null) {
            cleanup(old);
        }
        ACTIVE.put(id, new BoostState(recruit, groupId, mode, ticks, runnerFactor, modifierIdFor(id)));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        applyRotationDamping();

        if (!ACTIVE.isEmpty()) {
            for (Map.Entry<UUID, BoostState> entry : List.copyOf(ACTIVE.entrySet())) {
                BoostState state = entry.getValue();
                AbstractRecruitEntity recruit = state.recruit;
                if (recruit == null || !recruit.isAlive() || recruit.isRemoved() || --state.remainingTicks <= 0) {
                    stop(entry.getKey(), state);
                    continue;
                }

                if (!isStillMoving(recruit)) {
                    stop(entry.getKey(), state);
                    continue;
                }

                double navigationSpeed;
                double mountAttributeAmount = 0.0D;
                double selfAttributeAmount = 0.0D;
                switch (state.mode) {
                    case FORMATION_INFANTRY -> navigationSpeed = 1.0D;
                    case FORMATION_CAVALRY -> {
                        navigationSpeed = REGULAR_CAVALRY_NAV_SPEED;
                        mountAttributeAmount = REGULAR_CAVALRY_MOUNT_MULTIPLIER;
                    }
                    case HASTY_INFANTRY -> {
                        navigationSpeed = HASTY_INFANTRY_SPEED * state.runnerFactor;
                        selfAttributeAmount = HASTY_INFANTRY_ATTRIBUTE_MULTIPLIER + (state.runnerFactor - 1.0D);
                    }
                    case HASTY_CAVALRY -> {
                        navigationSpeed = HASTY_CAVALRY_NAV_SPEED * state.runnerFactor;
                        mountAttributeAmount = HASTY_CAVALRY_MOUNT_MULTIPLIER + (state.runnerFactor - 1.0D);
                    }
                    case SLOW_MARCH -> {
                        navigationSpeed = SLOW_MARCH_NAV_SPEED;
                        if (isCavalry(recruit)) {
                            mountAttributeAmount = SLOW_MARCH_ATTRIBUTE_MULTIPLIER;
                        } else {
                            selfAttributeAmount = SLOW_MARCH_ATTRIBUTE_MULTIPLIER;
                        }
                    }
                    default -> navigationSpeed = 1.0D;
                }
                applySpeed(state, navigationSpeed, selfAttributeAmount, mountAttributeAmount);
                if (state.mode == Mode.FORMATION_INFANTRY) {
                    applyArmorWeightInfantryStepDamping(state);
                } else if (state.mode == Mode.HASTY_INFANTRY) {
                    applyHastyInfantryStepVariation(state);
                } else if (state.mode == Mode.SLOW_MARCH) {
                    applySlowMarchStepDamping(state);
                }
                if (state.mode == Mode.FORMATION_CAVALRY || state.mode == Mode.HASTY_CAVALRY
                        || (state.mode == Mode.SLOW_MARCH && isCavalry(recruit))) {
                    maybeRecoverStuckCavalry(state);
                }
            }
        }

        processForcedOrders();

        // Better Formations also ticks on ServerTickEvent.END at normal priority. LOWEST
        // therefore sees the centre after BF has decided whether it advanced normally.
        // Ordinary pace boosts still require real BF progress; only processForcedOrders()
        // above may conservatively break a proven stall for an explicit player RTS order.
        if (!ACTIVE_GROUPS.isEmpty()) {
            for (Map.Entry<UUID, GroupBoostState> entry : List.copyOf(ACTIVE_GROUPS.entrySet())) {
                UUID groupId = entry.getKey();
                GroupBoostState state = entry.getValue();
                if (--state.remainingTicks <= 0) {
                    ACTIVE_GROUPS.remove(groupId, state);
                    continue;
                }
                Vec3 current = BetterFormationsCompat.formationCenter(groupId);
                if (current == null) {
                    ACTIVE_GROUPS.remove(groupId, state);
                    continue;
                }
                double moved = horizontalDistance(current, state.lastCenter);
                if (moved > 0.005D && moved < 0.75D) {
                    if (state.mode == GroupMode.SLOW_MARCH) {
                        double factor = Math.max(0.25D, Math.min(0.75D,
                                SLOW_MARCH_FACTOR * state.externalSpeedScale));
                        Vec3 adjusted = new Vec3(
                                state.lastCenter.x + (current.x - state.lastCenter.x) * factor,
                                current.y,
                                state.lastCenter.z + (current.z - state.lastCenter.z) * factor
                        );
                        if (BetterFormationsCompat.setFormationCenter(groupId, adjusted)) {
                            state.lastCenter = adjusted;
                        } else {
                            state.lastCenter = current;
                        }
                    } else {
                        // Armor Weight v1.1 is a movement-speed MULTIPLY_TOTAL modifier, but
                        // Better Formations advances its group centre directly. Scale the real
                        // centre step first, then scale any Commander-added extra stride too.
                        Vec3 scaledCurrent = current;
                        if (state.externalSpeedScale < 0.999D) {
                            Vec3 adjusted = new Vec3(
                                    state.lastCenter.x + (current.x - state.lastCenter.x) * state.externalSpeedScale,
                                    current.y,
                                    state.lastCenter.z + (current.z - state.lastCenter.z) * state.externalSpeedScale
                            );
                            if (BetterFormationsCompat.setFormationCenter(groupId, adjusted)) {
                                scaledCurrent = adjusted;
                            }
                        }
                        double extra = switch (state.mode) {
                            case FORMATION_INFANTRY -> 0.0D;
                            case FORMATION_CAVALRY -> regularCavalryExtra();
                            case HASTY_INFANTRY -> GROUP_EXTRA_HASTY_INFANTRY;
                            case HASTY_CAVALRY -> hastyCavalryExtra();
                            case SLOW_MARCH -> 0.0D;
                        };
                        extra *= state.externalSpeedScale;
                        Vec3 advanced = extra > 1.0E-6D
                                ? BetterFormationsCompat.advanceFormationCenter(groupId, extra)
                                : scaledCurrent;
                        state.lastCenter = advanced == null ? scaledCurrent : advanced;
                    }
                } else {
                    state.lastCenter = current;
                }
            }
        }
    }

    private static void applyRotationDamping() {
        if (ACTIVE_ROTATIONS.isEmpty()) {
            return;
        }

        for (Map.Entry<UUID, RotationState> entry : List.copyOf(ACTIVE_ROTATIONS.entrySet())) {
            RotationState state = entry.getValue();
            AbstractRecruitEntity recruit = state.recruit;
            if (recruit == null || !recruit.isAlive() || recruit.isRemoved() || --state.remainingTicks <= 0) {
                ACTIVE_ROTATIONS.remove(entry.getKey(), state);
                continue;
            }

            Entity controlled = controlledEntity(recruit);
            Vec3 before = state.beforeFormationStep;
            state.beforeFormationStep = null;
            if (controlled == null || controlled.isRemoved() || before == null) {
                continue;
            }

            Vec3 now = controlled.position();
            double dx = now.x - before.x;
            double dz = now.z - before.z;
            double moved = Math.hypot(dx, dz);
            boolean locked = BetterFormationsCompat.isFormationMemberLocked(state.groupId, recruit.getUUID());
            if (!locked && moved > 0.002D && moved < 0.80D) {
                double rewind = 1.0D - ROTATION_REPOSITION_FACTOR;
                controlled.move(MoverType.SELF, new Vec3(-dx * rewind, 0.0D, -dz * rewind));
                state.stationaryTicks = 0;
            } else {
                state.stationaryTicks++;
            }

            // Keep the rotation state alive until Better Formations has actually settled.
            // Locked final placement is deliberately left exact and is never rewound.
            if (state.stationaryTicks >= 8 && !isStillMoving(recruit)) {
                ACTIVE_ROTATIONS.remove(entry.getKey(), state);
            }
        }

    }

    private static void applyArmorWeightInfantryStepDamping(BoostState state) {
        Vec3 before = state.beforeFormationStep;
        state.beforeFormationStep = null;
        AbstractRecruitEntity recruit = state.recruit;
        if (before == null || recruit == null || !recruit.isAlive() || !recruit.holdFormation) return;
        Entity controlled = controlledEntity(recruit);
        if (controlled == null || controlled.isRemoved()) return;
        double factor = armorWeightScale(controlled);
        if (factor >= 0.999D) return;
        Vec3 now = controlled.position();
        double dx = now.x - before.x;
        double dz = now.z - before.z;
        double moved = Math.hypot(dx, dz);
        if (moved < 0.003D || moved > 0.80D) return;
        double rewind = 1.0D - factor;
        controlled.move(MoverType.SELF, new Vec3(-dx * rewind, 0.0D, -dz * rewind));
    }

    private static void applyHastyInfantryStepVariation(BoostState state) {
        Vec3 before = state.beforeFormationStep;
        state.beforeFormationStep = null;
        AbstractRecruitEntity recruit = state.recruit;
        if (before == null || recruit == null || !recruit.isAlive() || !recruit.holdFormation) {
            return;
        }
        Entity controlled = controlledEntity(recruit);
        if (controlled == null || controlled.isRemoved()) {
            return;
        }

        Vec3 now = controlled.position();
        double dx = now.x - before.x;
        double dz = now.z - before.z;
        double moved = Math.hypot(dx, dz);
        if (moved < 0.003D || moved > 0.80D) {
            return;
        }

        // Scale only the real horizontal BF/member step. A factor below one gently
        // rewinds the recruit; a factor above one adds a collision-resolved stride.
        // The formation engine still owns the slot and continuously reins the runner in.
        double deltaFactor = state.runnerFactor * armorWeightScale(recruit) - 1.0D;
        if (Math.abs(deltaFactor) <= 1.0E-4D) {
            return;
        }
        controlled.move(MoverType.SELF, new Vec3(dx * deltaFactor, 0.0D, dz * deltaFactor));
    }

    private static void applySlowMarchStepDamping(BoostState state) {
        Vec3 before = state.beforeFormationStep;
        state.beforeFormationStep = null;
        AbstractRecruitEntity recruit = state.recruit;
        if (before == null || recruit == null || !recruit.isAlive() || !recruit.holdFormation) {
            return;
        }
        Entity controlled = controlledEntity(recruit);
        if (controlled == null || controlled.isRemoved()) {
            return;
        }

        Vec3 now = controlled.position();
        double dx = now.x - before.x;
        double dz = now.z - before.z;
        double moved = Math.hypot(dx, dz);
        if (moved < 0.003D || moved > 0.80D) {
            return;
        }

        boolean locked = BetterFormationsCompat.isFormationMemberLocked(state.groupId, recruit.getUUID());
        if (!locked) {
            double desiredFactor = SLOW_MARCH_FACTOR * armorWeightScale(controlled);
            double rewind = 1.0D - Math.max(0.01D, desiredFactor);
            controlled.move(MoverType.SELF, new Vec3(-dx * rewind, 0.0D, -dz * rewind));
        }
    }

    private static Entity controlledEntity(AbstractRecruitEntity recruit) {
        return recruit != null && recruit.getVehicle() != null ? recruit.getVehicle() : recruit;
    }

    private static boolean isStillMoving(AbstractRecruitEntity recruit) {
        Vec3 hold = recruit.getHoldPos();
        if (recruit.getShouldHoldPos() && hold != null) {
            EntityPosition position = controlledPosition(recruit);
            double dx = position.x - hold.x;
            double dz = position.z - hold.z;
            return dx * dx + dz * dz > ARRIVAL_DISTANCE_SQ || !position.navigationDone;
        }
        if (recruit.getShouldMovePos() && recruit.getMovePos() != null && !recruit.reachedMovePos) {
            return true;
        }
        return !recruit.getNavigation().isDone();
    }

    private static EntityPosition controlledPosition(AbstractRecruitEntity recruit) {
        if (recruit.getVehicle() instanceof Mob mob) {
            return new EntityPosition(mob.getX(), mob.getZ(), mob.getNavigation().isDone());
        }
        return new EntityPosition(recruit.getX(), recruit.getZ(), recruit.getNavigation().isDone());
    }

    private static void maybeRecoverStuckCavalry(BoostState state) {
        if (!RTSServerConfig.CAVALRY_STUCK_RECOVERY.get()) {
            resetRecovery(state);
            return;
        }
        AbstractRecruitEntity recruit = state.recruit;
        Vec3 slot = recruit.getShouldHoldPos() ? recruit.getHoldPos() : null;
        if (slot == null || !recruit.holdFormation || recruit.getTarget() != null) {
            resetRecovery(state);
            return;
        }

        Entity controlled = recruit.getVehicle() != null ? recruit.getVehicle() : recruit;
        if (controlled == null || controlled.isRemoved() || !(recruit.level() instanceof ServerLevel level)) {
            resetRecovery(state);
            return;
        }

        // A moving slot means the formation itself is still travelling. Recovery is only
        // for the final convergence phase, not a shortcut that lets cavalry warp along a march.
        if (state.lastRecoverySlot == null || horizontalDistance(slot, state.lastRecoverySlot) > 0.05D) {
            state.lastRecoverySlot = slot;
            state.lastSlotDistance = horizontalDistance(controlled.position(), slot);
            state.slotStallTicks = 0;
            return;
        }

        double distance = horizontalDistance(controlled.position(), slot);
        double minimumDistance = RTSServerConfig.CAVALRY_STUCK_RECOVERY_MIN_DISTANCE.get();
        if (distance < minimumDistance) {
            resetRecovery(state);
            state.lastRecoverySlot = slot;
            return;
        }

        if (Double.isFinite(state.lastSlotDistance) && state.lastSlotDistance - distance > 0.08D) {
            state.slotStallTicks = Math.max(0, state.slotStallTicks - 3);
        } else {
            state.slotStallTicks++;
        }
        state.lastSlotDistance = distance;
        state.lastRecoverySlot = slot;

        int delayTicks = RTSServerConfig.CAVALRY_STUCK_RECOVERY_DELAY_SECONDS.get() * 20;
        double maximumDistance = RTSServerConfig.CAVALRY_STUCK_RECOVERY_MAX_DISTANCE.get();
        if (state.slotStallTicks < delayTicks || distance > maximumDistance) {
            return;
        }
        if (!level.hasChunkAt(BlockPos.containing(slot))
                || !clearRecoveryPath(level, controlled, slot)
                || !collisionFreeAt(level, controlled, slot)) {
            // Keep the counter saturated so an obstacle becoming clear can recover quickly,
            // but never use this feature to teleport through that obstacle.
            state.slotStallTicks = delayTicks;
            return;
        }

        controlled.teleportTo(slot.x, slot.y, slot.z);
        controlled.setDeltaMovement(Vec3.ZERO);
        if (controlled instanceof Mob mob) {
            mob.getNavigation().stop();
        }
        recruit.getNavigation().stop();
        state.lastVehiclePosition = controlled.position();
        resetRecovery(state);
        RecruitsRTSCameraMod.LOGGER.debug(
                "Recovered stuck cavalry recruit {} into its RTS formation slot (group {}).",
                recruit.getUUID(),
                state.groupId
        );
    }

    private static boolean clearRecoveryPath(ServerLevel level, Entity entity, Vec3 slot) {
        double eye = Math.max(0.35D, Math.min(1.2D, entity.getBbHeight() * 0.5D));
        Vec3 start = entity.position().add(0.0D, eye, 0.0D);
        Vec3 end = slot.add(0.0D, eye, 0.0D);
        return level.clip(new ClipContext(
                start,
                end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                entity
        )).getType() == HitResult.Type.MISS;
    }

    private static boolean collisionFreeAt(ServerLevel level, Entity entity, Vec3 slot) {
        Vec3 delta = slot.subtract(entity.position());
        AABB destination = entity.getBoundingBox().move(delta).inflate(-0.03D);
        return level.noCollision(entity, destination);
    }

    private static void processForcedOrders() {
        if (ACTIVE_ORDERS.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, ForcedOrderState> entry : List.copyOf(ACTIVE_ORDERS.entrySet())) {
            UUID groupId = entry.getKey();
            ForcedOrderState state = entry.getValue();
            ServerPlayer sender = state.sender;
            if (!RTSServerConfig.FORMATION_ORDER_WATCHDOG.get()
                    || sender == null || sender.isRemoved() || sender.getServer() == null
                    || --state.remainingTicks <= 0) {
                ACTIVE_ORDERS.remove(groupId, state);
                continue;
            }

            List<AbstractRecruitEntity> alive = state.recruits.stream()
                    .filter(r -> r != null && r.isAlive() && !r.isRemoved())
                    .toList();
            if (alive.isEmpty()) {
                ACTIVE_ORDERS.remove(groupId, state);
                continue;
            }

            // Recruits in Aggressive/Neutral mode may reacquire an enemy immediately after
            // the initial command clears targets. During a deliberate RTS manoeuvre the
            // movement order wins; the underlying aggression state itself is untouched.
            for (AbstractRecruitEntity recruit : alive) {
                if (recruit.getTarget() != null) {
                    recruit.setTarget(null);
                }
            }

            Vec3 current = BetterFormationsCompat.formationCenter(groupId);
            Vec3 destination = BetterFormationsCompat.formationDestination(groupId);
            if (destination != null) {
                state.destination = destination;
            }
            if (current == null || state.destination == null) {
                // Custom/native fallback movement has no Better Formations centre to repair.
                // Target suppression still protects the explicit move until normal movement ends.
                boolean anyMoving = alive.stream().anyMatch(RTSMovementBoostManager::isStillMoving);
                if (!anyMoving) {
                    ACTIVE_ORDERS.remove(groupId, state);
                }
                continue;
            }

            double remaining = horizontalDistance(current, state.destination);
            if (remaining <= FORCED_ORDER_ARRIVAL_DISTANCE) {
                boolean anyMoving = alive.stream().anyMatch(RTSMovementBoostManager::isStillMoving);
                if (!anyMoving && ++state.settledTicks >= 8) {
                    ACTIVE_ORDERS.remove(groupId, state);
                } else if (anyMoving) {
                    state.settledTicks = 0;
                }
                state.lastCenter = current;
                continue;
            }
            state.settledTicks = 0;

            double moved = horizontalDistance(current, state.lastCenter);
            if (moved > 0.008D) {
                state.stallTicks = 0;
            } else {
                state.stallTicks++;
            }
            state.lastCenter = current;
            if (state.repairCooldown > 0) {
                state.repairCooldown--;
            }

            int threshold = RTSServerConfig.FORMATION_STALL_RECOVERY_TICKS.get();
            if (state.stallTicks >= threshold) {
                double nudge = RTSServerConfig.FORMATION_STALL_NUDGE_STEP.get() * averageArmorWeightScale(alive);
                Vec3 advanced = BetterFormationsCompat.advanceFormationCenter(groupId, nudge);
                if (advanced != null) {
                    state.lastCenter = advanced;
                }
            }

            int controlled = BetterFormationsCompat.formationMemberCount(groupId);
            boolean missingMembers = controlled >= 0 && controlled < alive.size();
            boolean longStall = state.stallTicks >= threshold * 3;
            if ((missingMembers || longStall) && state.repairCooldown <= 0) {
                // Rebuild from the persisted RTS formation memory. This repairs BF states that
                // dropped members or got wedged during a dense cavalry/melee collision.
                if (FormationService.movePreserved(sender, groupId, alive, state.destination)) {
                    state.repairCooldown = FORCED_ORDER_REPAIR_COOLDOWN;
                    state.stallTicks = 0;
                    Vec3 repaired = BetterFormationsCompat.formationCenter(groupId);
                    if (repaired != null) {
                        state.lastCenter = repaired;
                    }
                    RecruitsRTSCameraMod.LOGGER.debug(
                            "Repaired stalled RTS formation {} (expected {}, BF controlled {}, laggards {}).",
                            groupId, alive.size(), controlled, BetterFormationsCompat.formationLaggards(groupId)
                    );
                }
            }
        }
    }

    private static double averageArmorWeightScale(List<AbstractRecruitEntity> recruits) {
        if (recruits == null || recruits.isEmpty()) {
            return 1.0D;
        }
        double total = 0.0D;
        int count = 0;
        for (AbstractRecruitEntity recruit : recruits) {
            if (recruit == null || !recruit.isAlive()) {
                continue;
            }
            Entity controlled = controlledEntity(recruit);
            if (controlled != null) {
                total += armorWeightScale(controlled);
                count++;
            }
        }
        return count <= 0 ? 1.0D : total / count;
    }

    private static double armorWeightScale(Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return 1.0D;
        }
        AttributeInstance movement = living.getAttribute(Attributes.MOVEMENT_SPEED);
        if (movement == null) {
            return 1.0D;
        }
        AttributeModifier modifier = movement.getModifier(ARMOR_WEIGHT_MODIFIER_ID);
        if (modifier == null) {
            return 1.0D;
        }
        // Armor Weight v1.1 uses MULTIPLY_TOTAL with a negative value. Keep this generic
        // enough that a compatible datapack using the same modifier UUID still behaves.
        if (modifier.getOperation() == AttributeModifier.Operation.MULTIPLY_TOTAL) {
            return Math.max(0.01D, Math.min(1.0D, 1.0D + modifier.getAmount()));
        }
        return 1.0D;
    }


    private static Vec3 averageControlledPosition(List<AbstractRecruitEntity> recruits) {
        if (recruits == null || recruits.isEmpty()) {
            return null;
        }
        double x = 0.0D, y = 0.0D, z = 0.0D;
        int count = 0;
        for (AbstractRecruitEntity recruit : recruits) {
            if (recruit == null || !recruit.isAlive() || recruit.isRemoved()) continue;
            Entity controlled = controlledEntity(recruit);
            if (controlled == null || controlled.isRemoved()) continue;
            Vec3 pos = controlled.position();
            x += pos.x; y += pos.y; z += pos.z; count++;
        }
        return count <= 0 ? null : new Vec3(x / count, y / count, z / count);
    }

    private static Vec3 averageCommandDestination(List<AbstractRecruitEntity> recruits) {
        if (recruits == null || recruits.isEmpty()) {
            return null;
        }
        double x = 0.0D, y = 0.0D, z = 0.0D;
        int count = 0;
        for (AbstractRecruitEntity recruit : recruits) {
            if (recruit == null || !recruit.isAlive() || recruit.isRemoved()) continue;
            Vec3 target = null;
            if (recruit.getShouldHoldPos() && recruit.getHoldPos() != null) {
                target = recruit.getHoldPos();
            } else if (recruit.getShouldMovePos() && recruit.getMovePos() != null) {
                BlockPos pos = recruit.getMovePos();
                target = Vec3.atBottomCenterOf(pos);
            }
            if (target == null) continue;
            x += target.x; y += target.y; z += target.z; count++;
        }
        return count <= 0 ? null : new Vec3(x / count, y / count, z / count);
    }

    private static void resetRecovery(BoostState state) {
        state.slotStallTicks = 0;
        state.lastSlotDistance = Double.NaN;
        state.lastRecoverySlot = null;
    }

    private static void applySpeed(BoostState state, double speed, double selfAttributeAmount, double mountAttributeAmount) {
        AbstractRecruitEntity recruit = state.recruit;
        recruit.moveSpeed = (float) speed;
        recruit.getNavigation().setSpeedModifier(speed);
        applyAttributeModifier(recruit, state.modifierId, selfAttributeAmount);

        LivingEntity currentVehicle = recruit.getVehicle() instanceof LivingEntity living ? living : null;
        if (state.boostedVehicle != null && state.boostedVehicle != currentVehicle) {
            removeMountModifier(state.boostedVehicle, state.modifierId);
            state.boostedVehicle = null;
            state.lastVehiclePosition = null;
        }

        if (currentVehicle instanceof Mob mob) {
            mob.getNavigation().setSpeedModifier(speed);
        }
        if (currentVehicle != null && Math.abs(mountAttributeAmount) > 1.0E-6D) {
            applyAttributeModifier(currentVehicle, state.modifierId, mountAttributeAmount);
            state.boostedVehicle = currentVehicle;
        }

        if (currentVehicle instanceof Mob vehicle && isCavalry(recruit)) {
            double armorScale = armorWeightScale(vehicle);
            applyCavalryArmorWeightDamping(state, vehicle, armorScale);
            double memberPace = state.mode == Mode.HASTY_CAVALRY
                    ? RTSServerConfig.CAVALRY_HASTY_SPEED.get() * 1.2D * state.runnerFactor
                    : RTSServerConfig.CAVALRY_FORMATION_SPEED.get() * 1.2D;
            if (state.mode != Mode.SLOW_MARCH) {
                applyMountedStrideAssist(state, vehicle, memberPace * armorScale);
            } else {
                state.lastVehiclePosition = null;
            }
        } else {
            state.lastVehiclePosition = null;
        }
    }

    private static void applyCavalryArmorWeightDamping(BoostState state, Mob vehicle, double armorScale) {
        Vec3 before = state.beforeFormationStep;
        state.beforeFormationStep = null;
        if (before == null || armorScale >= 0.999D) {
            return;
        }
        Vec3 now = vehicle.position();
        double dx = now.x - before.x;
        double dz = now.z - before.z;
        double moved = Math.hypot(dx, dz);
        if (moved < 0.003D || moved > 0.80D) {
            return;
        }
        // Armor Weight's MULTIPLY_TOTAL modifier normally affects navigation/attributes,
        // but Better Formations glides mounted entities directly. Rewind the ignored part
        // of that direct step so the datapack remains meaningful in RTS formations too.
        double rewind = 1.0D - armorScale;
        vehicle.move(MoverType.SELF, new Vec3(-dx * rewind, 0.0D, -dz * rewind));
    }

    /**
     * Better Formations directly moves a mounted group's vehicle by a maximum 0.3 blocks
     * each tick, bypassing navigation speed. Measure that real movement and add one more
     * collision-resolved stride in the same direction. This is intentionally based on
     * observed movement, so a horse that BF could not move through a wall gets no boost.
     */
    private static void applyMountedStrideAssist(BoostState state, Mob vehicle, double memberPace) {
        Vec3 now = vehicle.position();
        Vec3 previous = state.lastVehiclePosition;
        if (previous == null) {
            state.lastVehiclePosition = now;
            return;
        }

        double dx = now.x - previous.x;
        double dz = now.z - previous.z;
        double moved = Math.hypot(dx, dz);
        if (moved < 0.01D || moved > 2.5D) {
            state.lastVehiclePosition = now;
            return;
        }

        // Top up the actual step to the configured pace, with no recursive multiplication
        // of native navigation plus BF movement. A horse already fast enough gets no push.
        double extraDistance = Math.min(moved * 2.0D, Math.max(0.0D, memberPace - moved));
        Vec3 hold = state.recruit.getShouldHoldPos() ? state.recruit.getHoldPos() : null;
        if (hold != null) {
            double remaining = Math.hypot(hold.x - now.x, hold.z - now.z);
            extraDistance = Math.min(extraDistance, Math.max(0.0D, remaining - 0.18D));
        } else if (state.recruit.getShouldMovePos() && state.recruit.getMovePos() != null) {
            BlockPos target = state.recruit.getMovePos();
            double remaining = Math.hypot(target.getX() + 0.5D - now.x, target.getZ() + 0.5D - now.z);
            extraDistance = Math.min(extraDistance, Math.max(0.0D, remaining - 0.18D));
        }

        if (extraDistance > 1.0E-4D) {
            double scale = extraDistance / moved;
            vehicle.move(MoverType.SELF, new Vec3(dx * scale, 0.0D, dz * scale));
        }
        state.lastVehiclePosition = vehicle.position();
    }

    private static void stop(UUID id, BoostState state) {
        if (!ACTIVE.remove(id, state)) {
            return;
        }
        cleanup(state);
    }

    private static void cleanup(BoostState state) {
        AbstractRecruitEntity recruit = state.recruit;
        if (recruit != null && !recruit.isRemoved()) {
            recruit.moveSpeed = 1.0F;
            recruit.getNavigation().setSpeedModifier(1.0D);
            removeAttributeModifier(recruit, state.modifierId);
            if (recruit.getVehicle() instanceof Mob vehicle) {
                vehicle.getNavigation().setSpeedModifier(1.0D);
            }
        }
        if (state.boostedVehicle != null) {
            removeMountModifier(state.boostedVehicle, state.modifierId);
            state.boostedVehicle = null;
        }
        state.lastVehiclePosition = null;
    }

    private static void applyAttributeModifier(LivingEntity entity, UUID modifierId, double amount) {
        if (entity == null || entity.isRemoved()) {
            return;
        }
        AttributeInstance movement = entity.getAttribute(Attributes.MOVEMENT_SPEED);
        if (movement == null) {
            return;
        }
        AttributeModifier existing = movement.getModifier(modifierId);
        if (existing != null) {
            movement.removeModifier(modifierId);
        }
        if (Math.abs(amount) > 1.0E-5D) {
            movement.addTransientModifier(new AttributeModifier(
                    modifierId,
                    "Recruits RTS Camera temporary pace",
                    amount,
                    AttributeModifier.Operation.MULTIPLY_TOTAL
            ));
        }
    }

    private static void removeAttributeModifier(LivingEntity entity, UUID modifierId) {
        if (entity == null || entity.isRemoved()) {
            return;
        }
        AttributeInstance movement = entity.getAttribute(Attributes.MOVEMENT_SPEED);
        if (movement != null && movement.getModifier(modifierId) != null) {
            movement.removeModifier(modifierId);
        }
    }

    private static void removeMountModifier(LivingEntity vehicle, UUID modifierId) {
        removeAttributeModifier(vehicle, modifierId);
    }

    private static double hastyRunnerFactor(UUID id) {
        long mixed = id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 23);
        mixed ^= (mixed >>> 33);
        mixed *= 0xff51afd7ed558ccdl;
        mixed ^= (mixed >>> 33);
        int bucket = (int) Math.floorMod(mixed, 1001L);
        double unit = bucket / 1000.0D;
        return 1.0D + (unit * 2.0D - 1.0D) * HASTY_VARIATION;
    }

    private static UUID modifierIdFor(UUID recruitId) {
        return UUID.nameUUIDFromBytes(
                ("recruits_rts_camera:cavalry:" + recruitId).getBytes(StandardCharsets.UTF_8)
        );
    }

    public static boolean isHastyActive(UUID groupId) {
        if (groupId == null) return false;
        GroupBoostState state = ACTIVE_GROUPS.get(groupId);
        return state != null && (state.mode == GroupMode.HASTY_INFANTRY || state.mode == GroupMode.HASTY_CAVALRY);
    }

    public static boolean isSlowMarchActive(UUID groupId) {
        if (groupId == null) return false;
        GroupBoostState state = ACTIVE_GROUPS.get(groupId);
        return state != null && state.mode == GroupMode.SLOW_MARCH;
    }

    public static boolean isCavalry(AbstractRecruitEntity recruit) {
        return recruit instanceof HorsemanEntity
                || recruit instanceof NomadEntity
                || (recruit.getVehicle() != null && !SmallShipsCompat.isShipRecruit(recruit));
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        if (a == null || b == null) {
            return 0.0D;
        }
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    private enum Mode {
        FORMATION_INFANTRY,
        FORMATION_CAVALRY,
        HASTY_INFANTRY,
        HASTY_CAVALRY,
        SLOW_MARCH
    }

    private enum GroupMode {
        FORMATION_INFANTRY,
        FORMATION_CAVALRY,
        HASTY_INFANTRY,
        HASTY_CAVALRY,
        SLOW_MARCH
    }

    private static final class BoostState {
        private final AbstractRecruitEntity recruit;
        private final UUID groupId;
        private final Mode mode;
        private final double runnerFactor;
        private final UUID modifierId;
        private int remainingTicks;
        private LivingEntity boostedVehicle;
        private Vec3 lastVehiclePosition;
        private Vec3 beforeFormationStep;
        private Vec3 lastRecoverySlot;
        private double lastSlotDistance = Double.NaN;
        private int slotStallTicks;

        private BoostState(
                AbstractRecruitEntity recruit,
                UUID groupId,
                Mode mode,
                int remainingTicks,
                double runnerFactor,
                UUID modifierId
        ) {
            this.recruit = recruit;
            this.groupId = groupId;
            this.mode = mode;
            this.remainingTicks = remainingTicks;
            this.runnerFactor = runnerFactor;
            this.modifierId = modifierId;
        }
    }

    private static final class GroupBoostState {
        private final GroupMode mode;
        private int remainingTicks;
        private Vec3 lastCenter;
        private final double externalSpeedScale;

        private GroupBoostState(GroupMode mode, int remainingTicks, Vec3 lastCenter, double externalSpeedScale) {
            this.mode = mode;
            this.remainingTicks = remainingTicks;
            this.lastCenter = lastCenter;
            this.externalSpeedScale = Math.max(0.01D, Math.min(1.0D, externalSpeedScale));
        }
    }

    private static final class ForcedOrderState {
        private final ServerPlayer sender;
        private final UUID groupId;
        private final List<AbstractRecruitEntity> recruits;
        private Vec3 destination;
        private Vec3 lastCenter;
        private int remainingTicks;
        private int stallTicks;
        private int settledTicks;
        private int repairCooldown;

        private ForcedOrderState(
                ServerPlayer sender, UUID groupId, List<AbstractRecruitEntity> recruits,
                Vec3 destination, Vec3 lastCenter, int remainingTicks
        ) {
            this.sender = sender;
            this.groupId = groupId;
            this.recruits = recruits;
            this.destination = destination;
            this.lastCenter = lastCenter;
            this.remainingTicks = remainingTicks;
        }
    }

    private static final class RotationState {
        private final AbstractRecruitEntity recruit;
        private final UUID groupId;
        private int remainingTicks;
        private int stationaryTicks;
        private Vec3 beforeFormationStep;

        private RotationState(AbstractRecruitEntity recruit, UUID groupId, int remainingTicks) {
            this.recruit = recruit;
            this.groupId = groupId;
            this.remainingTicks = remainingTicks;
        }
    }

    private record EntityPosition(double x, double z, boolean navigationDone) {
    }
}
