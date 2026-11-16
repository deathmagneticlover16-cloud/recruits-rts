package ee.lastpagans.recruitsrtscamera.mixin;

import com.talhanation.recruits.entities.BowmanEntity;
import com.talhanation.recruits.entities.ai.RecruitRangedBowAttackGoal;
import ee.lastpagans.recruitsrtscamera.combat.FormationVolleyManager;
import ee.lastpagans.recruitsrtscamera.combat.TacticalCombatManager;
import ee.lastpagans.recruitsrtscamera.combat.ThrowableWeaponManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces per-soldier randomized bow cooldowns with the formation's shared draw clock. */
@Mixin(value = RecruitRangedBowAttackGoal.class, remap = false)
public abstract class BowVolleyMixin {
    @Shadow(remap = false) @Final private BowmanEntity recruit;
    @Shadow(remap = false) private int attackTime;

    @Inject(method = "m_8037_()V", at = @At("HEAD"), cancellable = true, remap = false)
    private void rts$shareBowVolleyClock(CallbackInfo info) {
        if (ThrowableWeaponManager.ownsThrowCycle(recruit)) {
            if (recruit.holdFormation) recruit.getNavigation().stop();
            info.cancel();
            return;
        }
        int synchronizedTime = FormationVolleyManager.bowAttackTime(recruit);
        if (synchronizedTime != Integer.MIN_VALUE) attackTime = synchronizedTime;
    }

    @Inject(method = "m_8037_()V", at = @At("TAIL"), remap = false)
    private void rts$holdRangedFormationSlot(CallbackInfo info) {
        if (recruit.holdFormation && TacticalCombatManager.ownsTarget(recruit)) {
            recruit.getNavigation().stop();
        }
    }
}
