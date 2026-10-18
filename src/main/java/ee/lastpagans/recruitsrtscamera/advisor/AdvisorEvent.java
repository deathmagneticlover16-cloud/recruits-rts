package ee.lastpagans.recruitsrtscamera.advisor;

import ee.lastpagans.recruitsrtscamera.registry.RTSSounds;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.RegistryObject;

public enum AdvisorEvent {
    GENERAL_ATTACKED(5, "Our general is under attack!", RTSSounds.ADVISOR_GENERAL_ATTACKED),
    UNIT_ROUT(5, "A unit has fled the battlefield!", RTSSounds.ADVISOR_UNIT_ROUT),
    UNITS_TIRED(2, "Our men are growing tired.", RTSSounds.ADVISOR_UNITS_TIRED),
    UNIT_FALLEN(5, "An entire unit has fallen!", RTSSounds.ADVISOR_UNIT_FALLEN),
    AMMO_DEPLETED(2, "A unit has used all of its ammunition!", RTSSounds.ADVISOR_AMMO_DEPLETED),
    UNITS_EXHAUSTED(4, "Our men are exhausted!", RTSSounds.ADVISOR_UNITS_EXHAUSTED),
    ENEMY_COMMANDER_FALLEN(4, "The enemy commander is dead!", RTSSounds.ADVISOR_ENEMY_COMMANDER_FALLEN),
    ENEMY_UNIT_ROUT(3, "An enemy unit has fled the battlefield!", RTSSounds.ADVISOR_ENEMY_UNIT_ROUT),
    ARTILLERY_ATTACKED(4, "Our artillery is under attack!", RTSSounds.ADVISOR_ARTILLERY_ATTACKED);

    private final int priority;
    private final String subtitle;
    private final RegistryObject<SoundEvent> sound;

    AdvisorEvent(int priority, String subtitle, RegistryObject<SoundEvent> sound) {
        this.priority = priority;
        this.subtitle = subtitle;
        this.sound = sound;
    }

    public int priority() {
        return priority;
    }

    public String subtitle() {
        return subtitle;
    }

    public SoundEvent sound() {
        return sound.get();
    }
}
