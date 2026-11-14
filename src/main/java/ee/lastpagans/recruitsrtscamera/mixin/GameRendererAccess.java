package ee.lastpagans.recruitsrtscamera.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Client-only bridge to Minecraft's effective FOV calculation used by world rendering. */
@Mixin(GameRenderer.class)
public interface GameRendererAccess {
    @Invoker("getFov")
    double recruitsRts$invokeGetFov(Camera camera, float partialTick, boolean useFovSetting);
}
