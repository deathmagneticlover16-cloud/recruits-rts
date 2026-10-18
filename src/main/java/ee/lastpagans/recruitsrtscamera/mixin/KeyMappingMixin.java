package ee.lastpagans.recruitsrtscamera.mixin;

import ee.lastpagans.recruitsrtscamera.client.InputGate;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops foreign KeyMappings at the mapping itself while RTS command mode owns input.
 * This is intentionally client-only and complements the normal Forge input-event guard.
 */
@Mixin(KeyMapping.class)
public abstract class KeyMappingMixin {
    @Shadow private boolean isDown;
    @Shadow private int clickCount;

    @Inject(method = "setDown", at = @At("HEAD"), cancellable = true)
    private void recruitsRTSCamera$guardSetDown(boolean down, CallbackInfo ci) {
        KeyMapping self = (KeyMapping) (Object) this;
        if (InputGate.shouldSuppress(self)) {
            this.isDown = false;
            this.clickCount = 0;
            ci.cancel();
        }
    }

    @Inject(method = "isDown", at = @At("HEAD"), cancellable = true)
    private void recruitsRTSCamera$guardIsDown(CallbackInfoReturnable<Boolean> cir) {
        KeyMapping self = (KeyMapping) (Object) this;
        if (InputGate.shouldSuppress(self)) {
            this.isDown = false;
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "consumeClick", at = @At("HEAD"), cancellable = true)
    private void recruitsRTSCamera$guardConsume(CallbackInfoReturnable<Boolean> cir) {
        KeyMapping self = (KeyMapping) (Object) this;
        if (InputGate.shouldSuppress(self)) {
            this.clickCount = 0;
            cir.setReturnValue(false);
        }
    }
}
