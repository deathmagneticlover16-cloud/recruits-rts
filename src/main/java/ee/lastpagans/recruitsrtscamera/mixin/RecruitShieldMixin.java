package ee.lastpagans.recruitsrtscamera.mixin;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.ai.UseShield;
import com.talhanation.recruits.pathfinding.AsyncPathfinderMob;
import ee.lastpagans.recruitsrtscamera.combat.ShieldTactics;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Native shield goal must not repeatedly lower a shield owned by the tactical stance. */
@Mixin(UseShield.class)
public abstract class RecruitShieldMixin {
    @Shadow(remap = false) @Final public AsyncPathfinderMob entity;
    @Inject(method = "hasShieldInHand", at = @At("HEAD"), cancellable = true, remap = false)
    private void rts$tacticalShieldOwner(CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof AbstractRecruitEntity recruit && ShieldTactics.controls(recruit)) cir.setReturnValue(false);
    }
}
