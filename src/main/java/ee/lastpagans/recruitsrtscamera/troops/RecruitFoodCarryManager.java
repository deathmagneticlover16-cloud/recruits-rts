package ee.lastpagans.recruitsrtscamera.troops;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Enforces the campaign food carry ceiling without deleting excess supplies. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RecruitFoodCarryManager {
    public static final int MAX_FOOD_PIECES = 38;

    private RecruitFoodCarryManager() { }

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof AbstractRecruitEntity recruit)
                || recruit.level().isClientSide || recruit.tickCount % 20 != Math.floorMod(recruit.getId(), 20)) return;
        Container inventory = recruit.getInventory();
        if (inventory == null) return;
        int food = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getItem().getFoodProperties() != null) food += stack.getCount();
        }
        int excess = food - MAX_FOOD_PIECES;
        if (excess <= 0) return;
        for (int slot = inventory.getContainerSize() - 1; slot >= 0 && excess > 0; slot--) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || stack.getItem().getFoodProperties() == null) continue;
            int remove = Math.min(excess, stack.getCount());
            ItemStack dropped = inventory.removeItem(slot, remove);
            if (!dropped.isEmpty()) recruit.spawnAtLocation(dropped, 0.15F);
            excess -= remove;
        }
        inventory.setChanged();
    }
}
