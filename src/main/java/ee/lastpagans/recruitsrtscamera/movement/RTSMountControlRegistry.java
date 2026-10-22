package ee.lastpagans.recruitsrtscamera.movement;

import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Reference-counted mounts whose horizontal movement is wholly owned by RTS control. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RTSMountControlRegistry {
    private static final Map<UUID, Integer> CLAIMS = new HashMap<>();

    private RTSMountControlRegistry() { }

    public static void claim(Entity mount) {
        if (mount != null) CLAIMS.merge(mount.getUUID(), 1, Integer::sum);
    }

    public static void release(Entity mount) {
        if (mount == null) return;
        UUID id = mount.getUUID();
        Integer claims = CLAIMS.get(id);
        if (claims == null || claims <= 1) CLAIMS.remove(id);
        else CLAIMS.put(id, claims - 1);
    }

    public static boolean controls(Entity entity) {
        return entity != null && CLAIMS.containsKey(entity.getUUID());
    }

    @SubscribeEvent
    public static void onStop(ServerStoppedEvent event) {
        CLAIMS.clear();
    }
}
