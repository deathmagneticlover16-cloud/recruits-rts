package ee.lastpagans.recruitsrtscamera.mixin;

import immersive_paintings.entity.ImmersivePaintingEntity;

/** Implemented onto ImmersivePaintingScreen by the optional compatibility mixin. */
public interface ImmersivePaintingScreenBridge {
    void recruitsRts$setEntity(ImmersivePaintingEntity entity);
}
