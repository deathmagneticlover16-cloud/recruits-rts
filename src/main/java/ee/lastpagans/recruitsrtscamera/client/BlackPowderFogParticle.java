package ee.lastpagans.recruitsrtscamera.client;

import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.config.RTSClientConfig;
import ee.lastpagans.recruitsrtscamera.registry.RTSParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Large, slowly dispersing translucent black-powder fog sprite. */
public final class BlackPowderFogParticle extends TextureSheetParticle {
    private final float startingAlpha;

    private BlackPowderFogParticle(ClientLevel level, double x, double y, double z,
                                   double xSpeed, double ySpeed, double zSpeed,
                                   SpriteSet sprites, int spriteTier) {
        super(level, x, y, z, xSpeed, ySpeed, zSpeed);
        this.xd = xSpeed * 0.20D + (random.nextDouble() - 0.5D) * 0.006D;
        this.yd = ySpeed * 0.20D + 0.0015D + random.nextDouble() * 0.002D;
        this.zd = zSpeed * 0.20D + (random.nextDouble() - 0.5D) * 0.006D;
        this.lifetime = 150 + random.nextInt(100);
        this.quadSize = 1.8F + random.nextFloat() * 1.4F;
        // Light black-powder haze remains translucent after one volley while ordinary
        // alpha compositing makes repeated volleys progressively more opaque.
        this.startingAlpha = 0.18F + random.nextFloat() * 0.08F;
        this.alpha = startingAlpha;
        float shade = 0.78F + random.nextFloat() * 0.14F;
        setColor(shade, shade * 0.985F, shade * 0.955F);
        this.friction = 0.992F;
        setSprite(sprites.get(Mth.clamp(spriteTier, 0, 3), 3));
    }

    @Override
    public void tick() {
        super.tick();
        float progress = age / (float) lifetime;
        this.alpha = startingAlpha * Mth.clamp(Math.min(progress / 0.10F,
                (1.0F - progress) / 0.34F), 0.0F, 1.0F);
        this.quadSize *= 1.0035F;
        this.yd += 0.000015D;
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
            RTSClientConfig.SmokeResolution quality = RTSClientConfig.BLACK_POWDER_SMOKE_RESOLUTION.get();
            Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            double distance = camera.distanceTo(new Vec3(x, y, z));
            int distanceTier = distance <= 32.0D ? 3 : distance <= 64.0D ? 2 : distance <= 128.0D ? 1 : 0;
            float lodDensity = distance <= 32.0D ? 1.0F : distance <= 64.0D ? 0.72F
                    : distance <= 128.0D ? 0.46F : 0.24F;
            if (level.random.nextFloat() > quality.density() * lodDensity) return null;
            int spriteTier = Math.min(quality.spriteTier(), distanceTier);
            return new BlackPowderFogParticle(level, x, y, z, xSpeed, ySpeed, zSpeed,
                    sprites, spriteTier);
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
