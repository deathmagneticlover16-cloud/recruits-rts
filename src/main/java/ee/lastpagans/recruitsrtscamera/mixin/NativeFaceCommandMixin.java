package ee.lastpagans.recruitsrtscamera.mixin;

import com.talhanation.recruits.network.MessageFaceCommand;
import ee.lastpagans.recruitsrtscamera.formation.FormationRuntime;
import ee.lastpagans.recruitsrtscamera.formation.FormationService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/** Carries the native Recruits Face command into the built-in formation runtime. */
@Mixin(value = MessageFaceCommand.class, remap = false)
public abstract class NativeFaceCommandMixin {
    @Shadow(remap = false) private UUID group;

    @Inject(method = "executeServerSide", at = @At("TAIL"))
    private void rts$faceFormation(NetworkEvent.Context context, CallbackInfo callback) {
        ServerPlayer sender = context.getSender();
        if (sender != null && group != null) {
            float lockedFacing = sender.getYRot();
            FormationService.rememberFacing(sender.serverLevel(), group, lockedFacing);
            FormationRuntime.faceFormation(group, lockedFacing);
        }
    }
}
