package ee.lastpagans.recruitsrtscamera.client;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.config.RTSClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ViewportEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Smooth formation-scale hoofbeat vibration applied to the active game camera. */
public final class CavalryCameraShakeClient {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final double MAX_DISTANCE = 96.0D;
    private static float intensity;
    private static float targetIntensity;

    private CavalryCameraShakeClient() { }

    public static void tick() {
        if (MC.level == null || MC.gameRenderer == null || !RTSClientConfig.CAVALRY_CAMERA_SHAKE.get()) {
            targetIntensity = 0.0F;
            intensity *= 0.82F;
            return;
        }
        Vec3 camera = MC.gameRenderer.getMainCamera().getPosition();
        Map<UUID, Sample> groups = new HashMap<>();
        for (LivingEntity entity : RTSCameraController.commanderLoadedLivingEntities()) {
            if (!(entity instanceof AbstractRecruitEntity recruit) || recruit.getGroup() == null
                    || !(recruit.getVehicle() instanceof LivingEntity mount)) continue;
            groups.computeIfAbsent(recruit.getGroup(), ignored -> new Sample()).add(mount);
        }

        double combined = 0.0D;
        for (Sample sample : groups.values()) {
            if (sample.riders == 0 || sample.movingRiders * 3 < sample.riders) continue;
            Vec3 center = sample.center();
            double distance = Math.sqrt(center.distanceToSqr(camera));
            if (distance >= MAX_DISTANCE) continue;
            // Exponential group scaling is normalized so seventeen nearby moving horses
            // produce one full unit of shake, then capped before huge armies become nausea.
            double formation = Math.expm1(sample.riders / 17.0D) / Math.expm1(1.0D);
            formation = Math.min(4.0D, formation);
            double speed = sample.speed / Math.max(1, sample.movingRiders);
            // A march should read as weight in the ground rather than constant camera
            // vibration. Shake rises smoothly once the formation enters a real run.
            double gait = Mth.clamp((speed - 0.065D) / 0.20D, 0.025D, 1.20D);
            double range = 1.0D - distance / MAX_DISTANCE;
            combined += formation * gait * range * range;
        }
        targetIntensity = (float) Math.min(4.0D, combined);
        float smoothing = targetIntensity > intensity ? 0.18F : 0.10F;
        intensity += (targetIntensity - intensity) * smoothing;
    }

    public static void apply(ViewportEvent.ComputeCameraAngles event) {
        if (intensity < 0.001F || !RTSClientConfig.CAVALRY_CAMERA_SHAKE.get() || MC.level == null) return;
        double phase = (MC.level.getGameTime() + event.getPartialTick()) * 0.92D;
        float strength = intensity * RTSClientConfig.CAVALRY_CAMERA_SHAKE_INTENSITY.get().floatValue();
        event.setPitch(event.getPitch() + (float) Math.sin(phase * 1.91D) * 0.17F * strength);
        event.setYaw(event.getYaw() + (float) Math.sin(phase * 1.37D + 1.2D) * 0.10F * strength);
        event.setRoll(event.getRoll() + (float) Math.sin(phase * 2.23D + 0.4D) * 0.08F * strength);
    }

    public static void reset() {
        intensity = 0.0F;
        targetIntensity = 0.0F;
    }

    private static final class Sample {
        int riders;
        int movingRiders;
        double x;
        double y;
        double z;
        double speed;

        void add(LivingEntity mount) {
            riders++;
            x += mount.getX();
            y += mount.getY();
            z += mount.getZ();
            Vec3 motion = mount.getDeltaMovement();
            double horizontalSpeed = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
            if (horizontalSpeed > 0.025D) {
                movingRiders++;
                speed += horizontalSpeed;
            }
        }

        Vec3 center() {
            return new Vec3(x / riders, y / riders, z / riders);
        }
    }
}
