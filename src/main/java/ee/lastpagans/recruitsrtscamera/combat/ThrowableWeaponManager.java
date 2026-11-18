package ee.lastpagans.recruitsrtscamera.combat;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.BowmanEntity;
import com.talhanation.recruits.entities.CrossBowmanEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.animation.CommanderAnimationHints;
import ee.lastpagans.recruitsrtscamera.compat.SeriousAnimationsCompat;
import ee.lastpagans.recruitsrtscamera.projectile.ThrownRecruitWeaponEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Total War-style recruit-thrown weapon support for tagged weapons.
 *
 * <p>Throwing is a two-stage action: the Recruit begins the optional authored Serious
 * Animations spear throw, then Commander releases the projectile on a deliberately short,
 * formation-synchronized snap-throw frame. The animation addon remains optional.</p>
 */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ThrowableWeaponManager {
    private static final ResourceLocation STEEL_JAVELIN = new ResourceLocation(
            "darkagesarmory", "steel_javelin");
    public static final double MIN_THROW_RANGE = 5.0D;
    public static final double MAX_THROW_RANGE = 18.0D;

    /** A readable snap-throw followed by an actual reload, rather than one long wind-up. */
    private static final int THROW_WINDUP_TICKS = 6;
    private static final int THROW_COOLDOWN_TICKS = 28;
    private static final Map<UUID, Long> LAST_THROW = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingThrow> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, GroundOrder> GROUND_ORDERS = new ConcurrentHashMap<>();

    private ThrowableWeaponManager() {
    }

    public static boolean hasThrowable(AbstractRecruitEntity recruit) {
        if (recruit == null) return false;
        if (isSupportedThrowable(recruit.getMainHandItem())) return true;
        // Missile specialists may keep replacement javelin stacks in their inventory.
        // Ordinary infantry still has to equip one explicitly before RTS will throw it.
        return (recruit instanceof BowmanEntity || recruit instanceof CrossBowmanEntity)
                && !findThrowable(recruit).stack().isEmpty();
    }

    /** Deliberately narrow integration: no spear or tag extension becomes throwable. */
    public static boolean isSupportedThrowable(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && STEEL_JAVELIN.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    public static boolean isPreparingThrow(AbstractRecruitEntity recruit) {
        return recruit != null && PENDING.containsKey(recruit.getUUID());
    }

    public static boolean ownsThrowCycle(AbstractRecruitEntity recruit) {
        return recruit != null && (PENDING.containsKey(recruit.getUUID())
                || GROUND_ORDERS.containsKey(recruit.getUUID()));
    }

    /**
     * Starts a throw if the Recruit is in range. The actual weapon does not leave the hand
     * until the short release frame. This prevents an instant projectile pop without making
     * a javelin rank spend most of its combat cycle winding up.
     */
    public static boolean tryThrow(AbstractRecruitEntity recruit, LivingEntity target) {
        if (recruit != null && TacticalCombatManager.settings(recruit).holdFire()
                && !TacticalCombatManager.explicitFireOrder(recruit)) return false;
        if (recruit == null || target == null || !recruit.isAlive() || !target.isAlive()
                || recruit.level().isClientSide || !hasThrowable(recruit)
                || !(recruit.level() instanceof ServerLevel level)) {
            return false;
        }
        double distance = recruit.distanceTo(target);
        if (distance < MIN_THROW_RANGE || distance > MAX_THROW_RANGE || !recruit.hasLineOfSight(target)) {
            return false;
        }

        return queueThrow(level, recruit, target.getId(), null);
    }

    /** Persistent F-order for synchronized javelin volleys into an exact ground area. */
    public static boolean designateStrategicFire(AbstractRecruitEntity recruit, Vec3 target) {
        if (recruit == null || target == null || !recruit.isAlive() || recruit.level().isClientSide
                || !(recruit.level() instanceof ServerLevel level)
                || !Double.isFinite(target.x) || !Double.isFinite(target.y) || !Double.isFinite(target.z)
                || !hasThrowable(recruit)) return false;
        double distance = recruit.position().distanceTo(target);
        if (distance < MIN_THROW_RANGE || distance > MAX_THROW_RANGE) return false;
        GroundOrder previous = GROUND_ORDERS.put(recruit.getUUID(),
                new GroundOrder(level, recruit.getUUID(), target));
        if (previous != null && previous.target.distanceToSqr(target) > 0.01D) {
            // A fresh F designation supersedes an animation aimed at the old ground point.
            PENDING.remove(recruit.getUUID());
            LAST_THROW.remove(recruit.getUUID());
        }
        queueThrow(level, recruit, -1, target);
        return true;
    }

    private static boolean queueThrow(ServerLevel level, AbstractRecruitEntity recruit,
                                      int targetEntityId, Vec3 targetPoint) {
        if (PENDING.containsKey(recruit.getUUID())) return true;
        if (!prepareThrowable(recruit)) return false;

        long now = level.getGameTime();
        long last = LAST_THROW.getOrDefault(recruit.getUUID(), Long.MIN_VALUE / 4);
        if (now - last < THROW_COOLDOWN_TICKS) {
            return false;
        }

        int releaseDelay = Math.min(THROW_WINDUP_TICKS,
                SeriousAnimationsCompat.spearThrowReleaseTick());
        int duration = Math.min(20, SeriousAnimationsCompat.spearThrowDurationTicks());
        long sharedRelease = FormationVolleyManager.reserveJavelinRelease(
                recruit, releaseDelay, THROW_COOLDOWN_TICKS);
        if (sharedRelease < 0L) return false;
        CommanderAnimationHints.offer(recruit, CommanderAnimationHints.Hint.SPEAR_THROW, duration);
        SeriousAnimationsCompat.playSpearThrow(recruit);

        PENDING.put(recruit.getUUID(), new PendingThrow(
                level,
                recruit.getUUID(),
                targetEntityId,
                targetPoint,
                sharedRelease,
                now + Math.max(duration + 6L, releaseDelay + 6L)
        ));
        // Reserve the cooldown immediately so repeated RTS retarget ticks cannot queue duplicates.
        LAST_THROW.put(recruit.getUUID(), now);
        return true;
    }

    public static void cancel(AbstractRecruitEntity recruit) {
        if (recruit == null) return;
        PENDING.remove(recruit.getUUID());
        GROUND_ORDERS.remove(recruit.getUUID());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END
                || (PENDING.isEmpty() && GROUND_ORDERS.isEmpty())) return;

        for (Map.Entry<UUID, PendingThrow> entry : List.copyOf(PENDING.entrySet())) {
            PendingThrow pending = entry.getValue();
            ServerLevel level = pending.level;
            long now = level.getGameTime();
            if (now < pending.releaseAt) continue;

            Entity rawRecruit = level.getEntity(pending.recruitId);
            if (!(rawRecruit instanceof AbstractRecruitEntity recruit)
                    || !recruit.isAlive() || recruit.isRemoved()
                    || now > pending.expireAt
                    || (pending.targetEntityId < 0 && !StrategicFireManager.hasOrder(recruit))
                    || findThrowable(recruit).stack().isEmpty()) {
                PENDING.remove(entry.getKey(), pending);
                continue;
            }

            Vec3 aimPoint = pending.targetPoint;
            if (pending.targetEntityId >= 0) {
                Entity rawTarget = level.getEntity(pending.targetEntityId);
                if (!(rawTarget instanceof LivingEntity target)
                        || !target.isAlive() || target.isRemoved()) {
                    PENDING.remove(entry.getKey(), pending);
                    continue;
                }
                // The target may have moved during the animation. Recompute at release.
                aimPoint = new Vec3(target.getX(),
                        target.getY() + target.getBbHeight() * 0.72D, target.getZ());
            }
            launchNow(level, recruit, aimPoint);
            PENDING.remove(entry.getKey(), pending);
        }

        for (Map.Entry<UUID, GroundOrder> entry : List.copyOf(GROUND_ORDERS.entrySet())) {
            GroundOrder order = entry.getValue();
            Entity rawRecruit = order.level.getEntity(order.recruitId);
            if (!(rawRecruit instanceof AbstractRecruitEntity recruit)
                    || !recruit.isAlive() || recruit.isRemoved()
                    || !StrategicFireManager.hasOrder(recruit) || !hasThrowable(recruit)) {
                GROUND_ORDERS.remove(entry.getKey(), order);
                continue;
            }
            double distance = recruit.position().distanceTo(order.target);
            if (distance >= MIN_THROW_RANGE && distance <= MAX_THROW_RANGE) {
                queueThrow(order.level, recruit, -1, order.target);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStop(ServerStoppedEvent event) {
        LAST_THROW.clear();
        PENDING.clear();
        GROUND_ORDERS.clear();
    }

    private static void launchNow(ServerLevel level, AbstractRecruitEntity recruit, Vec3 target) {
        if (TacticalCombatManager.settings(recruit).holdFire()
                && !TacticalCombatManager.explicitFireOrder(recruit)
                || ee.lastpagans.recruitsrtscamera.battle.RoutedGroupManager.isRouting(recruit)) return;
        ItemSource source = findThrowable(recruit);
        ItemStack held = source.stack();
        if (held.isEmpty()) return;

        ItemStack projectileStack = held.copyWithCount(1);
        float damage = Math.max(5.0F, Math.min(10.0F, recruit.getAttackDamage() * 0.85F));
        ThrownRecruitWeaponEntity projectile = new ThrownRecruitWeaponEntity(
                level, recruit, projectileStack, damage
        );

        // Old throws originated low enough that short-range shots could intersect the ground.
        // Spawn just above eye/shoulder height and a little forward toward the target.
        Vec3 horizontalToTarget = new Vec3(
                target.x - recruit.getX(),
                0.0D,
                target.z - recruit.getZ()
        );
        Vec3 forward = horizontalToTarget.lengthSqr() > 1.0E-6D
                ? horizontalToTarget.normalize()
                : new Vec3(0.0D, 0.0D, 1.0D);
        double launchY = recruit.getEyeY() + 0.24D;
        projectile.setPos(
                recruit.getX() + forward.x * 0.34D,
                launchY,
                recruit.getZ() + forward.z * 0.34D
        );

        // Direct throws arrive at the upper torso; strategic throws arrive just above their
        // assigned patch of ground. The lift keeps both from burying themselves immediately.
        Vec3 aim = new Vec3(
                target.x - projectile.getX(),
                target.y + 0.12D - projectile.getY(),
                target.z - projectile.getZ()
        );
        double horizontal = Math.hypot(aim.x, aim.z);
        projectile.shoot(aim.x, aim.y + horizontal * 0.085D, aim.z, 1.30F, 1.10F);
        level.addFreshEntity(projectile);

        held.shrink(1);
        if (source.mainHand() && held.isEmpty()) {
            recruit.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            recruit.tryToReequip(EquipmentSlot.MAINHAND);
        } else if (!source.mainHand()) {
            recruit.getInventory().setChanged();
        }
    }

    private static boolean prepareThrowable(AbstractRecruitEntity recruit) {
        if (isSupportedThrowable(recruit.getMainHandItem())) return true;
        recruit.switchMainHandItem(ThrowableWeaponManager::isSupportedThrowable);
        return !findThrowable(recruit).stack().isEmpty();
    }

    /**
     * Keeps the supported javelin out of the defensive hand even if another addon bypasses
     * Recruits' shield-only inventory slot. No item is deleted when both hands are occupied.
     */
    public static void relocateOffhandJavelin(AbstractRecruitEntity recruit) {
        if (recruit == null || !isSupportedThrowable(recruit.getOffhandItem())) return;
        ItemStack javelin = recruit.getOffhandItem().copy();
        recruit.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        if (recruit.getMainHandItem().isEmpty()) {
            recruit.setItemInHand(InteractionHand.MAIN_HAND, javelin);
            return;
        }
        ItemStack remainder = recruit.getInventory().addItem(javelin);
        recruit.getInventory().setChanged();
        if (!remainder.isEmpty()) recruit.spawnAtLocation(remainder);
    }

    private static ItemSource findThrowable(AbstractRecruitEntity recruit) {
        if (recruit == null) return ItemSource.EMPTY;
        ItemStack mainHand = recruit.getMainHandItem();
        if (isSupportedThrowable(mainHand)) return new ItemSource(mainHand, true);
        SimpleContainer inventory = recruit.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack candidate = inventory.getItem(slot);
            if (isSupportedThrowable(candidate)) return new ItemSource(candidate, false);
        }
        return ItemSource.EMPTY;
    }

    public static void cleanup(UUID recruitId) {
        if (recruitId == null) return;
        LAST_THROW.remove(recruitId);
        PENDING.remove(recruitId);
        GROUND_ORDERS.remove(recruitId);
    }

    private static final class PendingThrow {
        private final ServerLevel level;
        private final UUID recruitId;
        private final int targetEntityId;
        private final Vec3 targetPoint;
        private final long releaseAt;
        private final long expireAt;

        private PendingThrow(
                ServerLevel level,
                UUID recruitId,
                int targetEntityId,
                Vec3 targetPoint,
                long releaseAt,
                long expireAt
        ) {
            this.level = level;
            this.recruitId = recruitId;
            this.targetEntityId = targetEntityId;
            this.targetPoint = targetPoint;
            this.releaseAt = releaseAt;
            this.expireAt = expireAt;
        }
    }

    private record GroundOrder(ServerLevel level, UUID recruitId, Vec3 target) { }

    private record ItemSource(ItemStack stack, boolean mainHand) {
        private static final ItemSource EMPTY = new ItemSource(ItemStack.EMPTY, false);
    }
}
