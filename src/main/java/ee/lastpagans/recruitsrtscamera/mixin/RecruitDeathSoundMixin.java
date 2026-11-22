package ee.lastpagans.recruitsrtscamera.mixin;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.sounds.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replaces the immediate native voice so one delayed RTS death sound is heard per casualty. */
@Mixin(value = AbstractRecruitEntity.class, remap = false)
public abstract class RecruitDeathSoundMixin {
    // Recruits ships this vanilla override under its production SRG name.
    @Inject(method = "m_5592_()Lnet/minecraft/sounds/SoundEvent;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void recruitsRts$replaceNativeDeathVoice(CallbackInfoReturnable<SoundEvent> result) {
        // LivingEntity treats a null death event as silent. The queued RTS voice is the sole
        // audible event, avoiding a native+custom double without emitting an empty packet.
        result.setReturnValue(null);
    }
}
