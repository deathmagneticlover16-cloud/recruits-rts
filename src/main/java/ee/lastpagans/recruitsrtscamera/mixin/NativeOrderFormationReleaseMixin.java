package ee.lastpagans.recruitsrtscamera.mixin;

import com.talhanation.recruits.network.MessageUpkeepEntity;
import com.talhanation.recruits.network.MessageUpkeepPos;
import ee.lastpagans.recruitsrtscamera.formation.FormationRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/** Upkeep commands explicitly return movement authority to native Recruits. */
@Mixin(value = {MessageUpkeepEntity.class, MessageUpkeepPos.class}, remap = false)
public abstract class NativeOrderFormationReleaseMixin {
    @Shadow(remap = false) private UUID group;

    @Inject(method = "executeServerSide", at = @At("TAIL"))
    private void rts$releaseFormation(NetworkEvent.Context context, CallbackInfo callback) {
        ServerPlayer sender = context.getSender();
        if (sender != null && group != null) FormationRuntime.releaseForAttack(sender, group);
    }
}
