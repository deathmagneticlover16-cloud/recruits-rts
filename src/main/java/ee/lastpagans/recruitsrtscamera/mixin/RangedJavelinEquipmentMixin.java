package ee.lastpagans.recruitsrtscamera.mixin;

import com.talhanation.recruits.entities.BowmanEntity;
import com.talhanation.recruits.entities.CrossBowmanEntity;
import ee.lastpagans.recruitsrtscamera.combat.ThrowableWeaponManager;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets missile specialists accept the supported Epic Knights javelin in main hand. */
@Mixin(value = {BowmanEntity.class, CrossBowmanEntity.class}, remap = false)
public abstract class RangedJavelinEquipmentMixin {
    @Inject(method = "m_7252_(Lnet/minecraft/world/item/ItemStack;)Z",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void rts$allowJavelinInMainHand(ItemStack stack,
                                            CallbackInfoReturnable<Boolean> result) {
        if (ThrowableWeaponManager.isSupportedThrowable(stack)) result.setReturnValue(true);
    }
}
