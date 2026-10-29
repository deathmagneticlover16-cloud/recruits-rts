package ee.lastpagans.recruitsrtscamera.compat;

import com.talhanation.recruits.entities.CrossBowmanEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Native replacement for Musket Rest Position 0.1.1. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MusketRestManager {
    private static final int IDLE_TICKS = 20 * 5;
    private static final Set<String> LONG_GUNS = Set.of(
            "musket", "musket_with_bayonet", "musket_with_scope", "blunderbuss"
    );
    private static final Map<UUID, Integer> QUIET_TICKS = new HashMap<>();

    private MusketRestManager() { }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof CrossBowmanEntity recruit) || recruit.level().isClientSide) return;
        ItemStack main = recruit.getMainHandItem();
        ItemStack off = recruit.getOffhandItem();
        if (isLongGun(off)) {
            QUIET_TICKS.put(recruit.getUUID(), 0);
            if (combatActive(recruit) && main.isEmpty()) move(recruit, off, InteractionHand.MAIN_HAND);
            return;
        }
        if (!isLongGun(main)) {
            QUIET_TICKS.remove(recruit.getUUID());
            return;
        }
        if (combatActive(recruit)) {
            QUIET_TICKS.put(recruit.getUUID(), 0);
            return;
        }
        int quiet = QUIET_TICKS.merge(recruit.getUUID(), 1, Integer::sum);
        if (quiet >= IDLE_TICKS && off.isEmpty()) {
            move(recruit, main, InteractionHand.OFF_HAND);
            QUIET_TICKS.put(recruit.getUUID(), 0);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide || !(event.getEntity() instanceof CrossBowmanEntity recruit)) return;
        if (recruit.getMainHandItem().isEmpty() && isLongGun(recruit.getOffhandItem())) {
            move(recruit, recruit.getOffhandItem(), InteractionHand.MAIN_HAND);
        }
        QUIET_TICKS.remove(recruit.getUUID());
    }

    @SubscribeEvent
    public static void onStop(ServerStoppedEvent event) { QUIET_TICKS.clear(); }

    private static boolean combatActive(CrossBowmanEntity recruit) {
        return recruit.getTarget() != null && recruit.getTarget().isAlive()
                || recruit.getShouldStrategicFire()
                || recruit.isUsingItem();
    }

    private static boolean isLongGun(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return "musketmod".equals(id.getNamespace()) && LONG_GUNS.contains(id.getPath());
    }

    private static void move(CrossBowmanEntity recruit, ItemStack stack, InteractionHand destination) {
        InteractionHand source = destination == InteractionHand.MAIN_HAND
                ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        recruit.setItemInHand(destination, stack);
        recruit.setItemInHand(source, ItemStack.EMPTY);
    }
}
