package ee.lastpagans.recruitsrtscamera.mixin;

import ee.lastpagans.recruitsrtscamera.compat.TrottingWagonsDrivingManager;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds Recruit orders into the wagon's own throttle, steering and reverse controls. */
@Pseudo
@Mixin(targets = "net.favouriteless.trotting_wagons.common.entities.base.AbstractWagon", remap = false)
public abstract class TrottingWagonRecruitDriverMixin {
    @Shadow private int speedLevel;
    @Shadow @Final private Mob[] horses;

    @Inject(method = "moveTick", at = @At(value = "INVOKE",
            target = "Lnet/favouriteless/trotting_wagons/common/entities/base/AbstractWagon;handleSteering()V"),
            require = 0, remap = false)
    private void recruitsRts$controlRecruitDriver(CallbackInfo callback) {
        Entity wagon = (Entity) (Object) this;
        LivingEntity passenger = wagon.getControllingPassenger();
        if (passenger == null || passenger instanceof Player || this.horses.length < 2
                || this.horses[0] == null || this.horses[1] == null) return;
        // Do not compete with Knightmare's general Recruit wagon compatibility (or another
        // future controller). RTS owns only its explicitly boarded restored chariots.
        if (!TrottingWagonsDrivingManager.controls(wagon)) return;

        boolean blocked = wagon.horizontalCollision
                || this.horses[0].horizontalCollision || this.horses[1].horizontalCollision;
        TrottingWagonsDrivingManager.WagonControl control =
                TrottingWagonsDrivingManager.nativeControl(wagon, blocked);
        TrottingVehicleInvoker vehicle = (TrottingVehicleInvoker) (Object) this;
        this.speedLevel = Mth.clamp(control.speedLevel(), 0, 2);
        vehicle.recruitsRts$setSteeringImpulse(Mth.clamp(control.steeringImpulse(), -1.0F, 1.0F));
        if (vehicle.recruitsRts$isReversing() != control.reversing()) {
            vehicle.recruitsRts$toggleReversing();
        }
    }
}
