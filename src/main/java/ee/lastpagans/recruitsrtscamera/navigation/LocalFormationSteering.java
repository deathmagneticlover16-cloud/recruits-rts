package ee.lastpagans.recruitsrtscamera.navigation;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitSpatialIndex;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitRuntimeMetrics;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/** Cheap damped slot correction and grid-backed friendly separation for unobstructed movement. */
public final class LocalFormationSteering {
    private static final double SEPARATION_RADIUS = 0.82D;

    private LocalFormationSteering() {
    }

    public static boolean steer(
            ServerLevel level,
            AbstractRecruitEntity recruit,
            Mob mover,
            Vec3 desired,
            Vec3 slotVelocity,
            double catchUpScale
    ) {
        RecruitSpatialIndex.track(recruit);
        RecruitRuntimeMetrics.LOCAL_STEERING_UPDATES.increment();
        Vec3 position = mover.position();
        Vec3 error = desired.subtract(position);
        Vec3 current = mover.getDeltaMovement();
        Vec3 formationVelocity = slotVelocity == null
                ? Vec3.ZERO : new Vec3(slotVelocity.x, 0.0D, slotVelocity.z);
        double slotDistance = Math.hypot(error.x, error.z);
        double safeScale = Math.max(1.0D, Math.min(1.25D, catchUpScale));
        if (recruit.getVehicle() == mover) {
            return steerMounted(level, mover, desired, position, error, current,
                    formationVelocity, safeScale);
        }
        Vec3 requested = new Vec3(error.x * 0.34D, 0.0D, error.z * 0.34D)
                .add(formationVelocity)
                .subtract(new Vec3(current.x * 0.08D, 0.0D, current.z * 0.08D))
                .add(separation(recruit));
        double maximum = 0.18D * safeScale;
        double horizontal = Math.hypot(requested.x, requested.z);
        if (horizontal > maximum) requested = requested.scale(maximum / horizontal);
        Vec3 probe = position.add(requested.scale(2.5D));
        Vec3 safe = TacticalPathfinder.standableAt(level, mover, probe);
        // Never fall back to an unchecked desired point. A moving formation can shift
        // its slot after the staggered direct-route probe; stopping for one update is
        // preferable to letting MoveControl step into newly unsupported space.
        if (safe == null) {
            if (desired.y > mover.getY() + 0.45D) mover.getJumpControl().jump();
            mover.getNavigation().moveTo(desired.x, desired.y, desired.z, 0.78D * safeScale);
            return false;
        }
        // The probe only proves the next part of the route. A farther look-ahead keeps
        // MoveControl from being reset to a point under half a block away every tick.
        double lookAhead = Math.min(1.75D, slotDistance);
        Vec3 target = lookAhead <= 1.0E-5D ? safe
                : position.add(error.x / Math.hypot(error.x, error.z) * lookAhead,
                safe.y - position.y, error.z / Math.hypot(error.x, error.z) * lookAhead);
        // Foot MoveControl needs an explicit jump request for a supported rise.
        if (safe.y > mover.getY() + 0.45D
                && horizontalDistanceSq(position, safe) < 1.60D * 1.60D) {
            mover.getJumpControl().jump();
        }
        mover.getMoveControl().setWantedPosition(target.x, target.y, target.z,
                0.78D * safeScale);
        return true;
    }

    /**
     * One velocity owner for an RTS horse on verified open ground. MoveControl targets a
     * lateral slot and also rotates toward it, which fights the formation heading and causes
     * the familiar left/right shiver. This controller keeps the route velocity continuous and
     * applies a small, damped sideways correction without changing the horse's facing.
     */
    private static boolean steerMounted(
            ServerLevel level,
            Mob mover,
            Vec3 desired,
            Vec3 position,
            Vec3 error,
            Vec3 current,
            Vec3 formationVelocity,
            double safeScale
    ) {
        MountedFormationMotion.Vector motion = MountedFormationMotion.velocity(
                current.x, current.z, error.x, error.z,
                formationVelocity.x, formationVelocity.z, safeScale
        );
        Vec3 requested = new Vec3(motion.x(), 0.0D, motion.z());
        double probeDistance = Math.max(1.25D, requested.length() * 5.0D);
        Vec3 probe = requested.length() > 1.0E-5D
                ? position.add(requested.normalize().scale(probeDistance)) : position;
        if (!TacticalPathfinder.canSteerDirect(level, mover, probe)) {
            mover.getNavigation().moveTo(desired.x, desired.y, desired.z, 0.86D * safeScale);
            return false;
        }
        Vec3 safe = TacticalPathfinder.standableAt(level, mover, probe);
        if (safe == null) {
            mover.getNavigation().moveTo(desired.x, desired.y, desired.z, 0.86D * safeScale);
            return false;
        }

        if (safe.y > mover.getY() + 0.45D
                && horizontalDistanceSq(position, safe) < 3.0D * 3.0D) {
            mover.getJumpControl().jump();
            if (mover.onGround()) {
                mover.setDeltaMovement(current.x, Math.max(current.y, 0.42D), current.z);
            }
        }
        // Neutralize stale MoveControl without asking it to choose another yaw.
        mover.getMoveControl().setWantedPosition(mover.getX(), mover.getY(), mover.getZ(), 0.0D);
        mover.setDeltaMovement(requested.x, mover.getDeltaMovement().y, requested.z);
        mover.hasImpulse = true;
        return true;
    }

    private static double horizontalDistanceSq(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private static Vec3 separation(AbstractRecruitEntity recruit) {
        Vec3 result = Vec3.ZERO;
        for (AbstractRecruitEntity neighbor : RecruitSpatialIndex.nearbyGroup(recruit, SEPARATION_RADIUS)) {
            Vec3 delta = recruit.position().subtract(neighbor.position());
            double distanceSq = delta.x * delta.x + delta.z * delta.z;
            if (distanceSq < 1.0E-5D || distanceSq >= SEPARATION_RADIUS * SEPARATION_RADIUS) continue;
            double distance = Math.sqrt(distanceSq);
            double strength = (SEPARATION_RADIUS - distance) / SEPARATION_RADIUS * 0.055D;
            result = result.add(delta.x / distance * strength, 0.0D, delta.z / distance * strength);
        }
        return result;
    }
}
