package ee.lastpagans.recruitsrtscamera.combat;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.Vec3;

/** Applies the attack order's per-archer target to the arrow's ballistic solution. */
public final class RangedProjectileArc {
    private static final double ARROW_GRAVITY = 0.05D;

    private RangedProjectileArc() { }

    public static void apply(AbstractArrow arrow, AbstractRecruitEntity shooter, LivingEntity target) {
        if (arrow == null || shooter == null || target == null || !target.isAlive()) return;
        Vec3 current = arrow.getDeltaMovement();
        double horizontalSpeed = Math.hypot(current.x, current.z);
        if (horizontalSpeed < 0.10D) return;
        Vec3 aim = new Vec3(target.getX(), target.getY(0.68D), target.getZ()).subtract(arrow.position());
        BallisticArc.Velocity solution = BallisticArc.solve(
                aim.x, aim.y, aim.z, horizontalSpeed, ARROW_GRAVITY);
        arrow.setDeltaMovement(solution.x(), solution.y(), solution.z());
    }
}
