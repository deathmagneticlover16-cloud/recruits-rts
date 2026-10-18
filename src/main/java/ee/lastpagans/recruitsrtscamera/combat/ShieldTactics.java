package ee.lastpagans.recruitsrtscamera.combat;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.battle.RoutedGroupManager;
import ee.lastpagans.recruitsrtscamera.projectile.ThrownRecruitWeaponEntity;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** FRONT uses vanilla directional shield blocking; UP additionally intercepts overhead missiles. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID)
public final class ShieldTactics {
    private static final String USING = "RTSTacticalShield";
    private static final String UP = "RTSShieldUp";
    private ShieldTactics() {}
    public static boolean controls(AbstractRecruitEntity recruit) {
        return !recruit.level().isClientSide && !RoutedGroupManager.isRouting(recruit)
                && (TacticalCombatManager.settings(recruit).shield() != TacticalSettings.ShieldMode.NATIVE
                || ProjectileAwarenessManager.isUnderFire(recruit));
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof AbstractRecruitEntity recruit) || recruit.level().isClientSide) return;
        boolean controls = controls(recruit);
        if (!controls || !recruit.canBlock()) { release(recruit); return; }
        if (!recruit.getOffhandItem().canPerformAction(ToolActions.SHIELD_BLOCK)) {
            if (recruit.tickCount % 20 == 0) recruit.switchOffHandItem(s -> s.canPerformAction(ToolActions.SHIELD_BLOCK));
            if (!recruit.getOffhandItem().canPerformAction(ToolActions.SHIELD_BLOCK)) { release(recruit); return; }
        }
        ProjectileAwarenessManager.Threat threat = ProjectileAwarenessManager.threat(recruit);
        var mode = TacticalCombatManager.settings(recruit).shield();
        boolean up = mode == TacticalSettings.ShieldMode.UP
                || threat != null && threat.overhead();
        LivingEntity target = recruit.getTarget();
        boolean front = target != null && target.isAlive() && recruit.distanceToSqr(target) < 36
                && (recruit.attackCooldown > 6 || target.swinging);
        boolean raise = threat != null || up || front;
        // Leave food, potions, bows, and throw animations in charge of their own item use.
        if (recruit.isUsingItem() && recruit.getUsedItemHand() != InteractionHand.OFF_HAND) raise = false;
        if (!raise) { release(recruit); return; }
        if (threat != null && !threat.overhead()) {
            recruit.getLookControl().setLookAt(threat.source().x, recruit.getEyeY(), threat.source().z, 45, 35);
        } else if (!up && target != null) {
            recruit.getLookControl().setLookAt(target, 30, 30);
        }
        if (!recruit.isUsingItem()) recruit.startUsingItem(InteractionHand.OFF_HAND);
        recruit.getPersistentData().putBoolean(USING, true);
        recruit.getPersistentData().putBoolean(UP, up);
    }
    private static void release(AbstractRecruitEntity recruit) {
        if (recruit.getPersistentData().getBoolean(USING) && recruit.isUsingItem()
                && recruit.getUsedItemHand() == InteractionHand.OFF_HAND
                && recruit.getUseItem().canPerformAction(ToolActions.SHIELD_BLOCK)) recruit.stopUsingItem();
        recruit.getPersistentData().remove(USING); recruit.getPersistentData().remove(UP);
    }
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void overhead(LivingAttackEvent event) {
        if (!(event.getEntity() instanceof AbstractRecruitEntity recruit) || !controls(recruit)
                || !recruit.getPersistentData().getBoolean(UP) || !recruit.canBlock()
                || !recruit.isBlocking() || event.getSource().is(DamageTypeTags.BYPASSES_SHIELD)
                || !(event.getSource().getDirectEntity() instanceof Projectile projectile)
                || !(projectile instanceof AbstractArrow || projectile instanceof ThrownRecruitWeaponEntity)
                || projectile instanceof AbstractArrow arrow && arrow.getPierceLevel() > 0) return;
        Vec3 velocity = projectile.getDeltaMovement();
        // Front/side/rear horizontal fire still goes through vanilla directional block checks.
        if (velocity.y >= -0.08 || -velocity.y < Math.hypot(velocity.x, velocity.z) * 0.35) return;
        event.setCanceled(true);
        recruit.getUseItem().hurtAndBreak(Math.max(1, (int) Math.ceil(event.getAmount())), recruit,
                e -> e.broadcastBreakEvent(InteractionHand.OFF_HAND));
        projectile.discard();
    }
}
