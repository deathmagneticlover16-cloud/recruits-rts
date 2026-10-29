package ee.lastpagans.recruitsrtscamera.compat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/** Optional bridge to Epic Knights' real lance raised/lowered model state. */
public final class EpicKnightsLanceCompat {
    private static boolean resolved;
    private static Class<?> lanceClass;
    private static Method setRaised;

    private EpicKnightsLanceCompat() { }

    public static boolean isLance(LivingEntity rider) {
        resolve();
        return rider != null && lanceClass != null
                && lanceClass.isInstance(rider.getMainHandItem().getItem());
    }

    public static boolean setRaised(LivingEntity rider, boolean raised) {
        resolve();
        if (rider == null || lanceClass == null || setRaised == null) return false;
        Item item = rider.getMainHandItem().getItem();
        if (!lanceClass.isInstance(item)) return false;
        try {
            setRaised.invoke(item, rider, raised);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;
        if (!ModList.get().isLoaded("magistuarmory")) return;
        try {
            lanceClass = Class.forName("com.magistuarmory.item.LanceItem");
            setRaised = lanceClass.getMethod("setRaised", LivingEntity.class, boolean.class);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            lanceClass = null;
            setRaised = null;
        }
    }
}
