package ee.lastpagans.recruitsrtscamera.mixin;

import com.talhanation.recruits.entities.CrossBowmanEntity;
import com.talhanation.recruits.entities.ai.RecruitRangedCrossbowAttackGoal;
import ee.lastpagans.recruitsrtscamera.combat.FormationVolleyManager;
import net.minecraft.world.item.CrossbowItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets every crossbow reload independently, then holds loaded weapons for one group release. */
@Mixin(value = RecruitRangedCrossbowAttackGoal.class, remap = false)
public abstract class CrossbowVolleyMixin {
    @Shadow(remap = false) @Final private CrossBowmanEntity crossBowman;

    @Inject(method = "m_8037_()V", at = @At("HEAD"), cancellable = true, remap = false)
    private void rts$holdLoadedCrossbowForVolley(CallbackInfo info) {
        if (CrossbowItem.isCharged(crossBowman.getMainHandItem())
                && !FormationVolleyManager.crossbowMayRelease(crossBowman)) info.cancel();
    }
}
