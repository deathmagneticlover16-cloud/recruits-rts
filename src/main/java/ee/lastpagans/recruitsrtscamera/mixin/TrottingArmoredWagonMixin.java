package ee.lastpagans.recruitsrtscamera.mixin;

import ee.lastpagans.recruitsrtscamera.compat.TrottingWagonsCompat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes middle-click and destruction return the restored item instead of an Armored Wagon. */
@Pseudo
@Mixin(targets = "net.favouriteless.trotting_wagons.common.entities.wagons.ArmoredWagon", remap = false)
public abstract class TrottingArmoredWagonMixin {
    @Inject(method = {"getPickResult", "m_142340_"}, at = @At("HEAD"),
            cancellable = true, require = 0, remap = false)
    private void rts$pickScythedVariant(CallbackInfoReturnable<ItemStack> result) {
        if (TrottingWagonsCompat.isScythedWagon((Entity) (Object) this)) {
            Entity wagon = (Entity) (Object) this;
            result.setReturnValue(TrottingWagonsCompat.createItemStack(TrottingWagonsCompat.color(wagon)));
        }
    }
}
