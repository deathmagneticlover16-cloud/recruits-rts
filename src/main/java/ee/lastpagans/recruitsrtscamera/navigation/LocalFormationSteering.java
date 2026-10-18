package ee.lastpagans.recruitsrtscamera.navigation;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitSpatialIndex;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitRuntimeMetrics;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/** Cheap damped slot correction and grid-backed friendly separation for unobstructed movement. */
public final class LocalFormationSteering {
    private static final double SEPARATION_RADIUS = 1.35D;

    private LocalFormationSteering() {
    }

    public static void steer(
            ServerLevel level,
            AbstractRecruitEntity recruit,
            Mob mover,
            Vec3 desired,
            Vec3 slotVelocity
    ) {
        RecruitSpatialIndex.track(recruit);
        RecruitRuntimeMetrics.LOCAL_STEERING_UPDATES.increment();
        Vec3 position = mover.position();
        Vec3 error = desired.subtract(position);
        Vec3 current = mover.getDeltaMovement();
        Vec3 requested = new Vec3(error.x * 0.28D, 0.0D, error.z * 0.28D)
                .add(slotVelocity == null ? Vec3.ZERO : new Vec3(slotVelocity.x, 0.0D, slotVelocity.z))
                .subtract(new Vec3(current.x * 0.18D, 0.0D, current.z * 0.18D))
                .add(separation(recruit));
        double maximum = recruit.getVehicle() == null ? 0.22D : 0.28D;
        double horizontal = Math.hypot(requested.x, requested.z);
        if (horizontal > maximum) requested = requested.scale(maximum / horizontal);

        Vec3 probe = position.add(requested.scale(2.0D));
        Vec3 safe = TacticalPathfinder.standableAt(level, mover, probe);
        mover.getNavigation().stop();
        // Never fall back to an unchecked desired point. A moving formation can shift
        // its slot after the staggered direct-route probe; stopping for one update is
        // preferable to letting MoveControl step into newly unsupported space.
        if (safe == null) return;
        mover.getMoveControl().setWantedPosition(safe.x, safe.y, safe.z,
                recruit.getVehicle() == null ? 1.05D : 1.0D);
    }

    private static Vec3 separation(AbstractRecruitEntity recruit) {
        Vec3 result = Vec3.ZERO;
        for (AbstractRecruitEntity neighbor : RecruitSpatialIndex.nearbyGroup(recruit, SEPARATION_RADIUS)) {
            Vec3 delta = recruit.position().subtract(neighbor.position());
            double distanceSq = delta.x * delta.x + delta.z * delta.z;
            if (distanceSq < 1.0E-5D || distanceSq >= SEPARATION_RADIUS * SEPARATION_RADIUS) continue;
            double distance = Math.sqrt(distanceSq);
            double strength = (SEPARATION_RADIUS - distance) / SEPARATION_RADIUS * 0.16D;
            result = result.add(delta.x / distance * strength, 0.0D, delta.z / distance * strength);
        }
        return result;
    }
}
