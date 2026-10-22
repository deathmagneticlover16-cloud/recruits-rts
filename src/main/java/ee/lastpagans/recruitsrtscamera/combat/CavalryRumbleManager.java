package ee.lastpagans.recruitsrtscamera.combat;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.formation.FormationRuntime;
import ee.lastpagans.recruitsrtscamera.movement.RTSMovementBoostManager;
import ee.lastpagans.recruitsrtscamera.network.CavalryRumblePacket;
import ee.lastpagans.recruitsrtscamera.network.RTSCameraNetwork;
import ee.lastpagans.recruitsrtscamera.persistence.RTSGroupTacticsSavedData;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitRuntimeRegistry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Samples mounted groups cheaply and drives client-side positional loops with explicit stops. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CavalryRumbleManager {
    private static final int SAMPLE_TICKS = 5;
    private static final double HEARING_RANGE_SQ = 256.0D * 256.0D;
    private static final Map<UUID, MountSample> LAST_MOUNT_SAMPLE = new HashMap<>();
    private static final Map<UUID, AudibleGroup> AUDIBLE_GROUPS = new HashMap<>();

    private CavalryRumbleManager() { }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().overworld() == null) return;
        long now = event.getServer().overworld().getGameTime();
        if (Math.floorMod(now, SAMPLE_TICKS) != 0L) return;

        Map<UUID, AudibleGroup> current = new HashMap<>();
        for (ServerLevel level : event.getServer().getAllLevels()) sampleLevel(level, current);

        for (Map.Entry<UUID, AudibleGroup> entry : AUDIBLE_GROUPS.entrySet()) {
            if (current.containsKey(entry.getKey())) continue;
            ServerLevel level = event.getServer().getLevel(entry.getValue().levelKey());
            if (level != null) sendStop(level, entry.getKey(), entry.getValue().center());
        }
        AUDIBLE_GROUPS.clear();
        AUDIBLE_GROUPS.putAll(current);
    }

    private static void sampleLevel(ServerLevel level, Map<UUID, AudibleGroup> current) {
        Map<UUID, GroupSample> groups = new HashMap<>();
        long now = level.getGameTime();
        for (AbstractRecruitEntity recruit : RecruitRuntimeRegistry.loaded(level)) {
            UUID groupId = recruit.getGroup();
            Entity mount = recruit.getVehicle();
            if (groupId == null || mount == null || !RTSMovementBoostManager.isCavalry(recruit)) continue;
            Vec3 position = mount.position();
            MountSample previous = LAST_MOUNT_SAMPLE.put(mount.getUUID(), new MountSample(position, now));
            long elapsed = previous == null ? 0L : Math.max(1L, now - previous.tick());
            double speed = previous == null ? 0.0D : horizontalDistance(previous.position(), position) / elapsed;
            groups.computeIfAbsent(groupId, ignored -> new GroupSample()).add(position, speed);
        }

        RTSGroupTacticsSavedData tactics = RTSGroupTacticsSavedData.get(level);
        for (Map.Entry<UUID, GroupSample> entry : groups.entrySet()) {
            UUID groupId = entry.getKey();
            GroupSample sample = entry.getValue();
            double speed = sample.averageSpeed();
            if (speed < 0.025D) continue;
            boolean wedge = "CAVALRY_WEDGE".equals(FormationRuntime.currentShape(groupId));
            boolean charging = tactics.timer(groupId, "chargeUntil") > now;
            float volume = volume(sample.riders, speed, wedge, charging);
            if (volume <= 0.0F) continue;
            AudibleGroup audible = new AudibleGroup(level.dimension(), sample.center());
            current.put(groupId, audible);
            CavalryRumblePacket packet = new CavalryRumblePacket(groupId, true, audible.center(), volume, 1.0F);
            for (ServerPlayer player : level.players()) {
                if (player.position().distanceToSqr(audible.center()) <= HEARING_RANGE_SQ) send(player, packet);
            }
        }
    }

    private static void sendStop(ServerLevel level, UUID groupId, Vec3 center) {
        CavalryRumblePacket packet = new CavalryRumblePacket(groupId, false, center, 0.0F, 1.0F);
        for (ServerPlayer player : level.players()) send(player, packet);
    }

    private static void send(ServerPlayer player, CavalryRumblePacket packet) {
        RTSCameraNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /** Riders x actual blocks/tick x independently stacking charge/wedge factors. */
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
        LAST_MOUNT_SAMPLE.clear();
        AUDIBLE_GROUPS.clear();
    }

    private record MountSample(Vec3 position, long tick) { }
    private record AudibleGroup(ResourceKey<Level> levelKey, Vec3 center) { }

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
