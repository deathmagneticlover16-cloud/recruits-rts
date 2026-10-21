package ee.lastpagans.recruitsrtscamera.combat;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.IRangedRecruit;
import com.talhanation.recruits.entities.SiegeEngineerEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.battle.RoutedGroupManager;
import ee.lastpagans.recruitsrtscamera.battle.BattleMechanicsManager;
import ee.lastpagans.recruitsrtscamera.persistence.RTSGroupTacticsSavedData;
import ee.lastpagans.recruitsrtscamera.registry.RTSTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.*;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Server-owned target leases prevent native retaliation/LOS searches from collapsing a frontage. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID)
public final class TacticalCombatManager {
    private static final Map<UUID, Lease> LOCKS = new ConcurrentHashMap<>();
    private static final UUID CHARGE_SPEED = UUID.fromString("e238b2c5-1c46-4aef-83de-e2cf59a920ee");
    public static final int CHARGE_ORDER_TICKS = 20 * 30;
    private static final Map<UUID, ShockWindow> LAST_CHARGE_SHOCK = new HashMap<>();
    private TacticalCombatManager() {}
    private record Lease(AbstractRecruitEntity recruit, UUID group, LivingEntity target, long expires) {}

    public static TacticalSettings settings(AbstractRecruitEntity recruit) {
        return recruit.level() instanceof ServerLevel level && recruit.getGroup() != null
                ? RTSGroupTacticsSavedData.get(level).settings(recruit.getGroup()) : TacticalSettings.DEFAULT;
    }
    public static boolean ranged(AbstractRecruitEntity recruit) {
        return recruit instanceof IRangedRecruit && settings(recruit).combat() != TacticalSettings.CombatMode.MELEE
                && (settings(recruit).combat() == TacticalSettings.CombatMode.RANGED
                || settings(recruit).holdFire() || recruit.getShouldRanged());
    }
    public static void lock(AbstractRecruitEntity recruit, UUID group, LivingEntity target) {
        if (RoutedGroupManager.isRouting(recruit)) return;
        LOCKS.put(recruit.getUUID(), new Lease(recruit, group, target, recruit.level().getGameTime() + 20));
        if (target != null && settings(recruit).skirmish() && ranged(recruit)
                && !(recruit instanceof SiegeEngineerEntity)) SkirmishController.attach(recruit);
    }
    public static boolean ownsTarget(AbstractRecruitEntity recruit) {
        Lease lease = LOCKS.get(recruit.getUUID());
        return lease != null && lease.expires >= recruit.level().getGameTime() && !RoutedGroupManager.isRouting(recruit);
    }
    public static LivingEntity assignedTarget(AbstractRecruitEntity recruit) {
        Lease lease = LOCKS.get(recruit.getUUID());
        if (lease == null || !ownsTarget(recruit)) return null;
        var target = lease.target;
        return target != null && target.isAlive() && !target.isRemoved() && target.level() == recruit.level()
                && recruit.canAttack(target) && !recruit.isAlliedTo(target) ? target : null;
    }
    public static void cancelGroup(UUID group) {
        for (Lease lease : List.copyOf(LOCKS.values())) if (group.equals(lease.group)) remove(lease);
    }
    private static void remove(Lease lease) {
        LOCKS.remove(lease.recruit.getUUID(), lease);
        SkirmishController.remove(lease.recruit);
        var speed = lease.recruit.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(CHARGE_SPEED);
        if (!lease.recruit.isRemoved() && !RoutedGroupManager.isRouting(lease.recruit)) {
            lease.recruit.getNavigation().stop();
            lease.recruit.setTarget(null);
        }
    }
    public static void applyMode(AbstractRecruitEntity recruit) {
        if (!(recruit.level() instanceof ServerLevel) || RoutedGroupManager.isRouting(recruit)) return;
        var s = settings(recruit);
        if (recruit.getPersistentData().getBoolean(TacticalOrders.DISMOUNTED)) recruit.setShouldMount(false);
        if (recruit instanceof IRangedRecruit ranged && !(recruit instanceof SiegeEngineerEntity)) {
            if (s.combat() == TacticalSettings.CombatMode.MELEE) {
                recruit.setShouldRanged(false);
                if (ranged.getWeaponType().test(recruit.getMainHandItem())) {
                    recruit.stopUsingItem();
                    recruit.switchMainHandItem(stack -> stack.getItem() instanceof SwordItem
                            || stack.getItem() instanceof AxeItem || stack.getItem() instanceof TridentItem
                            || stack.is(RTSTags.THROWABLE_WEAPONS));
                }
            } else if (s.combat() == TacticalSettings.CombatMode.RANGED) {
                recruit.setShouldRanged(true);
            }
            if (s.holdFire()) {
                recruit.setShouldRanged(false);
                if (recruit.isUsingItem() && ranged.getWeaponType().test(recruit.getUseItem())) recruit.stopUsingItem();
            }
        }
        var speed = recruit.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            long now = recruit.level().getGameTime();
            boolean charging = ownsTarget(recruit) && recruit.getVehicle() == null && !ranged(recruit)
                    && RTSGroupTacticsSavedData.get((ServerLevel) recruit.level()).timer(recruit.getGroup(), "chargeUntil") > now;
            if (charging) {
                double amount = chargeAcceleration(recruit) - 1.0D;
                AttributeModifier old = speed.getModifier(CHARGE_SPEED);
                if (old == null || Math.abs(old.getAmount() - amount) > 0.001D) {
                    speed.removeModifier(CHARGE_SPEED);
                    speed.addTransientModifier(new AttributeModifier(CHARGE_SPEED,
                            "Commander progressive charge", amount, AttributeModifier.Operation.MULTIPLY_TOTAL));
                }
            } else speed.removeModifier(CHARGE_SPEED);
        }
    }

    /** A sustained charge grows from +10% to +40% over twelve seconds. */
    public static double chargeAcceleration(AbstractRecruitEntity recruit) {
        if (recruit == null || recruit.getGroup() == null || !(recruit.level() instanceof ServerLevel level)) return 1.0D;
        long now = level.getGameTime();
        long until = RTSGroupTacticsSavedData.get(level).timer(recruit.getGroup(), "chargeUntil");
        if (until <= now) return 1.0D;
        long elapsed = Math.max(0L, CHARGE_ORDER_TICKS - (until - now));
        double progress = Math.min(1.0D, elapsed / (20.0D * 12.0D));
        return 1.10D + 0.30D * progress;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onChargeDamage(LivingDamageEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level) || event.getAmount() <= 0.0F
                || !(event.getSource().getEntity() instanceof AbstractRecruitEntity attacker)
                || attacker.getVehicle() != null || ranged(attacker)
                || !(event.getEntity() instanceof AbstractRecruitEntity victim) || victim.getGroup() == null
                || attacker.getGroup() == null || attacker.isAlliedTo(victim)) return;
        long now = level.getGameTime();
        if (RTSGroupTacticsSavedData.get(level).timer(attacker.getGroup(), "chargeUntil") <= now) return;
        if (LAST_CHARGE_SHOCK.size() > 256) {
            LAST_CHARGE_SHOCK.values().removeIf(window -> now - window.started > 200L);
        }
        double progress = Math.max(0.0D, Math.min(1.0D, (chargeAcceleration(attacker) - 1.10D) / 0.30D));
        double desired = 8.0D * (0.35D + 0.65D * progress) * (rearImpact(attacker, victim) ? 2.0D : 1.0D);
        ShockWindow window = LAST_CHARGE_SHOCK.computeIfAbsent(victim.getGroup(), ignored -> new ShockWindow(now));
        if (now - window.started >= 40L) {
            window.started = now;
            window.applied = 0.0D;
        }
        double extra = Math.max(0.0D, desired - window.applied);
        window.applied = Math.max(window.applied, desired);
        if (extra > 0.0D) BattleMechanicsManager.adjustGroupMorale(level, victim.getGroup(), (float) -extra);
    }

    public static boolean rearImpact(LivingEntity attacker, LivingEntity victim) {
        if (attacker == null || victim == null) return false;
        Vec3 toAttacker = attacker.position().subtract(victim.position()).multiply(1.0D, 0.0D, 1.0D);
        if (toAttacker.lengthSqr() < 1.0E-6D) return false;
        double yaw = Math.toRadians(victim.getYRot());
        Vec3 victimForward = new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw));
        return victimForward.dot(toAttacker.normalize()) < -0.50D;
    }
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onTarget(LivingChangeTargetEvent event) {
        if (event.getEntity() instanceof AbstractRecruitEntity recruit && ownsTarget(recruit)) {
            // Routing's LOWEST target veto runs after this lease; no target can revive a routed unit.
            event.setNewTarget(assignedTarget(recruit));
        }
    }
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onProjectile(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof Projectile projectile
                && projectile.getOwner() instanceof AbstractRecruitEntity shooter) {
            var s = settings(shooter);
            if (s.holdFire() || s.combat() == TacticalSettings.CombatMode.MELEE && shooter instanceof IRangedRecruit)
                event.setCanceled(true);
        }
    }
    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (event.getEntity() instanceof AbstractRecruitEntity recruit && !recruit.level().isClientSide
                && recruit.tickCount % 5 == Math.floorMod(recruit.getId(), 5)) applyMode(recruit);
    }
    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        for (Lease lease : List.copyOf(LOCKS.values()))
            if (!lease.recruit.isAlive() || lease.recruit.isRemoved() || RoutedGroupManager.isRouting(lease.recruit)
                    || lease.expires < lease.recruit.level().getGameTime()) remove(lease);
    }
    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof AbstractRecruitEntity recruit) {
            Lease lease = LOCKS.get(recruit.getUUID());
            if (lease != null) remove(lease);
        }
    }
    @SubscribeEvent
    public static void onStop(ServerStoppedEvent event) {
        LOCKS.clear();
        LAST_CHARGE_SHOCK.clear();
        SkirmishController.clear();
    }

    private static final class ShockWindow {
        long started;
        double applied;
        ShockWindow(long started) { this.started = started; }
    }
}
