package ee.lastpagans.recruitsrtscamera.mixin;

import com.talhanation.recruits.entities.NomadEntity;
import com.talhanation.recruits.entities.ai.NomadAttackAI;
import ee.lastpagans.recruitsrtscamera.combat.FormationVolleyManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Horse archers consume the same synchronized bow clock as foot Bowman formations. */
@Mixin(value = NomadAttackAI.class, remap = false)
public abstract class NomadVolleyMixin {
    @Shadow(remap = false) @Final private NomadEntity nomad;
    @Shadow(remap = false) private int attackTime;

    @Inject(method = "m_8037_()V", at = @At("HEAD"), remap = false)
    private void rts$shareMountedBowVolleyClock(CallbackInfo info) {
        int synchronizedTime = FormationVolleyManager.bowAttackTime(nomad);
        if (synchronizedTime != Integer.MIN_VALUE) attackTime = synchronizedTime;
    }
}
