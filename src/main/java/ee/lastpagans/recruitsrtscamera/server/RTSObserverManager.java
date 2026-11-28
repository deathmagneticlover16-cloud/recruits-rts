package ee.lastpagans.recruitsrtscamera.server;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-side positions of active detached RTS listeners/render observers. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RTSObserverManager {
    private static final Map<UUID, Observer> OBSERVERS = new ConcurrentHashMap<>();

    private RTSObserverManager() { }

    public static void update(ServerPlayer player, boolean active, Vec3 position) {
        if (player == null) return;
        if (!active || position == null || !Double.isFinite(position.x)
                || !Double.isFinite(position.y) || !Double.isFinite(position.z)) {
            OBSERVERS.remove(player.getUUID());
            return;
        }
        // The client camera is already clamped; retain an additional server-side abuse bound.
        if (position.distanceToSqr(player.position()) > 1_024.0D * 1_024.0D) return;
        OBSERVERS.put(player.getUUID(), new Observer(player.level().dimension(), position,
                player.serverLevel().getGameTime()));
    }

    public static Vec3 position(ServerPlayer player) {
        if (player == null) return null;
        Observer observer = OBSERVERS.get(player.getUUID());
        if (observer == null || !observer.dimension.equals(player.level().dimension())
                || player.serverLevel().getGameTime() - observer.updatedAt > 40L) return null;
        return observer.position;
    }

    /** Camera listeners near a sound whose physical player would not receive vanilla audio. */
    public static List<ServerPlayer> remoteListeners(ServerLevel level, Vec3 source, double radius) {
        return remoteListeners(level, source, radius, radius);
    }

    public static List<ServerPlayer> remoteListeners(ServerLevel level, Vec3 source,
                                                     double cameraRadius, double physicalRadius) {
        if (level == null || source == null) return List.of();
        double radiusSq = cameraRadius * cameraRadius;
        double physicalRadiusSq = physicalRadius * physicalRadius;
        List<ServerPlayer> result = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            Vec3 camera = position(player);
            if (camera != null && camera.distanceToSqr(source) <= radiusSq
                    && player.distanceToSqr(source) > physicalRadiusSq) result.add(player);
        }
        return result;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        OBSERVERS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onStop(ServerStoppedEvent event) { OBSERVERS.clear(); }

    private record Observer(ResourceKey<Level> dimension, Vec3 position, long updatedAt) { }
}
