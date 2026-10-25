package ee.lastpagans.recruitsrtscamera.combat;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.compat.IcyHorseSoundsCompat;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        Set<UUID> cavalryGroups = new HashSet<>();
        Set<UUID> sampledMounts = new HashSet<>();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            sampleLevel(level, current, cavalryGroups, sampledMounts);
        }
        LAST_MOUNT_SAMPLE.keySet().removeIf(id -> !sampledMounts.contains(id));
        IcyHorseSoundsCompat.retainGroups(current.keySet());
        MountedTriumphManager.retainGroups(cavalryGroups);

        for (Map.Entry<UUID, AudibleGroup> entry : AUDIBLE_GROUPS.entrySet()) {
            if (current.containsKey(entry.getKey())) continue;
            ServerLevel level = event.getServer().getLevel(entry.getValue().levelKey());
            if (level != null) sendStop(level, entry.getKey(), entry.getValue().center());
        }
        AUDIBLE_GROUPS.clear();
        AUDIBLE_GROUPS.putAll(current);
    }

    private static void sampleLevel(
            ServerLevel level,
            Map<UUID, AudibleGroup> current,
            Set<UUID> cavalryGroups,
            Set<UUID> sampledMounts
    ) {
        Map<UUID, GroupSample> groups = new HashMap<>();
        long now = level.getGameTime();
        for (AbstractRecruitEntity recruit : RecruitRuntimeRegistry.loaded(level)) {
            UUID groupId = recruit.getGroup();
            if (groupId == null) continue;
            GroupSample sample = groups.computeIfAbsent(groupId, ignored -> new GroupSample());
            sample.totalMembers++;
            Entity mount = recruit.getVehicle();
            if (mount == null || !RTSMovementBoostManager.isCavalry(recruit)) continue;
            sampledMounts.add(mount.getUUID());
            Vec3 position = mount.position();
            MountSample previous = LAST_MOUNT_SAMPLE.put(mount.getUUID(), new MountSample(position, now));
            long elapsed = previous == null ? 0L : Math.max(1L, now - previous.tick());
            double speed = previous == null ? 0.0D : horizontalDistance(previous.position(), position) / elapsed;
            sample.add(recruit, mount, speed);
        }

        RTSGroupTacticsSavedData tactics = RTSGroupTacticsSavedData.get(level);
        for (Map.Entry<UUID, GroupSample> entry : groups.entrySet()) {
            UUID groupId = entry.getKey();
            GroupSample sample = entry.getValue();
            if (sample.riders == 0 || sample.riders * 2 <= sample.totalMembers) continue;
            cavalryGroups.add(groupId);
            double speed = sample.averageSpeed();
            boolean wedge = "CAVALRY_WEDGE".equals(FormationRuntime.currentShape(groupId));
            boolean charging = tactics.timer(groupId, "chargeUntil") > now;
            boolean running = charging || RTSMovementBoostManager.isHastyActive(groupId) || speed >= 0.38D;
            MountedTriumphManager.maybePlay(
                    level, groupId, sample.cavalry, sample.averageMorale(),
                    charging || running || wedge, now
            );
            if (speed < 0.025D) continue;
            IcyHorseSoundsCompat.maybePlay(level, groupId, sample.mounts, running, now);
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
        return (float) Math.max(0.0D, Math.min(1.2D, riders * speed * emphasis / 15.0D));
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
        IcyHorseSoundsCompat.reset();
        MountedTriumphManager.reset();
    }

    private record MountSample(Vec3 position, long tick) { }
    private record AudibleGroup(ResourceKey<Level> levelKey, Vec3 center) { }

    private static final class GroupSample {
        int riders;
        int totalMembers;
        double x;
        double y;
        double z;
        double speed;
        final java.util.List<Entity> mounts = new java.util.ArrayList<>();
        final List<AbstractRecruitEntity> cavalry = new java.util.ArrayList<>();
        double morale;

        void add(AbstractRecruitEntity recruit, Entity mount, double actualSpeed) {
            Vec3 position = mount.position();
            riders++;
            mounts.add(mount);
            cavalry.add(recruit);
            x += position.x;
            y += position.y;
            z += position.z;
            speed += Math.min(1.0D, actualSpeed);
            morale += Math.max(0.0F, Math.min(100.0F, recruit.getMorale()));
        }

        double averageSpeed() { return riders == 0 ? 0.0D : speed / riders; }
        float averageMorale() { return riders == 0 ? 0.0F : (float) (morale / riders); }
        Vec3 center() { return new Vec3(x / riders, y / riders, z / riders); }
    }
}
