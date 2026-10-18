package ee.lastpagans.recruitsrtscamera.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Mob.class)
public interface MobGoalAccess {
    @Accessor("goalSelector") GoalSelector rts$getGoals();
    @Accessor("targetSelector") GoalSelector rts$getTargets();
}
