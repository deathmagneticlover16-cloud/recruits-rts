package ee.lastpagans.recruitsrtscamera.client;

import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.compat.TrottingWagonsCompat;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.client.event.EntityRenderersEvent;

import java.lang.reflect.Constructor;

/** Client-only reflective renderer bridge; never loaded on a dedicated server. */
public final class TrottingWagonsClientCompat {
    private TrottingWagonsClientCompat() {
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void registerRenderer(EntityRenderersEvent.RegisterRenderers event) {
        EntityType<?> type = TrottingWagonsCompat.entityType();
        if (type == null) return;
        event.registerEntityRenderer((EntityType) type, TrottingWagonsClientCompat::createRenderer);
    }

    @SuppressWarnings("unchecked")
    private static EntityRenderer<Entity> createRenderer(EntityRendererProvider.Context context) {
        try {
            Class<?> rendererClass = Class.forName(
                    "net.favouriteless.trotting_wagons.client.render.entity.WagonRenderer");
            Constructor<?> constructor = rendererClass.getConstructor(
                    EntityRendererProvider.Context.class,
                    ResourceLocation.class);
            return (EntityRenderer<Entity>) constructor.newInstance(
                    context,
                    new ResourceLocation(RecruitsRTSCameraMod.MOD_ID, TrottingWagonsCompat.MODEL_PATH));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            RecruitsRTSCameraMod.LOGGER.error(
                    "Could not create the optional Trotting Wagons combat-wagon renderer", exception);
            return new EntityRenderer<>(context) {
                @Override
                public ResourceLocation getTextureLocation(Entity entity) {
                    return TextureAtlas.LOCATION_BLOCKS;
                }
            };
        }
    }
}
