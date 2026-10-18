package ee.lastpagans.recruitsrtscamera.mixin;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.battle.RoutedGroupManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Native Recruits commands and asynchronous target searches must respect tactical defeat. */
@Mixin(AbstractRecruitEntity.class)
public abstract class RecruitRoutingMixin {
    @Inject(method = "searchForTargets", at = @At("HEAD"), cancellable = true, remap = false)
    private void rts$keepFormationTarget(CallbackInfo ci) {
        if (ee.lastpagans.recruitsrtscamera.combat.TacticalCombatManager.ownsTarget(
                (AbstractRecruitEntity) (Object) this)) ci.cancel();
    }
    @Inject(method = {"searchForTargets", "updateGroup", "applyMoralEffects", "doNoPaymentAction"},
            at = @At("HEAD"), cancellable = true, remap = false)
    private void rts$stopRoutedAI(CallbackInfo ci) {
        if (RoutedGroupManager.isRouting((AbstractRecruitEntity) (Object) this)) ci.cancel();
    }

    @Inject(method = {"isEffectedByCommand(Ljava/util/UUID;)Z",
            "isEffectedByCommand(Ljava/util/UUID;Ljava/util/UUID;)Z", "shouldAttack",
            "canBeHired"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void rts$rejectRoutedCommand(CallbackInfoReturnable<Boolean> cir) {
        if (RoutedGroupManager.isRouting((AbstractRecruitEntity) (Object) this)) cir.setReturnValue(false);
    }
}
