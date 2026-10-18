package ee.lastpagans.recruitsrtscamera.mixin;

import ee.lastpagans.recruitsrtscamera.client.RTSSelectionOutlineState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Force the normal vanilla entity-outline render pass for RTS-selected recruits.
 *
 * <p>0.9.7 only toggled the local glowing entity flag. Server entity-data refreshes can
 * overwrite that flag before the render pass on busy multiplayer clients, making the
 * selection outline disappear. Hooking Minecraft's actual outline decision is purely
 * client-side and does not touch scoreboard teams or synchronize glow state to anyone else.</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftSelectionGlowMixin {
    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
    private void recruitsRtsCamera$selectedEntityGlows(
            Entity entity,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (entity != null && RTSSelectionOutlineState.contains(entity.getUUID())) {
            cir.setReturnValue(true);
        }
    }
}
