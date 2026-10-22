package ee.lastpagans.recruitsrtscamera.mixin;

import ee.lastpagans.recruitsrtscamera.movement.RTSMountControlRegistry;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevent entity shove impulses from fighting an RTS-owned mount's route or slot controller. */
@Mixin(Entity.class)
public abstract class RTSMountEntityPushMixin {
    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void rts$ignoreEntityPushForControlledMount(Entity other, CallbackInfo callback) {
        Entity self = (Entity) (Object) this;
        if (!self.level().isClientSide
                && (RTSMountControlRegistry.controls(self) || RTSMountControlRegistry.controls(other))) {
            // This only cancels entity-to-entity velocity impulses. Block collision,
            // support, fluids, walls, holes and tactical terrain checks remain unchanged.
            callback.cancel();
        }
    }
}
