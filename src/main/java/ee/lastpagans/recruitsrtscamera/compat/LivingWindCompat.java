package ee.lastpagans.recruitsrtscamera.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/** Optional, dedicated-server-safe bridge to Living Wind's public server API. */
public final class LivingWindCompat {
    private static final String MOD_ID = "livingwind";
    private static volatile Api api;
    private static volatile boolean resolved;

    private LivingWindCompat() { }

    public static Vec3 windAt(ServerLevel level, Vec3 position) {
        if (level == null || position == null || !ModList.get().isLoaded(MOD_ID)) return Vec3.ZERO;
        Api bridge = resolve();
        if (bridge == null) return Vec3.ZERO;
        try {
            Object snapshot = bridge.getAt.invoke(null, level, BlockPos.containing(position));
            if (snapshot == null) return Vec3.ZERO;
            double x = ((Number) bridge.windX.invoke(snapshot)).doubleValue();
            double z = ((Number) bridge.windZ.invoke(snapshot)).doubleValue();
            return Double.isFinite(x) && Double.isFinite(z) ? new Vec3(x, 0.0D, z) : Vec3.ZERO;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return Vec3.ZERO;
        }
    }

    private static Api resolve() {
        if (resolved) return api;
        synchronized (LivingWindCompat.class) {
            if (resolved) return api;
            try {
                ClassLoader loader = LivingWindCompat.class.getClassLoader();
                Class<?> windApi = Class.forName("com.macatmovie.livingwind.api.WindApi", false, loader);
                Class<?> snapshot = Class.forName("com.macatmovie.livingwind.wind.WindSnapshot", false, loader);
                api = new Api(
                        windApi.getMethod("getAt", ServerLevel.class, BlockPos.class),
                        snapshot.getMethod("windX"),
                        snapshot.getMethod("windZ")
                );
            } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
                api = null;
            }
            resolved = true;
            return api;
        }
    }

    private record Api(Method getAt, Method windX, Method windZ) { }
}
