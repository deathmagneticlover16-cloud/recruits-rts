package ee.lastpagans.recruitsrtscamera.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Optional accessors for Trotting Wagons' native steering state. */
@Pseudo
@Mixin(targets = "net.favouriteless.trotting_wagons.common.entities.base.AbstractGeckolibVehicle", remap = false)
public interface TrottingVehicleInvoker {
    @Invoker("setSteeringImpulse")
    void recruitsRts$setSteeringImpulse(float impulse);

    @Invoker("isReversing")
    boolean recruitsRts$isReversing();

    @Invoker("toggleReversing")
    void recruitsRts$toggleReversing();
}
