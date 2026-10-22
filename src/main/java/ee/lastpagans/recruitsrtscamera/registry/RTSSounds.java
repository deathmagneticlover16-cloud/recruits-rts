package ee.lastpagans.recruitsrtscamera.registry;

import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class RTSSounds {
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(
            ForgeRegistries.SOUND_EVENTS,
            RecruitsRTSCameraMod.MOD_ID
    );

    public static final RegistryObject<SoundEvent> ADVISOR_GENERAL_ATTACKED = register("advisor_general_attacked");
    public static final RegistryObject<SoundEvent> ADVISOR_UNIT_ROUT = register("advisor_unit_rout");
    public static final RegistryObject<SoundEvent> ADVISOR_UNITS_TIRED = register("advisor_units_tired");
    public static final RegistryObject<SoundEvent> ADVISOR_UNIT_FALLEN = register("advisor_unit_fallen");
    public static final RegistryObject<SoundEvent> ADVISOR_AMMO_DEPLETED = register("advisor_ammo_depleted");
    public static final RegistryObject<SoundEvent> ADVISOR_UNITS_EXHAUSTED = register("advisor_units_exhausted");
    public static final RegistryObject<SoundEvent> ADVISOR_ENEMY_COMMANDER_FALLEN = register("advisor_enemy_commander_fallen");
    public static final RegistryObject<SoundEvent> ADVISOR_ENEMY_UNIT_ROUT = register("advisor_enemy_unit_rout");
    public static final RegistryObject<SoundEvent> ADVISOR_ARTILLERY_ATTACKED = register("advisor_artillery_attacked");
    public static final RegistryObject<SoundEvent> CAVALRY_RUMBLE = register("cavalry_rumble");
    public static final RegistryObject<SoundEvent> CAVALRY_RUMBLE_DISTANT = register("cavalry_rumble_distant");

    private RTSSounds() {
    }

    private static RegistryObject<SoundEvent> register(String path) {
        ResourceLocation id = new ResourceLocation(RecruitsRTSCameraMod.MOD_ID, path);
        return SOUNDS.register(path, () -> SoundEvent.createVariableRangeEvent(id));
    }

    public static void register(IEventBus bus) {
        SOUNDS.register(bus);
    }
}
