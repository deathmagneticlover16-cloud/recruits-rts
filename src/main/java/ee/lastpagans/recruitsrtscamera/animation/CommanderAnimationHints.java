package ee.lastpagans.recruitsrtscamera.animation;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tiny optional bridge for the separate Serious Player Animations/Recruits addon.
 *
 * <p>No animation mod is required. Commander writes semantic hints into Recruit persistent
 * data with a short expiry. An animation addon can read these keys without depending on RTS
 * Camera internals, and RTS Camera remains fully functional when no animation addon exists.</p>
 */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CommanderAnimationHints {
    public static final String HINT_KEY = "RecruitsRTSAnimHint";
    public static final String PRIORITY_KEY = "RecruitsRTSAnimHintPriority";
    public static final String UNTIL_KEY = "RecruitsRTSAnimHintUntil";
    public static final String MOVEMENT_KEY = "RecruitsRTSMovementState";
    public static final String WALKING_KEY = "RecruitsRTSWalking";
    public static final String RUNNING_KEY = "RecruitsRTSRunning";
    private static final int IDLE_HYSTERESIS_TICKS = 6;
    private static final Map<UUID, MovementSample> MOVEMENT = new HashMap<>();

    private CommanderAnimationHints() {
    }

    public enum Hint {
        STANDARD_BEARER(0),
        WINDED(1),
        TIRED(1),
        EXHAUSTED(2),
        WAVERING(3),
        SPEAR_THROW(4),
        ROUTING(5);

        private final int priority;

        Hint(int priority) {
            this.priority = priority;
        }

        public int priority() {
            return priority;
        }
    }

    /** Stable semantic state for optional animation integrations. */
    public enum MovementState { IDLE, WALKING, RUNNING }

    /**
     * Publish movement intent with a short idle delay. A one-tick path handoff therefore
     * cannot reset a custom walk/run animation. Persistent data changes only on a state
     * transition; consumers may read the enum API or the documented NBT keys.
     */
    public static void updateMovement(
            AbstractRecruitEntity recruit, boolean movementCommanded, boolean runningCommanded
    ) {
        if (recruit == null || recruit.level().isClientSide) return;
        Entity mover = recruit.getVehicle() == null ? recruit : recruit.getVehicle();
        Vec3 velocity = mover.getDeltaMovement();
        boolean physicallyMoving = velocity.x * velocity.x + velocity.z * velocity.z > 0.0025D;
        long now = recruit.level().getGameTime();
        MovementSample sample = MOVEMENT.computeIfAbsent(recruit.getUUID(),
                ignored -> new MovementSample(persistedMovement(recruit), now));
        MovementState next = sample.state;
        if (movementCommanded || physicallyMoving) {
            sample.lastMovingAt = now;
            next = runningCommanded ? MovementState.RUNNING : MovementState.WALKING;
        } else if (now - sample.lastMovingAt >= IDLE_HYSTERESIS_TICKS) {
            next = MovementState.IDLE;
        }
        if (next != sample.state || !next.name().equals(recruit.getPersistentData().getString(MOVEMENT_KEY))) {
            sample.state = next;
            publishMovement(recruit, next);
        }
        synchronizeSprintState(recruit, next);
    }

    public static MovementState movementState(AbstractRecruitEntity recruit) {
        if (recruit == null) return MovementState.IDLE;
        MovementSample sample = MOVEMENT.get(recruit.getUUID());
        if (sample != null) return sample.state;
        return persistedMovement(recruit);
    }

    public static void clearMovement(AbstractRecruitEntity recruit) {
        if (recruit == null) return;
        MOVEMENT.remove(recruit.getUUID());
        if (!recruit.level().isClientSide) publishMovement(recruit, MovementState.IDLE);
    }

    private static void publishMovement(AbstractRecruitEntity recruit, MovementState state) {
        recruit.getPersistentData().putString(MOVEMENT_KEY, state.name());
        recruit.getPersistentData().putBoolean(WALKING_KEY, state == MovementState.WALKING);
        recruit.getPersistentData().putBoolean(RUNNING_KEY, state == MovementState.RUNNING);
        // SPA and other animation packs commonly use the vanilla sprint flag as their
        // inexpensive run-state signal. Keep it aligned with the public semantic state.
        synchronizeSprintState(recruit, state);
    }

    private static void synchronizeSprintState(AbstractRecruitEntity recruit, MovementState state) {
        boolean running = state == MovementState.RUNNING;
        if (recruit.isSprinting() != running) recruit.setSprinting(running);
        if (recruit.getVehicle() != null && recruit.getVehicle().isSprinting() != running) {
            recruit.getVehicle().setSprinting(running);
        }
    }

    private static MovementState persistedMovement(AbstractRecruitEntity recruit) {
        try {
            return MovementState.valueOf(recruit.getPersistentData().getString(MOVEMENT_KEY));
        } catch (IllegalArgumentException ignored) {
            return MovementState.IDLE;
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        MOVEMENT.clear();
    }

    public static void offer(AbstractRecruitEntity recruit, Hint hint, int durationTicks) {
        if (recruit == null || hint == null || recruit.level().isClientSide) return;
        long now = recruit.level().getGameTime();
        long until = recruit.getPersistentData().getLong(UNTIL_KEY);
        int currentPriority = recruit.getPersistentData().getInt(PRIORITY_KEY);
        if (until > now && currentPriority > hint.priority()) return;
        recruit.getPersistentData().putString(HINT_KEY, hint.name());
        recruit.getPersistentData().putInt(PRIORITY_KEY, hint.priority());
        recruit.getPersistentData().putLong(UNTIL_KEY, now + Math.max(1, durationTicks));
    }

    public static String current(AbstractRecruitEntity recruit) {
        if (recruit == null) return "";
        long now = recruit.level().getGameTime();
        if (recruit.getPersistentData().getLong(UNTIL_KEY) <= now) return "";
        return recruit.getPersistentData().getString(HINT_KEY);
    }

    private static final class MovementSample {
        MovementState state;
        long lastMovingAt;

        MovementSample(MovementState state, long now) {
            this.state = state;
            this.lastMovingAt = now;
        }
    }
}
