package ee.lastpagans.recruitsrtscamera.compat;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.formation.FormationKind;
import ee.lastpagans.recruitsrtscamera.formation.FormationPlan;
import ee.lastpagans.recruitsrtscamera.registry.RTSTags;
import ee.lastpagans.recruitsrtscamera.server.BodyguardManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Optional Better Formations 1.0.3 executor.
 *
 * RTS Camera's planner is intentionally engine-neutral. When a plan carries explicit
 * facing or LINE-rank information (frontage or a preserved frontage move), this bridge
 * temporarily supplies those values to Better Formations, asks it to build/own the
 * formation, then restores the player's own preferences immediately. No preset/frontage
 * recruit slots are assigned by RTS Camera.
 */
public final class BetterFormationsCompat {
    private static final String MOD_ID = "betterformations";
    private static final String CONTROLLER = "me.puredoom.betterformations.manager.FormationController";
    private static final String SHAPE = CONTROLLER + "$Shape";
    private static final String PREFERENCES = "me.puredoom.betterformations.data.FormationPreferences";

    private static boolean initialized;
    private static boolean usable;
    private static boolean failureLogged;
    private static boolean pacingFailureLogged;
    private static Class<?> shapeClass;
    private static Method getShape;
    private static Method layout;
    private static Method releaseForAttack;
    private static Method getLineRanks;
    private static Method setLineRanks;
    private static Field statesField;
    private static Field stateCurrentCenterField;
    private static Field stateDestCenterField;
    private static Field stateHeadingField;
    private static Field stateLockedField;
    private static Field stateOffsetsField;
    private static Field stateLaggardsField;
    private static Field stateGroundSlotsField;
    private static Method cachedIdealMethod;
    private static Method cachedTickMethod;
    private static Constructor<?> cachedGroundSlotConstructor;
    private static Constructor<?> offsetConstructor;
    private static Method offsetRightMethod;
    private static Method offsetBackMethod;
    private static final Map<UUID, CommanderReservation> COMMANDER_RESERVATIONS = new HashMap<>();

    private BetterFormationsCompat() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    /** Returns the current Better Formations shape name, or null when unavailable. */
    public static String currentShape(UUID groupId) {
        if (!ensureReady() || groupId == null) {
            return null;
        }
        try {
            Object shape = getShape.invoke(null, groupId);
            return shape instanceof Enum<?> value ? value.name() : (shape == null ? null : String.valueOf(shape));
        } catch (ReflectiveOperationException | LinkageError ex) {
            disableAfterFailure("shape query", ex);
            return null;
        }
    }

    /** Execute one engine-neutral plan using Better Formations. */
    public static boolean executePlan(
            ServerPlayer sender,
            List<AbstractRecruitEntity> recruits,
            FormationPlan plan
    ) {
        if (!ensureReady() || sender == null || recruits == null || recruits.isEmpty() || plan == null) {
            return false;
        }

        boolean overrideLineRanks = plan.intent().kind() == FormationKind.LINE
                && plan.intent().ranks() > 0;
        boolean overrideFacing = Float.isFinite(plan.intent().facingYaw());
        float originalYaw = sender.getYRot();
        Integer originalRanks = null;

        try {
            if (overrideLineRanks) {
                originalRanks = (Integer) getLineRanks.invoke(null, sender);
                int ranks = Math.max(1, Math.min(20, plan.intent().ranks()));
                setLineRanks.invoke(null, sender, ranks);
            }
            if (overrideFacing) {
                sender.setYRot(plan.intent().facingYaw());
            }

            Object shape = shapeClass.getMethod("valueOf", String.class)
                    .invoke(null, plan.intent().kind().name());
            layout.invoke(
                    null,
                    shape,
                    sender,
                    recruits,
                    plan.destination(),
                    plan.intent().spacing(),
                    plan.intent().hold()
            );
            preferMoraleBearerSlots(plan.groupId(), recruits);
            if (BodyguardManager.shouldReserveCommanderSlot(sender, plan.groupId())) {
                // Reserve the commander's synthetic slot immediately after every BF rebuild so
                // the player is part of the layout from the first movement tick rather than
                // chasing an already-moving formation.
                commanderSlot(sender.serverLevel(), sender, plan.groupId());
            }
            return true;
        } catch (ReflectiveOperationException | IllegalArgumentException | LinkageError ex) {
            disableAfterFailure("plan execution", ex);
            return false;
        } finally {
            if (overrideFacing) {
                sender.setYRot(originalYaw);
            }
            if (overrideLineRanks && originalRanks != null) {
                try {
                    setLineRanks.invoke(null, sender, originalRanks.intValue());
                } catch (ReflectiveOperationException | LinkageError ex) {
                    disableAfterFailure("line-rank preference restore", ex);
                }
            }
        }
    }

    /**
     * Returns Better Formations' live centre for one group. 0.6 uses this only for
     * movement pacing: Better Formations advances every formation centre by a fixed
     * infantry-sized 0.2 blocks/tick, even when the members are mounted.
     */
    public static net.minecraft.world.phys.Vec3 formationCenter(UUID groupId) {
        Object state = stateFor(groupId);
        if (state == null || stateCurrentCenterField == null) {
            return null;
        }
        try {
            Object value = stateCurrentCenterField.get(state);
            return value instanceof net.minecraft.world.phys.Vec3 vec ? vec : null;
        } catch (IllegalAccessException | LinkageError ex) {
            pacingFailure("formation-centre query", ex);
            return null;
        }
    }

    /**
     * Advance only the Better Formations group centre toward its existing destination.
     * Member slots remain entirely owned by Better Formations; this merely removes the
     * hardcoded infantry pace from cavalry formations.
     */
    /**
     * Set Better Formations' live group centre directly. Used only by deliberate RTS pace
     * modes (currently Slow March) after Better Formations has advanced the centre for a tick.
     * This never touches member offsets or destination state.
     */
    public static boolean setFormationCenter(UUID groupId, net.minecraft.world.phys.Vec3 center) {
        if (groupId == null || center == null) {
            return false;
        }
        Object state = stateFor(groupId);
        if (state == null || stateCurrentCenterField == null) {
            return false;
        }
        try {
            stateCurrentCenterField.set(state, center);
            return true;
        } catch (IllegalAccessException | LinkageError ex) {
            pacingFailure("formation-centre set", ex);
            return false;
        }
    }

    /** Translate both the live and destination formation centres by the same amount. */
    public static boolean translateFormation(UUID groupId, net.minecraft.world.phys.Vec3 delta) {
        if (groupId == null || delta == null || delta.lengthSqr() < 1.0E-10D) return false;
        Object state = stateFor(groupId);
        if (state == null || stateCurrentCenterField == null || stateDestCenterField == null) return false;
        try {
            Object currentObj = stateCurrentCenterField.get(state);
            Object destObj = stateDestCenterField.get(state);
            if (!(currentObj instanceof net.minecraft.world.phys.Vec3 current)
                    || !(destObj instanceof net.minecraft.world.phys.Vec3 dest)) return false;
            stateCurrentCenterField.set(state, current.add(delta));
            stateDestCenterField.set(state, dest.add(delta));
            return true;
        } catch (IllegalAccessException | LinkageError ex) {
            pacingFailure("formation translation", ex);
            return false;
        }
    }

    public static net.minecraft.world.phys.Vec3 advanceFormationCenter(UUID groupId, double extraStep) {
        if (extraStep <= 0.0D) {
            return formationCenter(groupId);
        }
        Object state = stateFor(groupId);
        if (state == null || stateCurrentCenterField == null || stateDestCenterField == null) {
            return null;
        }
        try {
            Object currentObj = stateCurrentCenterField.get(state);
            Object destObj = stateDestCenterField.get(state);
            if (!(currentObj instanceof net.minecraft.world.phys.Vec3 current)
                    || !(destObj instanceof net.minecraft.world.phys.Vec3 dest)) {
                return null;
            }
            double dx = dest.x - current.x;
            double dz = dest.z - current.z;
            double distance = Math.hypot(dx, dz);
            if (distance < 0.08D) {
                return current;
            }
            double step = Math.min(extraStep, Math.max(0.0D, distance - 0.05D));
            if (step <= 1.0E-6D) {
                return current;
            }
            net.minecraft.world.phys.Vec3 advanced = new net.minecraft.world.phys.Vec3(
                    current.x + dx / distance * step,
                    current.y,
                    current.z + dz / distance * step
            );
            stateCurrentCenterField.set(state, advanced);
            return advanced;
        } catch (IllegalAccessException | LinkageError ex) {
            pacingFailure("formation-centre pacing", ex);
            return null;
        }
    }

    /** Current Better Formations destination centre, or null when no live formation state exists. */
    public static net.minecraft.world.phys.Vec3 formationDestination(UUID groupId) {
        Object state = stateFor(groupId);
        if (state == null || stateDestCenterField == null) {
            return null;
        }
        try {
            Object value = stateDestCenterField.get(state);
            return value instanceof net.minecraft.world.phys.Vec3 vec ? vec : null;
        } catch (IllegalAccessException | LinkageError ex) {
            pacingFailure("formation-destination query", ex);
            return null;
        }
    }

    /** Number of recruit UUIDs currently owned by Better Formations for this group. */
    public static int formationMemberCount(UUID groupId) {
        Object state = stateFor(groupId);
        if (state == null || stateOffsetsField == null) {
            return -1;
        }
        try {
            Object value = stateOffsetsField.get(state);
            return value instanceof Map<?, ?> map ? map.size() : -1;
        } catch (IllegalAccessException | LinkageError ex) {
            pacingFailure("formation-member query", ex);
            return -1;
        }
    }

    /** Better Formations' current laggard count, useful for the RTS debug/watchdog path. */
    public static int formationLaggards(UUID groupId) {
        Object state = stateFor(groupId);
        if (state == null || stateLaggardsField == null) {
            return -1;
        }
        try {
            return stateLaggardsField.getInt(state);
        } catch (IllegalAccessException | LinkageError ex) {
            pacingFailure("formation-laggard query", ex);
            return -1;
        }
    }

    /**
     * True when Better Formations has already locked this recruit into its final slot.
     * Rotation damping must never rewind a locked recruit, because Better Formations may
     * intentionally settle that last short step with an exact set-position operation.
     * If the private pacing bridge is unavailable, return true (safe/no damping).
     */
    public static boolean isFormationMemberLocked(UUID groupId, UUID recruitId) {
        if (groupId == null || recruitId == null || !ensureReady() || stateLockedField == null) {
            return true;
        }
        Object state = stateFor(groupId);
        if (state == null) {
            return true;
        }
        try {
            Object value = stateLockedField.get(state);
            return !(value instanceof java.util.Set<?> locked) || locked.contains(recruitId);
        } catch (IllegalAccessException | LinkageError ex) {
            pacingFailure("formation locked-member query", ex);
            return true;
        }
    }

    /**
     * Repair Better Formations' cached grounded slots using the real collision shape. This is
     * intentionally reflection-only so Better Formations stays optional, and it fixes fractional
     * or otherwise non-heightmap terrain (paths, modded soils, earthen clay, sandy grass, etc.)
     * without hardcoding block IDs.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void repairGroundSlots(net.minecraft.server.level.ServerLevel level, UUID groupId,
                                         List<AbstractRecruitEntity> recruits) {
        if (level == null || groupId == null || recruits == null || recruits.isEmpty()
                || !ensureReady() || stateGroundSlotsField == null || cachedGroundSlotConstructor == null
                || cachedIdealMethod == null || cachedTickMethod == null) {
            return;
        }
        Object state = stateFor(groupId);
        if (state == null) return;
        try {
            Object raw = stateGroundSlotsField.get(state);
            if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) return;
            AbstractRecruitEntity fallback = recruits.get(0);
            Map writable = (Map) raw;
            for (Object entryObj : List.copyOf(map.entrySet())) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) entryObj;
                Object cached = entry.getValue();
                if (cached == null) continue;
                Object idealObj = cachedIdealMethod.invoke(cached);
                if (!(idealObj instanceof net.minecraft.world.phys.Vec3 ideal)) continue;
                AbstractRecruitEntity sample = fallback;
                if (entry.getKey() instanceof UUID id) {
                    for (AbstractRecruitEntity recruit : recruits) {
                        if (recruit != null && id.equals(recruit.getUUID())) { sample = recruit; break; }
                    }
                }
                net.minecraft.world.phys.Vec3 corrected = TerrainSurfaceResolver.resolve(level, sample, ideal);
                if (Math.abs(corrected.y - ideal.y) > 1.0E-4D) {
                    Object replacement = cachedGroundSlotConstructor.newInstance(ideal, corrected, level.getGameTime());
                    writable.put(entry.getKey(), replacement);
                }
            }
        } catch (ReflectiveOperationException | LinkageError ex) {
            pacingFailure("terrain ground-slot repair", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object stateFor(UUID groupId) {
        if (!ensureReady() || groupId == null || statesField == null) {
            return null;
        }
        try {
            Object value = statesField.get(null);
            if (!(value instanceof Map<?, ?> states)) {
                return null;
            }
            return states.get(groupId);
        } catch (IllegalAccessException | LinkageError ex) {
            pacingFailure("formation-state access", ex);
            return null;
        }
    }



    /**
     * Standards and eagles are high-value morale objects. When Better Formations has just built
     * a preset/frontage layout, swap each loaded morale bearer into the safest available rear-
     * centre slot instead of leaving him on an exposed wing/front rank. We swap existing BF
     * Offset objects rather than inventing coordinates, so every shape keeps its native geometry.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void preferMoraleBearerSlots(UUID groupId, List<AbstractRecruitEntity> recruits) {
        if (groupId == null || recruits == null || recruits.size() < 2 || !ensureReady()
                || stateOffsetsField == null || offsetRightMethod == null || offsetBackMethod == null) {
            return;
        }
        Object state = stateFor(groupId);
        if (state == null) return;
        try {
            Object raw = stateOffsetsField.get(state);
            if (!(raw instanceof Map<?, ?> map) || map.size() < 2) return;
            Map writable = (Map) raw;

            java.util.Set<UUID> bearerIds = new java.util.HashSet<>();
            for (AbstractRecruitEntity recruit : recruits) {
                if (recruit != null && recruit.isAlive()
                        && (recruit.getMainHandItem().is(RTSTags.MORALE_ITEMS)
                        || recruit.getOffhandItem().is(RTSTags.MORALE_ITEMS))) {
                    bearerIds.add(recruit.getUUID());
                }
            }
            if (bearerIds.isEmpty()) return;

            java.util.Set<UUID> alreadyReserved = new java.util.HashSet<>();
            for (UUID bearerId : bearerIds) {
                Object bearerOffset = writable.get(bearerId);
                if (bearerOffset == null) continue;
                double bearerRight = ((Number) offsetRightMethod.invoke(bearerOffset)).doubleValue();
                double bearerBack = ((Number) offsetBackMethod.invoke(bearerOffset)).doubleValue();
                double bearerScore = bearerBack * 10.0D - Math.abs(bearerRight);
                UUID safestId = null;
                double safestScore = bearerScore;
                for (Object entryObj : map.entrySet()) {
                    Map.Entry<?, ?> entry = (Map.Entry<?, ?>) entryObj;
                    if (!(entry.getKey() instanceof UUID candidateId) || bearerIds.contains(candidateId)
                            || alreadyReserved.contains(candidateId) || entry.getValue() == null) {
                        continue;
                    }
                    double right = ((Number) offsetRightMethod.invoke(entry.getValue())).doubleValue();
                    double back = ((Number) offsetBackMethod.invoke(entry.getValue())).doubleValue();
                    // Rear depth is the dominant safety property; centre-line proximity breaks ties.
                    double score = back * 10.0D - Math.abs(right);
                    if (score > safestScore + 1.0E-4D) {
                        safestScore = score;
                        safestId = candidateId;
                    }
                }
                if (safestId != null) {
                    Object safeOffset = writable.get(safestId);
                    writable.put(bearerId, safeOffset);
                    writable.put(safestId, bearerOffset);
                    alreadyReserved.add(safestId);
                }
            }
        } catch (ReflectiveOperationException | LinkageError ex) {
            pacingFailure("morale-bearer protected-slot assignment", ex);
        }
    }


    /** Current Better Formations heading in degrees, used by the synthetic commander slot. */
    public static Float formationHeading(UUID groupId) {
        Object state = stateFor(groupId);
        if (state == null || stateHeadingField == null) return null;
        try {
            return stateHeadingField.getFloat(state);
        } catch (IllegalAccessException | LinkageError ex) {
            pacingFailure("formation-heading query", ex);
            return null;
        }
    }

    /**
     * Return the world-space slot occupied by the real player while commanding a Bodyguard.
     * Better Formations cannot safely accept a ServerPlayer UUID in its recruit offset map: its
     * tick path expects Mob/Recruit instances. Instead we move one real recruit from the safest
     * rear-centre slot to a newly-created rear-rank slot, leaving a genuine hole in the geometry.
     * That hole is the player's synthetic formation slot until RTS mode ends or the layout rebuilds.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static synchronized net.minecraft.world.phys.Vec3 commanderSlot(
            ServerLevel level, ServerPlayer player, UUID groupId
    ) {
        if (level == null || player == null || groupId == null || !ensureReady()
                || stateOffsetsField == null || stateCurrentCenterField == null
                || stateHeadingField == null || offsetConstructor == null
                || offsetRightMethod == null || offsetBackMethod == null) {
            return null;
        }
        Object state = stateFor(groupId);
        if (state == null) {
            COMMANDER_RESERVATIONS.remove(groupId);
            return null;
        }
        try {
            Object raw = stateOffsetsField.get(state);
            if (!(raw instanceof Map<?, ?> offsets) || offsets.isEmpty()) return null;
            Map writable = (Map) raw;

            CommanderReservation reservation = COMMANDER_RESERVATIONS.get(groupId);
            boolean valid = reservation != null
                    && reservation.state == state
                    && reservation.offsetsMap == raw
                    && writable.containsKey(reservation.displacedRecruit)
                    && reservation.displacedOffset.equals(writable.get(reservation.displacedRecruit));
            if (!valid) {
                reservation = reserveCommanderHole(state, raw, writable, moraleBearerIds(level, groupId));
                if (reservation == null) return null;
                COMMANDER_RESERVATIONS.put(groupId, reservation);
            }

            Object centerObj = stateCurrentCenterField.get(state);
            if (!(centerObj instanceof net.minecraft.world.phys.Vec3 center)) return null;
            float heading = stateHeadingField.getFloat(state);
            double angle = Math.toRadians(heading);
            net.minecraft.world.phys.Vec3 forward = new net.minecraft.world.phys.Vec3(
                    -Math.sin(angle), 0.0D, Math.cos(angle));
            net.minecraft.world.phys.Vec3 right = new net.minecraft.world.phys.Vec3(
                    forward.z, 0.0D, -forward.x);
            double slotRight = ((Number) offsetRightMethod.invoke(reservation.commanderOffset)).doubleValue();
            double slotBack = ((Number) offsetBackMethod.invoke(reservation.commanderOffset)).doubleValue();
            net.minecraft.world.phys.Vec3 ideal = center
                    .add(right.scale(slotRight))
                    .add(forward.scale(-slotBack));
            return TerrainSurfaceResolver.resolve(level, player, ideal);
        } catch (ReflectiveOperationException | LinkageError ex) {
            pacingFailure("synthetic commander slot", ex);
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CommanderReservation reserveCommanderHole(
            Object state, Object raw, Map writable, Set<UUID> protectedRecruitIds
    ) throws ReflectiveOperationException {
        UUID candidateId = null;
        Object candidateOffset = null;
        double spacing = estimateOffsetSpacing(writable);

        // Reserve a genuinely central command position, not merely the furthest-back slot.
        // First find the formation's rear depth; then only consider the rear third and strongly
        // prefer |right| ~= 0. This keeps the player inside the body of line/cavalry formations.
        double maxBack = -Double.MAX_VALUE;
        double minBack = Double.MAX_VALUE;
        for (Object value : writable.values()) {
            if (value == null) continue;
            double back = ((Number) offsetBackMethod.invoke(value)).doubleValue();
            maxBack = Math.max(maxBack, back);
            minBack = Math.min(minBack, back);
        }
        double rearBand = Double.isFinite(maxBack) && Double.isFinite(minBack)
                ? maxBack - Math.max(0.35D, (maxBack - minBack) * 0.34D)
                : -Double.MAX_VALUE;
        double bestScore = Double.MAX_VALUE;
        for (Object entryObj : writable.entrySet()) {
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) entryObj;
            if (!(entry.getKey() instanceof UUID id) || entry.getValue() == null
                    || protectedRecruitIds.contains(id)) continue;
            double right = ((Number) offsetRightMethod.invoke(entry.getValue())).doubleValue();
            double back = ((Number) offsetBackMethod.invoke(entry.getValue())).doubleValue();
            if (back + 1.0E-4D < rearBand) continue;
            double score = Math.abs(right) * 12.0D + Math.max(0.0D, maxBack - back);
            if (score < bestScore) {
                bestScore = score;
                candidateId = id;
                candidateOffset = entry.getValue();
            }
        }
        // Unusual/radial layouts can have no clear rear band. Fall back to the most central
        // non-standard slot rather than choosing an arbitrary convenient edge.
        if (candidateId == null) {
            for (Object entryObj : writable.entrySet()) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) entryObj;
                if (!(entry.getKey() instanceof UUID id) || entry.getValue() == null
                        || protectedRecruitIds.contains(id)) continue;
                double right = ((Number) offsetRightMethod.invoke(entry.getValue())).doubleValue();
                double back = ((Number) offsetBackMethod.invoke(entry.getValue())).doubleValue();
                double score = Math.abs(right) * 12.0D + Math.abs(back);
                if (score < bestScore) {
                    bestScore = score;
                    candidateId = id;
                    candidateOffset = entry.getValue();
                }
            }
        }
        if (candidateId == null || candidateOffset == null) return null;

        double candidateBack = ((Number) offsetBackMethod.invoke(candidateOffset)).doubleValue();
        double minRight = Double.MAX_VALUE;
        double maxRight = -Double.MAX_VALUE;
        for (Object value : writable.values()) {
            if (value == null) continue;
            double right = ((Number) offsetRightMethod.invoke(value)).doubleValue();
            double back = ((Number) offsetBackMethod.invoke(value)).doubleValue();
            if (Math.abs(back - candidateBack) <= Math.max(0.20D, spacing * 0.38D)) {
                minRight = Math.min(minRight, right);
                maxRight = Math.max(maxRight, right);
            }
        }
        if (!Double.isFinite(minRight) || !Double.isFinite(maxRight)) {
            minRight = -spacing * 0.5D;
            maxRight = spacing * 0.5D;
        }

        boolean extendRight = Math.abs(maxRight) <= Math.abs(minRight);
        double replacementRight = extendRight ? maxRight + spacing : minRight - spacing;
        // Do not accidentally place the displaced bodyguard on top of another unusual/radial slot.
        for (int attempts = 0; attempts < 8 && offsetTooClose(writable, replacementRight, candidateBack, spacing); attempts++) {
            replacementRight += extendRight ? spacing : -spacing;
        }
        Object displacedOffset = offsetConstructor.newInstance(replacementRight, candidateBack);
        writable.put(candidateId, displacedOffset);

        if (stateLockedField != null) {
            Object lockedRaw = stateLockedField.get(state);
            if (lockedRaw instanceof Set<?> set) ((Set) set).remove(candidateId);
        }
        if (stateGroundSlotsField != null) {
            Object groundsRaw = stateGroundSlotsField.get(state);
            if (groundsRaw instanceof Map<?, ?> map) ((Map) map).remove(candidateId);
        }
        return new CommanderReservation(state, raw, candidateId, candidateOffset, displacedOffset);
    }


    private static Set<UUID> moraleBearerIds(ServerLevel level, UUID groupId) {
        if (level == null || groupId == null) return Set.of();
        java.util.HashSet<UUID> ids = new java.util.HashSet<>();
        for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
            if (entity instanceof AbstractRecruitEntity recruit && recruit.isAlive()
                    && groupId.equals(recruit.getGroup())
                    && (recruit.getMainHandItem().is(RTSTags.MORALE_ITEMS)
                    || recruit.getOffhandItem().is(RTSTags.MORALE_ITEMS))) {
                ids.add(recruit.getUUID());
            }
        }
        return ids;
    }

    private static double estimateOffsetSpacing(Map<?, ?> offsets) throws ReflectiveOperationException {
        java.util.ArrayList<double[]> points = new java.util.ArrayList<>();
        for (Object value : offsets.values()) {
            if (value == null) continue;
            points.add(new double[]{
                    ((Number) offsetRightMethod.invoke(value)).doubleValue(),
                    ((Number) offsetBackMethod.invoke(value)).doubleValue()
            });
        }
        double best = Double.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            for (int j = i + 1; j < points.size(); j++) {
                double dx = points.get(i)[0] - points.get(j)[0];
                double dz = points.get(i)[1] - points.get(j)[1];
                double distance = Math.hypot(dx, dz);
                if (distance > 0.20D && distance < best) best = distance;
            }
        }
        if (!Double.isFinite(best) || best == Double.MAX_VALUE) best = 1.25D;
        return Math.max(0.75D, Math.min(2.50D, best));
    }

    private static boolean offsetTooClose(Map<?, ?> offsets, double right, double back, double spacing)
            throws ReflectiveOperationException {
        double minSq = Math.pow(Math.max(0.45D, spacing * 0.60D), 2.0D);
        for (Object value : offsets.values()) {
            if (value == null) continue;
            double dx = right - ((Number) offsetRightMethod.invoke(value)).doubleValue();
            double dz = back - ((Number) offsetBackMethod.invoke(value)).doubleValue();
            if (dx * dx + dz * dz < minSq) return true;
        }
        return false;
    }

    /** Restore the recruit displaced to make room for the real commander, when safe to do so. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static synchronized void clearCommanderReservation(UUID groupId) {
        if (groupId == null) return;
        CommanderReservation reservation = COMMANDER_RESERVATIONS.remove(groupId);
        if (reservation == null) return;
        try {
            if (reservation.offsetsMap instanceof Map<?, ?> raw) {
                Map writable = (Map) raw;
                Object current = writable.get(reservation.displacedRecruit);
                if (reservation.displacedOffset.equals(current)) {
                    writable.put(reservation.displacedRecruit, reservation.commanderOffset);
                }
            }
            if (reservation.state != null && stateLockedField != null) {
                Object lockedRaw = stateLockedField.get(reservation.state);
                if (lockedRaw instanceof Set<?> set) ((Set) set).remove(reservation.displacedRecruit);
            }
            if (reservation.state != null && stateGroundSlotsField != null) {
                Object groundRaw = stateGroundSlotsField.get(reservation.state);
                if (groundRaw instanceof Map<?, ?> map) ((Map) map).remove(reservation.displacedRecruit);
            }
        } catch (IllegalAccessException | LinkageError ex) {
            pacingFailure("commander-slot restore", ex);
        }
    }

    private static final class CommanderReservation {
        final Object state;
        final Object offsetsMap;
        final UUID displacedRecruit;
        final Object commanderOffset;
        final Object displacedOffset;

        CommanderReservation(Object state, Object offsetsMap, UUID displacedRecruit,
                             Object commanderOffset, Object displacedOffset) {
            this.state = state;
            this.offsetsMap = offsetsMap;
            this.displacedRecruit = displacedRecruit;
            this.commanderOffset = commanderOffset;
            this.displacedOffset = displacedOffset;
        }
    }

    /** Stop Better Formations from continuing to advance a group after an RTS HALT order. */
    @SuppressWarnings("unchecked")
    public static void haltFormation(UUID groupId) {
        clearCommanderReservation(groupId);
        if (!ensureReady() || groupId == null || statesField == null) {
            return;
        }
        try {
            Object value = statesField.get(null);
            if (value instanceof Map<?, ?> states) {
                ((Map<UUID, Object>) states).remove(groupId);
            }
        } catch (IllegalAccessException | LinkageError ex) {
            pacingFailure("formation halt", ex);
        }
    }

    /**
     * Reproduce Better Formations' normal attack cleanup so formation state does not
     * pull units back into slots while they are attacking. RTS Camera keeps its own
     * preferred shape separately, so the next move can restore it.
     */
    public static void releaseForAttack(ServerPlayer sender, UUID groupId) {
        if (!ensureReady() || sender == null || groupId == null || sender.getServer() == null) {
            return;
        }
        try {
            MinecraftServer server = sender.getServer();
            releaseForAttack.invoke(null, server, groupId, sender.getUUID());
        } catch (ReflectiveOperationException | LinkageError ex) {
            disableAfterFailure("attack integration", ex);
        }
    }

    private static synchronized boolean ensureReady() {
        if (initialized) {
            return usable;
        }
        initialized = true;

        if (!isLoaded()) {
            usable = false;
            return false;
        }

        try {
            Class<?> controllerClass = Class.forName(CONTROLLER);
            Class<?> preferencesClass = Class.forName(PREFERENCES);
            shapeClass = Class.forName(SHAPE);
            getShape = controllerClass.getMethod("getShape", UUID.class);
            layout = controllerClass.getMethod(
                    "layout",
                    shapeClass,
                    ServerPlayer.class,
                    List.class,
                    net.minecraft.world.phys.Vec3.class,
                    double.class,
                    boolean.class
            );
            releaseForAttack = controllerClass.getMethod(
                    "releaseForAttack",
                    MinecraftServer.class,
                    UUID.class,
                    UUID.class
            );
            getLineRanks = preferencesClass.getMethod("getLineRanks", net.minecraft.world.entity.player.Player.class);
            setLineRanks = preferencesClass.getMethod("setLineRanks", net.minecraft.world.entity.player.Player.class, int.class);

            // The centre-pacing bridge is intentionally optional even when Better
            // Formations itself is usable. If a future BF version changes private
            // runtime-state field names, preset/frontage integration still works; only
            // the cavalry centre acceleration falls back to ordinary BF pace.
            try {
                Class<?> stateClass = Class.forName(CONTROLLER + "$FormationState");
                statesField = controllerClass.getDeclaredField("STATES");
                statesField.setAccessible(true);
                stateCurrentCenterField = stateClass.getDeclaredField("currentCenter");
                stateCurrentCenterField.setAccessible(true);
                stateDestCenterField = stateClass.getDeclaredField("destCenter");
                stateDestCenterField.setAccessible(true);
                stateHeadingField = stateClass.getDeclaredField("heading");
                stateHeadingField.setAccessible(true);
                stateLockedField = stateClass.getDeclaredField("locked");
                stateLockedField.setAccessible(true);
                stateOffsetsField = stateClass.getDeclaredField("offsets");
                stateOffsetsField.setAccessible(true);
                stateLaggardsField = stateClass.getDeclaredField("laggards");
                stateLaggardsField.setAccessible(true);
                stateGroundSlotsField = stateClass.getDeclaredField("groundSlots");
                stateGroundSlotsField.setAccessible(true);
                Class<?> cachedGroundSlotClass = Class.forName(CONTROLLER + "$CachedGroundSlot");
                cachedIdealMethod = cachedGroundSlotClass.getDeclaredMethod("ideal");
                cachedIdealMethod.setAccessible(true);
                cachedTickMethod = cachedGroundSlotClass.getDeclaredMethod("tick");
                cachedTickMethod.setAccessible(true);
                cachedGroundSlotConstructor = cachedGroundSlotClass.getDeclaredConstructor(
                        net.minecraft.world.phys.Vec3.class, net.minecraft.world.phys.Vec3.class, long.class);
                cachedGroundSlotConstructor.setAccessible(true);
                Class<?> offsetClass = Class.forName(CONTROLLER + "$Offset");
                offsetConstructor = offsetClass.getDeclaredConstructor(double.class, double.class);
                offsetConstructor.setAccessible(true);
                offsetRightMethod = offsetClass.getDeclaredMethod("right");
                offsetRightMethod.setAccessible(true);
                offsetBackMethod = offsetClass.getDeclaredMethod("back");
                offsetBackMethod.setAccessible(true);
            } catch (ReflectiveOperationException | LinkageError pacingError) {
                statesField = null;
                stateCurrentCenterField = null;
                stateDestCenterField = null;
                stateHeadingField = null;
                stateLockedField = null;
                stateOffsetsField = null;
                stateLaggardsField = null;
                stateGroundSlotsField = null;
                cachedIdealMethod = null;
                cachedTickMethod = null;
                cachedGroundSlotConstructor = null;
                offsetConstructor = null;
                offsetRightMethod = null;
                offsetBackMethod = null;
                pacingFailure("private pacing bridge initialization", pacingError);
            }

            usable = true;
            RecruitsRTSCameraMod.LOGGER.info("Better Formations plan executor enabled (preset + preserved facing + frontage + cavalry/rotation pacing bridge).");
            return true;
        } catch (ReflectiveOperationException | LinkageError ex) {
            usable = false;
            disableAfterFailure("initialization", ex);
            return false;
        }
    }

    private static void pacingFailure(String phase, Throwable error) {
        if (!pacingFailureLogged) {
            pacingFailureLogged = true;
            RecruitsRTSCameraMod.LOGGER.warn(
                    "Better Formations {} unavailable; RTS formations still work, but advanced cavalry/rotation pacing falls back to Better Formations defaults.",
                    phase,
                    error
            );
        }
    }

    private static void disableAfterFailure(String phase, Throwable error) {
        usable = false;
        if (!failureLogged) {
            failureLogged = true;
            RecruitsRTSCameraMod.LOGGER.error(
                    "Better Formations {} failed; falling back to native Recruits movement.",
                    phase,
                    error
            );
        }
    }
}
