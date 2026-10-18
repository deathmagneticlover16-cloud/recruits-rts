package ee.lastpagans.recruitsrtscamera.combat;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.config.RTSServerConfig;
import ee.lastpagans.recruitsrtscamera.projectile.ThrownRecruitWeaponEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Extra lethality is highest against exposed troops; vanilla armor and shields still resolve damage. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID)
public final class ProjectileBalance {
    private ProjectileBalance() {}
    @SubscribeEvent public static void onHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof AbstractRecruitEntity victim)
                || !(event.getSource().getEntity() instanceof AbstractRecruitEntity)) return;
        Object direct = event.getSource().getDirectEntity();
        double multiplier;
        if (direct instanceof ThrownRecruitWeaponEntity) multiplier = RTSServerConfig.JAVELIN_LIGHT_ARMOR_DAMAGE.get();
        else if (direct instanceof AbstractArrow) multiplier = RTSServerConfig.ARROW_LIGHT_ARMOR_DAMAGE.get();
        else return;
        event.setAmount(TacticalMath.projectileDamage(event.getAmount(), victim.getArmorValue(), multiplier));
    }
}
