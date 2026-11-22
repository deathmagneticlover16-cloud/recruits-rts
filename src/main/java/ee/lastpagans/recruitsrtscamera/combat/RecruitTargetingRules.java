package ee.lastpagans.recruitsrtscamera.combat;

import com.talhanation.recruits.RecruitEvents;
import com.talhanation.recruits.config.RecruitsServerConfig;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

/** One authoritative gate for RTS-owned passive target acquisition. */
public final class RecruitTargetingRules {
    private RecruitTargetingRules() { }

    public static boolean mayAutomaticallyTarget(AbstractRecruitEntity recruit, LivingEntity target) {
        if (recruit == null || target == null || target == recruit || !target.isAlive()
                || target.isRemoved() || target.isAlliedTo(recruit) || recruit.isAlliedTo(target)
                || !target.isAttackable() || !recruit.canAttack(target)) return false;

        if (isBlacklisted(target)) return false;
        return RecruitEvents.canAttack(recruit, target);
    }

    /** Recruits' own configured list applies to explicit RTS orders as well as passive AI. */
    public static boolean isBlacklisted(LivingEntity target) {
        if (target == null) return true;
        ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
        return type != null && targetBlacklist().contains(type.toString());
    }

    private static List<? extends String> targetBlacklist() {
        try {
            List<? extends String> configured = RecruitsServerConfig.TargetBlackList.get();
            return configured == null ? List.of() : configured;
        } catch (RuntimeException ignored) {
            // Recruits also exposes its restart-time copy. Falling back keeps this gate safe
            // during unusual early lifecycle calls without inventing a separate RTS list.
            return RecruitsServerConfig.TARGET_BLACKLIST == null
                    ? List.of() : RecruitsServerConfig.TARGET_BLACKLIST;
        }
    }
}
