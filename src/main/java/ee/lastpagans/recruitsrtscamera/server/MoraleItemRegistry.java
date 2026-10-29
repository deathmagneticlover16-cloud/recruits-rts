package ee.lastpagans.recruitsrtscamera.server;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.persistence.RTSMoraleItemSavedData;
import ee.lastpagans.recruitsrtscamera.registry.RTSTags;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** One authoritative check shared by morale protection and standard-bearer positioning. */
public final class MoraleItemRegistry {
    private MoraleItemRegistry() { }

    public static boolean carries(AbstractRecruitEntity recruit) {
        if (recruit == null || !(recruit.level() instanceof ServerLevel level)) return false;
        return matches(level, recruit.getMainHandItem()) || matches(level, recruit.getOffhandItem());
    }

    public static boolean matches(ServerLevel level, ItemStack stack) {
        if (level == null || stack == null || stack.isEmpty()) return false;
        return stack.is(RTSTags.MORALE_ITEMS)
                || RTSMoraleItemSavedData.get(level).contains(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }
}
