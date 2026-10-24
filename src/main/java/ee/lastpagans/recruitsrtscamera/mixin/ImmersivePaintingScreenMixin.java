package ee.lastpagans.recruitsrtscamera.mixin;

import immersive_paintings.entity.ImmersivePaintingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/** Allows the real Immersive Paintings screen to operate on a private client-side proxy. */
@Pseudo
@Mixin(targets = "immersive_paintings.client.gui.ImmersivePaintingScreen", remap = false)
public abstract class ImmersivePaintingScreenMixin implements ImmersivePaintingScreenBridge {
    @Shadow @Final @Mutable public ImmersivePaintingEntity entity;

    @Override
    public void recruitsRts$setEntity(ImmersivePaintingEntity entity) {
        this.entity = entity;
    }
}
