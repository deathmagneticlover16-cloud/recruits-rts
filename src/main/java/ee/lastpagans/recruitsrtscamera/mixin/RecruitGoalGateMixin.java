package ee.lastpagans.recruitsrtscamera.mixin;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.ai.RecruitFollowOwnerGoal;
import com.talhanation.recruits.entities.ai.RecruitHoldPosGoal;
import com.talhanation.recruits.entities.ai.RecruitMountEntityGoal;
import com.talhanation.recruits.entities.ai.RecruitMoveToPosGoal;
import com.talhanation.recruits.entities.ai.RecruitProtectEntityGoal;
import com.talhanation.recruits.entities.ai.RecruitUpkeepEntityGoal;
import com.talhanation.recruits.entities.ai.RecruitUpkeepPosGoal;
import com.talhanation.recruits.entities.ai.RecruitWanderGoal;
import com.talhanation.recruits.entities.ai.RecruitDismountEntity;
import com.talhanation.recruits.entities.ai.RecruitDodgeGoal;
import com.talhanation.recruits.entities.ai.RecruitMoveTowardsTargetGoal;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitRuntimeBrain;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitRuntimeRegistry;
import ee.lastpagans.recruitsrtscamera.config.RTSServerConfig;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitRuntimeMetrics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevent native utility movement goals from competing with an explicit RTS authority. */
@Mixin({
        RecruitFollowOwnerGoal.class,
        RecruitHoldPosGoal.class,
        RecruitMountEntityGoal.class,
        RecruitMoveToPosGoal.class,
        RecruitProtectEntityGoal.class,
        RecruitUpkeepEntityGoal.class,
        RecruitUpkeepPosGoal.class,
        RecruitWanderGoal.class,
        RecruitDismountEntity.class,
        RecruitDodgeGoal.class,
        RecruitMoveTowardsTargetGoal.class
})
public abstract class RecruitGoalGateMixin {
    @Shadow(remap = false) private AbstractRecruitEntity recruit;

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void rts$singleMovementAuthorityOnStart(CallbackInfoReturnable<Boolean> result) {
        rts$gateMovementGoal(result);
    }

    // Some of the mixed goal classes inherit Goal.canContinueToUse() instead of
    // overriding it, so this injection is optional across the multi-target mixin.
    @Inject(method = "canContinueToUse", at = @At("HEAD"), cancellable = true, require = 0)
    private void rts$singleMovementAuthorityWhileRunning(CallbackInfoReturnable<Boolean> result) {
        rts$gateMovementGoal(result);
    }

    private void rts$gateMovementGoal(CallbackInfoReturnable<Boolean> result) {
        RecruitRuntimeMetrics.NATIVE_GOAL_EVALUATIONS.increment();
        if (RTSServerConfig.GOAL_SUPPRESSION.get() && RecruitRuntimeRegistry.brain(recruit).authority()
                == RecruitRuntimeBrain.MovementAuthority.RTS_FORMATION) {
            RecruitRuntimeMetrics.GOALS_SUPPRESSED.increment();
            result.setReturnValue(false);
        }
    }
}
