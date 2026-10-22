package ee.lastpagans.recruitsrtscamera.combat;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.formation.FormationRuntime;
import ee.lastpagans.recruitsrtscamera.movement.RTSMovementBoostManager;
import ee.lastpagans.recruitsrtscamera.persistence.RTSGroupTacticsSavedData;
import ee.lastpagans.recruitsrtscamera.registry.RTSSounds;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitRuntimeRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Emits one positional hoof rumble per moving cavalry group instead of one sound per horse. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CavalryRumbleManager {
    private static final int REPLAY_TICKS = 304;
    private static final Map<UUID, Vec3> LAST_MOUNT_POSITION = new HashMap<>();
    private static final Map<UUID, Long> NEXT_PLAY = new HashMap<>();

    private CavalryRumbleManager() { }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        for (ServerLevel level : event.getServer().getAllLevels()) update(level);
    }

    private static void update(ServerLevel level) {
        Map<UUID, GroupSample> groups = new HashMap<>();
        for (AbstractRecruitEntity recruit : RecruitRuntimeRegistry.loaded(level)) {
            UUID groupId = recruit.getGroup();
            Entity mount = recruit.getVehicle();
            if (groupId == null || mount == null || !RTSMovementBoostManager.isCavalry(recruit)) continue;
            Vec3 now = mount.position();
            Vec3 previous = LAST_MOUNT_POSITION.put(mount.getUUID(), now);
            double speed = previous == null ? 0.0D : horizontalDistance(previous, now);
            groups.computeIfAbsent(groupId, ignored -> new GroupSample()).add(now, speed);
        }

        long now = level.getGameTime();
        for (Map.Entry<UUID, GroupSample> entry : groups.entrySet()) {
            UUID groupId = entry.getKey();
            GroupSample sample = entry.getValue();
            double speed = sample.averageSpeed();
            if (speed < 0.025D || now < NEXT_PLAY.getOrDefault(groupId, 0L)) continue;
            boolean wedge = "CAVALRY_WEDGE".equals(FormationRuntime.currentShape(groupId));
            boolean charging = RTSGroupTacticsSavedData.get(level).timer(groupId, "chargeUntil") > now;
            float volume = volume(sample.riders, speed, wedge, charging);
            if (volume <= 0.0F) continue;
            Vec3 center = sample.center();
            level.playSound(null, center.x, center.y, center.z, RTSSounds.CAVALRY_RUMBLE.get(),
                    SoundSource.NEUTRAL, volume, 0.96F + level.random.nextFloat() * 0.08F);
            NEXT_PLAY.put(groupId, now + REPLAY_TICKS);
        }
    }

    /** Riders x actual speed x charge/wedge factors, scaled to Minecraft's volume range. */
    static float volume(int riders, double speed, boolean wedge, boolean charging) {
        double emphasis = (wedge ? 1.15D : 1.0D) * (charging ? 1.15D : 1.0D);
        return (float) Math.max(0.0D, Math.min(1.5D, riders * speed * emphasis / 12.0D));
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    @SubscribeEvent
    public static void onStop(ServerStoppedEvent event) {
        LAST_MOUNT_POSITION.clear();
        NEXT_PLAY.clear();
    }

    private static final class GroupSample {
        int riders;
        double x;
        double y;
        double z;
        double speed;

        void add(Vec3 position, double actualSpeed) {
            riders++;
            x += position.x;
            y += position.y;
            z += position.z;
            speed += Math.min(1.0D, actualSpeed);
        }

        double averageSpeed() { return riders == 0 ? 0.0D : speed / riders; }
        Vec3 center() { return new Vec3(x / riders, y / riders, z / riders); }
    }
}
