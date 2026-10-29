package ee.lastpagans.recruitsrtscamera.registry;

import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Particle types owned by RTS so firearm smoke does not depend on another mod. */
public final class RTSParticles {
    private static final DeferredRegister<net.minecraft.core.particles.ParticleType<?>> PARTICLES =
            DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, RecruitsRTSCameraMod.MOD_ID);

    public static final RegistryObject<SimpleParticleType> BLACK_POWDER_FOG =
            PARTICLES.register("black_powder_fog", () -> new SimpleParticleType(false));

    private RTSParticles() { }

    public static void register(IEventBus bus) {
        PARTICLES.register(bus);
    }
}
