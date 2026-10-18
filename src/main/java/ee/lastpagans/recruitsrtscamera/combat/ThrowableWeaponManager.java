package ee.lastpagans.recruitsrtscamera.combat;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.animation.CommanderAnimationHints;
import ee.lastpagans.recruitsrtscamera.compat.SeriousAnimationsCompat;
import ee.lastpagans.recruitsrtscamera.projectile.ThrownRecruitWeaponEntity;
import ee.lastpagans.recruitsrtscamera.registry.RTSTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Total War-style recruit-thrown weapon support for tagged weapons.
 *
 * <p>0.10.5 makes throwing a two-stage action: first the Recruit begins the authored
 * Serious Animations spear throw, then the projectile is released on that animation's
 * public release tick (11 in SPA 0.8.1). The animation addon remains completely optional;
 * without it Commander retains the same wind-up delay so the gameplay timing is stable.</p>
 */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ThrowableWeaponManager {
    public static final double MIN_THROW_RANGE = 5.0D;
    public static final double MAX_THROW_RANGE = 18.0D;

    private static final int THROW_COOLDOWN_TICKS = 50;
    private static final Map<UUID, Long> LAST_THROW = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingThrow> PENDING = new ConcurrentHashMap<>();

    private ThrowableWeaponManager() {
    }

    public static boolean hasThrowable(AbstractRecruitEntity recruit) {
        if (recruit == null) return false;
        ItemStack stack = recruit.getMainHandItem();
        return stack != null && !stack.isEmpty() && stack.is(RTSTags.THROWABLE_WEAPONS);
    }

    public static boolean isPreparingThrow(AbstractRecruitEntity recruit) {
        return recruit != null && PENDING.containsKey(recruit.getUUID());
    }

    /**
     * Starts a throw if the Recruit is in range. The actual weapon does not leave the hand
     * until the animation release frame. This prevents the old instant projectile pop and
     * gives cinematic volleys a readable wind-up.
     */
    public static boolean tryThrow(AbstractRecruitEntity recruit, LivingEntity target) {
        if (recruit != null && TacticalCombatManager.settings(recruit).holdFire()) return false;
        if (recruit == null || target == null || !recruit.isAlive() || !target.isAlive()
                || recruit.level().isClientSide || !hasThrowable(recruit)
                || !(recruit.level() instanceof ServerLevel level)) {
            return false;
        }
        if (PENDING.containsKey(recruit.getUUID())) {
            return true;
        }

        double distance = recruit.distanceTo(target);
        if (distance < MIN_THROW_RANGE || distance > MAX_THROW_RANGE || !recruit.hasLineOfSight(target)) {
            return false;
        }

        long now = level.getGameTime();
        long last = LAST_THROW.getOrDefault(recruit.getUUID(), Long.MIN_VALUE / 4);
        if (now - last < THROW_COOLDOWN_TICKS) {
            return false;
        }

        int releaseDelay = SeriousAnimationsCompat.spearThrowReleaseTick();
        int duration = SeriousAnimationsCompat.spearThrowDurationTicks();
        CommanderAnimationHints.offer(recruit, CommanderAnimationHints.Hint.SPEAR_THROW, duration);
        SeriousAnimationsCompat.playSpearThrow(recruit);

        PENDING.put(recruit.getUUID(), new PendingThrow(
                level,
                recruit.getUUID(),
                target.getId(),
                now + releaseDelay,
                now + Math.max(duration + 6L, releaseDelay + 6L)
        ));
        // Reserve the cooldown immediately so repeated RTS retarget ticks cannot queue duplicates.
        LAST_THROW.put(recruit.getUUID(), now);
        return true;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty()) return;

        for (Map.Entry<UUID, PendingThrow> entry : List.copyOf(PENDING.entrySet())) {
            PendingThrow pending = entry.getValue();
            ServerLevel level = pending.level;
            long now = level.getGameTime();
            if (now < pending.releaseAt) continue;

            Entity rawRecruit = level.getEntity(pending.recruitId);
            Entity rawTarget = level.getEntity(pending.targetEntityId);
            if (!(rawRecruit instanceof AbstractRecruitEntity recruit)
                    || !recruit.isAlive() || recruit.isRemoved()
                    || !(rawTarget instanceof LivingEntity target)
                    || !target.isAlive() || target.isRemoved()
                    || !hasThrowable(recruit)) {
                PENDING.remove(entry.getKey(), pending);
                continue;
            }

            // The target may have moved during the animation. Recompute the ballistic solution
            // at the release frame rather than firing toward the position from eleven ticks ago.
            launchNow(level, recruit, target);
            PENDING.remove(entry.getKey(), pending);
        }
    }

    private static void launchNow(ServerLevel level, AbstractRecruitEntity recruit, LivingEntity target) {
        if (TacticalCombatManager.settings(recruit).holdFire()
                || ee.lastpagans.recruitsrtscamera.battle.RoutedGroupManager.isRouting(recruit)) return;
        ItemStack held = recruit.getMainHandItem();
        if (held == null || held.isEmpty() || !held.is(RTSTags.THROWABLE_WEAPONS)) return;

        ItemStack projectileStack = held.copyWithCount(1);
        float damage = Math.max(5.0F, Math.min(10.0F, recruit.getAttackDamage() * 0.85F));
        ThrownRecruitWeaponEntity projectile = new ThrownRecruitWeaponEntity(
                level, recruit, projectileStack, damage
        );

        // Old throws originated low enough that short-range shots could intersect the ground.
        // Spawn just above eye/shoulder height and a little forward toward the target.
        Vec3 horizontalToTarget = new Vec3(
                target.getX() - recruit.getX(),
                0.0D,
                target.getZ() - recruit.getZ()
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

        // Aim for the upper torso instead of the bounding-box centre. Combined with a larger
        // ballistic lift this stops pila from immediately burying themselves in the terrain.
        double targetY = target.getY() + target.getBbHeight() * 0.72D;
        Vec3 aim = new Vec3(
                target.getX() - projectile.getX(),
                targetY - projectile.getY(),
                target.getZ() - projectile.getZ()
        );
        double horizontal = Math.hypot(aim.x, aim.z);
        projectile.shoot(aim.x, aim.y + horizontal * 0.085D, aim.z, 1.30F, 1.10F);
        level.addFreshEntity(projectile);

        held.shrink(1);
        if (held.isEmpty()) {
            recruit.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            recruit.tryToReequip(EquipmentSlot.MAINHAND);
        }
    }

    public static void cleanup(UUID recruitId) {
        if (recruitId == null) return;
        LAST_THROW.remove(recruitId);
        PENDING.remove(recruitId);
    }

    private static final class PendingThrow {
        private final ServerLevel level;
        private final UUID recruitId;
        private final int targetEntityId;
        private final long releaseAt;
        @SuppressWarnings("unused")
        private final long expireAt;

        private PendingThrow(
                ServerLevel level,
                UUID recruitId,
                int targetEntityId,
                long releaseAt,
                long expireAt
        ) {
            this.level = level;
            this.recruitId = recruitId;
            this.targetEntityId = targetEntityId;
            this.releaseAt = releaseAt;
            this.expireAt = expireAt;
        }
    }
}
