package ee.lastpagans.recruitsrtscamera.mixin;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.util.FormationUtils;
import ee.lastpagans.recruitsrtscamera.formation.FormationIntent;
import ee.lastpagans.recruitsrtscamera.formation.FormationKind;
import ee.lastpagans.recruitsrtscamera.formation.FormationMode;
import ee.lastpagans.recruitsrtscamera.formation.FormationPlan;
import ee.lastpagans.recruitsrtscamera.formation.NativeFormationController;
import ee.lastpagans.recruitsrtscamera.formation.NativeFormationPreferences;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.UUID;

/** Routes native Recruits formation commands through the built-in safe formation runtime. */
@Mixin(value = FormationUtils.class, remap = false)
public abstract class FormationUtilsMixin {
    @Inject(method = "movementFormation(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/List;Lnet/minecraft/world/phys/Vec3;DZ)V", at = @At("TAIL"))
    private static void rts$march(ServerPlayer player, List<AbstractRecruitEntity> units, Vec3 center,
                                  double scale, boolean hold, CallbackInfo callback) {
        apply(FormationKind.MARCH, player, units, center, scale, hold);
    }

    @Inject(method = "lineUpFormation(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/List;Lnet/minecraft/world/phys/Vec3;DZ)V", at = @At("TAIL"))
    private static void rts$line(ServerPlayer player, List<AbstractRecruitEntity> units, Vec3 center,
                                 double scale, boolean hold, CallbackInfo callback) {
        apply(FormationKind.LINE, player, units, center, scale, hold);
    }

    @Inject(method = "squareFormation(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/List;Lnet/minecraft/world/phys/Vec3;DZ)V", at = @At("TAIL"))
    private static void rts$square(ServerPlayer player, List<AbstractRecruitEntity> units, Vec3 center,
                                   double scale, boolean hold, CallbackInfo callback) {
        apply(FormationKind.SQUARE, player, units, center, scale, hold);
    }

    @Inject(method = "triangleFormation(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/List;Lnet/minecraft/world/phys/Vec3;DZ)V", at = @At("TAIL"))
    private static void rts$triangle(ServerPlayer player, List<AbstractRecruitEntity> units, Vec3 center,
                                     double scale, boolean hold, CallbackInfo callback) {
        apply(FormationKind.TRIANGLE, player, units, center, scale, hold);
    }

    @Inject(method = "hollowCircleFormation(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/List;Lnet/minecraft/world/phys/Vec3;DZ)V", at = @At("TAIL"))
    private static void rts$ring(ServerPlayer player, List<AbstractRecruitEntity> units, Vec3 center,
                                 double scale, boolean hold, CallbackInfo callback) {
        apply(FormationKind.RING, player, units, center, scale, hold);
    }

    @Inject(method = "circleFormation(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/List;Lnet/minecraft/world/phys/Vec3;DZ)V", at = @At("TAIL"))
    private static void rts$circle(ServerPlayer player, List<AbstractRecruitEntity> units, Vec3 center,
                                   double scale, boolean hold, CallbackInfo callback) {
        apply(FormationKind.CIRCLE, player, units, center, scale, hold);
    }

    @Inject(method = "hollowSquareFormation(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/List;Lnet/minecraft/world/phys/Vec3;DZ)V", at = @At("TAIL"))
    private static void rts$hollowSquare(ServerPlayer player, List<AbstractRecruitEntity> units, Vec3 center,
                                         double scale, boolean hold, CallbackInfo callback) {
        apply(FormationKind.HOLLOW_SQUARE, player, units, center, scale, hold);
    }

    @Inject(method = "vFormation(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/List;Lnet/minecraft/world/phys/Vec3;DZ)V", at = @At("TAIL"))
    private static void rts$vee(ServerPlayer player, List<AbstractRecruitEntity> units, Vec3 center,
                                double scale, boolean hold, CallbackInfo callback) {
        apply(FormationKind.VEE, player, units, center, scale, hold);
    }

    private static void apply(
            FormationKind kind,
            ServerPlayer player,
            List<AbstractRecruitEntity> units,
            Vec3 center,
            double scale,
            boolean hold
    ) {
        if (player == null || units == null || units.isEmpty() || center == null) return;
        UUID groupId = units.get(0).getGroup();
        if (groupId == null) groupId = player.getUUID();
        int ranks = kind == FormationKind.LINE ? NativeFormationPreferences.lineRanks(player) : 0;
        FormationIntent intent = new FormationIntent(
                FormationMode.PRESET, kind, scale, hold, 0.0D, player.getYRot(), ranks);
        NativeFormationController.execute(player, units, new FormationPlan(groupId, center, intent));
    }
}
