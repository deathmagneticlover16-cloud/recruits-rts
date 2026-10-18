package ee.lastpagans.recruitsrtscamera.mixin;

import com.talhanation.recruits.entities.HorsemanEntity;
import com.talhanation.recruits.entities.ai.HorsemanAttackAI;
import ee.lastpagans.recruitsrtscamera.config.RTSServerConfig;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitRuntimeBrain;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitRuntimeMetrics;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitRuntimeRegistry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Native mounted charge movement yields while an RTS formation route owns the horseman. */
@Mixin(HorsemanAttackAI.class)
public abstract class HorsemanGoalGateMixin {
    @Shadow(remap = false) @Final private HorsemanEntity horseman;

    @Inject(method = {"m_8036_()Z", "m_8045_()Z"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void rts$singleMountedAuthority(CallbackInfoReturnable<Boolean> result) {
        RecruitRuntimeMetrics.NATIVE_GOAL_EVALUATIONS.increment();
        if (RTSServerConfig.GOAL_SUPPRESSION.get() && RecruitRuntimeRegistry.brain(horseman).authority()
                == RecruitRuntimeBrain.MovementAuthority.RTS_FORMATION) {
            RecruitRuntimeMetrics.GOALS_SUPPRESSED.increment();
            result.setReturnValue(false);
        }
    }
}
