package ee.lastpagans.recruitsrtscamera.mixin;

import ee.lastpagans.recruitsrtscamera.client.InputGate;
import ee.lastpagans.recruitsrtscamera.client.RTSCameraController;
import net.minecraft.client.KeyboardHandler;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hard command-mode keyboard gate.
 *
 * KeyMappingMixin stops ordinary bindings from becoming/down staying active. This
 * second layer runs before Minecraft/Forge dispatches a keyboard press at all, which
 * also stops mods that listen directly to Forge's InputEvent.Key instead of polling a
 * KeyMapping. Escape is always retained as the emergency way to open a screen.
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void recruitsRTSCamera$guardKeyPress(
            long window,
            int key,
            int scanCode,
            int action,
            int modifiers,
            CallbackInfo ci
    ) {
        // RTS command bindings are handled before the normal KeyboardHandler body so
        // the same physical key cannot leak into another mod's raw InputEvent.Key listener.
        // KeyMapping polling remains as a fallback for non-mixin environments.
        if (RTSCameraController.captureRawKey(key, scanCode, action, modifiers)) {
            ci.cancel();
            return;
        }

        if (!InputGate.isBlocking()) {
            return;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            return;
        }
        if (!InputGate.isPhysicalKeyAllowed(key, scanCode)) {
            ci.cancel();
        }
    }
}
