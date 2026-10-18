package ee.lastpagans.recruitsrtscamera.registry;

import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.projectile.ThrownRecruitWeaponEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class RTSEntities {
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(
            ForgeRegistries.ENTITY_TYPES,
            RecruitsRTSCameraMod.MOD_ID
    );

    public static final RegistryObject<EntityType<ThrownRecruitWeaponEntity>> THROWN_RECRUIT_WEAPON =
            ENTITIES.register("thrown_recruit_weapon", () -> EntityType.Builder
                    .<ThrownRecruitWeaponEntity>of(ThrownRecruitWeaponEntity::new, MobCategory.MISC)
                    .sized(0.25F, 0.25F)
                    .clientTrackingRange(8)
                    .updateInterval(1)
                    .build(RecruitsRTSCameraMod.MOD_ID + ":thrown_recruit_weapon"));

    private RTSEntities() {
    }

    public static void register(IEventBus bus) {
        ENTITIES.register(bus);
    }
}
