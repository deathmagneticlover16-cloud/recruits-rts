package ee.lastpagans.recruitsrtscamera.client;

import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.registry.RTSParticles;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Large, slowly dispersing translucent black-powder fog sprite. */
public final class BlackPowderFogParticle extends TextureSheetParticle {
    private final SpriteSet sprites;
    private final float startingAlpha;

    private BlackPowderFogParticle(ClientLevel level, double x, double y, double z,
                                   double xSpeed, double ySpeed, double zSpeed, SpriteSet sprites) {
        super(level, x, y, z, xSpeed, ySpeed, zSpeed);
        this.sprites = sprites;
        this.xd = xSpeed * 0.20D + (random.nextDouble() - 0.5D) * 0.006D;
        this.yd = ySpeed * 0.20D + 0.0015D + random.nextDouble() * 0.002D;
        this.zd = zSpeed * 0.20D + (random.nextDouble() - 0.5D) * 0.006D;
        this.lifetime = 150 + random.nextInt(100);
        this.quadSize = 2.7F + random.nextFloat() * 2.4F;
        this.startingAlpha = 0.20F + random.nextFloat() * 0.10F;
        this.alpha = startingAlpha;
        float shade = 0.76F + random.nextFloat() * 0.13F;
        setColor(shade, shade, shade * 0.98F);
        this.friction = 0.992F;
        setSpriteFromAge(sprites);
    }

    @Override
    public void tick() {
        super.tick();
        float progress = age / (float) lifetime;
        this.alpha = startingAlpha * Mth.clamp(Math.min(progress / 0.10F,
                (1.0F - progress) / 0.34F), 0.0F, 1.0F);
        this.quadSize *= 1.0035F;
        this.yd += 0.000015D;
        setSpriteFromAge(sprites);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    public static final class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double xSpeed, double ySpeed, double zSpeed) {
            return new BlackPowderFogParticle(level, x, y, z, xSpeed, ySpeed, zSpeed, sprites);
        }
    }

    @Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID,
            bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Registration {
        private Registration() { }

        @SubscribeEvent
        public static void registerProviders(RegisterParticleProvidersEvent event) {
            event.registerSpriteSet(RTSParticles.BLACK_POWDER_FOG.get(), Provider::new);
        }
    }
}
