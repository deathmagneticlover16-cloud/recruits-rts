package ee.lastpagans.recruitsrtscamera.combat;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.IRangedRecruit;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.battle.BattleMechanicsManager;
import ee.lastpagans.recruitsrtscamera.battle.RoutedGroupManager;
import ee.lastpagans.recruitsrtscamera.compat.SmallShipsCompat;
import ee.lastpagans.recruitsrtscamera.config.RTSServerConfig;
import ee.lastpagans.recruitsrtscamera.movement.RTSMovementBoostManager;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitRuntimeRegistry;
import ee.lastpagans.recruitsrtscamera.troops.TroopStatService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Actual mount movement drives shock damage; infantry and ships never receive a cavalry bonus. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CavalryCombatManager {
    private static final UUID SPEED_ID = UUID.fromString("fe74a3c8-728f-4d41-bb90-3014d6a5db0b");
    private static final UUID MOUNT_HEALTH_ID = UUID.fromString("a7d39069-9e55-4ae3-a511-d36715156c92");
    private static final double MOUNT_HEALTH_BONUS = 0.75D;
    private static final double MAX_MOUNTED_SPEED_MULTIPLIER = 1.25D;
    private static final Map<UUID, RiderState> RIDERS = new HashMap<>();
    private static final Map<UUID, ShockWindow> LAST_SHOCK = new HashMap<>();
    private static final Map<UUID, Long> LAST_PRESSURE = new HashMap<>();

    private CavalryCombatManager() { }

    public static boolean isMounted(AbstractRecruitEntity recruit) {
        return recruit != null && recruit.getVehicle() instanceof Mob mount && mount.isAlive()
                && mount.getFirstPassenger() == recruit
                && !SmallShipsCompat.isShipRecruit(recruit);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        Set<UUID> seen = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (AbstractRecruitEntity rider : RecruitRuntimeRegistry.loaded(level)) {
                if (!rider.isAlive()
                        || rider.getOwnerUUID() == null || !isMounted(rider) || RoutedGroupManager.isRouting(rider)) continue;
                UUID id = rider.getUUID();
                Mob mount = (Mob) rider.getVehicle();
                seen.add(id);
                RiderState state = RIDERS.get(id);
                if (state == null || state.mount != mount || state.level != level) {
                    if (state != null) removeMountModifiers(state.mount);
                    state = new RiderState(mount, level);
                    RIDERS.put(id, state);
                    applyMountedHealth(mount);
                }
                // runtime formation pacing already has a separate attribute/stride pass. Do not
                // multiply it a second time. Outside formations this affects native horse AI.
                double speed = rider.getShouldMovePos() || rider.getShouldHoldPos()
                        || RTSMovementBoostManager.isSlowMarchActive(rider.getGroup())
                        ? 1.0D : Math.min(MAX_MOUNTED_SPEED_MULTIPLIER,
                        Math.max(1.0D, RTSServerConfig.CAVALRY_COMBAT_SPEED_MULTIPLIER.get()));
                setSpeed(mount, speed - 1.0D);
                sample(rider, state);
            }
        }
        for (UUID id : List.copyOf(RIDERS.keySet())) {
            if (!seen.contains(id)) removeMountModifiers(RIDERS.remove(id).mount);
        }
        long now = server.overworld().getGameTime();
        if (now % 200 == 0) {
            LAST_SHOCK.values().removeIf(window -> now - window.started > 200);
            LAST_PRESSURE.values().removeIf(tick -> now - tick > 200);
        }
    }

    private static void sample(AbstractRecruitEntity rider, RiderState state) {
        Vec3 current = state.mount.position();
        Vec3 previous = state.position;
        state.position = current;
        state.sampleTick = state.level.getGameTime();
        if (previous == null) return;
        Vec3 movement = horizontal(current.subtract(previous));
        double moved = movement.length();
        Vec3 heading = moved > 0.001D ? movement.scale(1.0D / moved) : Vec3.ZERO;
        LivingEntity target = rider.getTarget();
        boolean attacking = target != null && target.isAlive() && !(rider instanceof IRangedRecruit && rider.getShouldRanged())
                && !rider.isAlliedTo(target) && rider.canAttack(target);
        Vec3 toward = target == null ? Vec3.ZERO : horizontal(target.position().subtract(current));
        boolean forward = (attacking && (toward.lengthSqr() < 0.04D || heading.dot(toward.normalize()) > 0.25D))
                || state.momentum.hasImpactMomentum();
        double dot = state.heading == null ? 1.0D : heading.dot(state.heading);
        boolean near = attacking && rider.distanceToSqr(target) < 36.0D;
        if (!near && state.momentum.hasImpactMomentum()) {
            near = !state.level.getEntitiesOfClass(AbstractRecruitEntity.class,
                    state.mount.getBoundingBox().inflate(6.0D, 2.0D, 6.0D),
                    enemy -> validFootEnemy(rider, enemy)).isEmpty();
        }
        state.momentum.step(moved, dot, forward, near, state.mount.onGround() && !state.mount.isInWater(),
                RTSServerConfig.CAVALRY_FULL_CHARGE_SPEED.get(), RTSServerConfig.CAVALRY_CHARGE_RUNUP.get());
        if (state.momentum.quality() <= 0.0D && !near) state.victims.clear();
        state.heading = heading;
        if (forward && moved <= 2.5D && state.momentum.quality() > 0.0D
                && RTSServerConfig.CAVALRY_CHARGE_DAMAGE.get() > 0.0D) {
            sweepImpact(rider, state, previous, current);
        }
    }

    private static void sweepImpact(AbstractRecruitEntity rider, RiderState state, Vec3 from, Vec3 to) {
        AABB swept = state.mount.getBoundingBox().expandTowards(from.subtract(to)).inflate(0.20D, 0.0D, 0.20D);
        List<AbstractRecruitEntity> victims = state.level.getEntitiesOfClass(AbstractRecruitEntity.class, swept,
                victim -> validFootEnemy(rider, victim) && !state.victims.contains(victim.getUUID()));
        victims.sort(Comparator.comparingDouble(victim -> victim.distanceToSqr(from)));
        double radius = state.mount.getBbWidth() * 0.5D + 0.20D;
        for (AbstractRecruitEntity victim : victims) {
            if (state.momentum.quality() <= 0.0D) break;
            // Broad-phase AABB is followed by an actual horizontal swept-body intersection.
            AABB body = victim.getBoundingBox().inflate(radius, 0.0D, radius);
            double y = Math.max(body.minY + 0.05D, Math.min(body.maxY - 0.05D, to.y + 0.65D));
            Vec3 start = new Vec3(from.x, y, from.z);
            Vec3 end = new Vec3(to.x, y, to.z);
            if (!body.contains(start) && !body.contains(end) && body.clip(start, end).isEmpty()) continue;
            if (!facingImpact(state, victim) || !rider.hasLineOfSight(victim)) continue;
            double quality = takeImpact(state, victim);
            state.physicalImpact = true;
            try {
                float amount = (float) (RTSServerConfig.CAVALRY_CHARGE_DAMAGE.get() * quality
                        * TroopStatService.chargeMultiplier(rider));
                if (victim.hurt(state.level.damageSources().mobAttack(rider), amount)) {
                    victim.knockback(0.5D + quality * 0.7D, -state.heading.x, -state.heading.z);
                }
            } finally {
                state.physicalImpact = false;
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onHurt(LivingHurtEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel)
                || event.getAmount() <= 0.0F
                || !(event.getSource().getEntity() instanceof AbstractRecruitEntity rider)
                || event.getSource().getDirectEntity() != rider || !isMounted(rider)
                || (rider instanceof IRangedRecruit && rider.getShouldRanged()) || RoutedGroupManager.isRouting(rider)
                || !(event.getEntity() instanceof AbstractRecruitEntity victim) || !validFootEnemy(rider, victim)) return;
        RiderState state = RIDERS.get(rider.getUUID());
        if (state == null || state.physicalImpact || state.victims.contains(victim.getUUID())
                || state.level.getGameTime() - state.sampleTick > 1 || !facingImpact(state, victim)
                || !rider.hasLineOfSight(victim) || state.mount.position().distanceToSqr(state.position) > 6.25D
                || !state.mount.onGround() || state.momentum.quality() <= 0.0D) return;
        double quality = takeImpact(state, victim);
        event.setAmount(event.getAmount() + (float) (RTSServerConfig.CAVALRY_CHARGE_DAMAGE.get() * quality
                * TroopStatService.chargeMultiplier(rider)));
    }

    private static double takeImpact(RiderState state, AbstractRecruitEntity victim) {
        double quality = state.momentum.consumeImpact();
        state.victims.add(victim.getUUID());
        state.hitVictim = victim.getUUID();
        state.hitTick = state.level.getGameTime();
        state.hitQuality = quality;
        return quality;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDamage(LivingDamageEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level) || event.getAmount() <= 0.0F
                || !(event.getSource().getEntity() instanceof AbstractRecruitEntity rider)
                || event.getSource().getDirectEntity() != rider || !isMounted(rider)
                || RoutedGroupManager.isRouting(rider) || !(event.getEntity() instanceof AbstractRecruitEntity victim)
                || !validFootEnemy(rider, victim) || victim.getGroup() == null
                || rider.distanceToSqr(victim) > 36.0D) return;
        UUID group = victim.getGroup();
        long now = level.getGameTime();
        boolean ranged = victim instanceof IRangedRecruit;
        if (now - LAST_PRESSURE.getOrDefault(group, Long.MIN_VALUE / 4) >= 20) {
            LAST_PRESSURE.put(group, now);
            double pressure = ranged ? RTSServerConfig.CAVALRY_RANGED_PRESSURE.get() : RTSServerConfig.CAVALRY_MELEE_PRESSURE.get();
            BattleMechanicsManager.adjustGroupMorale(level, group, (float) -pressure);
        }
        RiderState state = RIDERS.get(rider.getUUID());
        if (state != null && victim.getUUID().equals(state.hitVictim) && now == state.hitTick && state.hitQuality > 0.0D) {
            double shock = ranged ? RTSServerConfig.CAVALRY_RANGED_SHOCK.get() : RTSServerConfig.CAVALRY_MELEE_SHOCK.get();
            ShockWindow window = LAST_SHOCK.get(group);
            if (window == null || now - window.started >= 40) {
                window = new ShockWindow(now);
                LAST_SHOCK.put(group, window);
            }
            // The strongest contact in this wave sets the total shock. A weak first
            // rider cannot block a proper charge, and 100 simultaneous horses do not
            // multiply group morale damage by 100.
            double desired = shock * state.hitQuality
                    * TroopStatService.chargeMultiplier(rider)
                    * (TacticalCombatManager.rearImpact(rider, victim) ? 2.0D : 1.0D);
            double extra = Math.max(0.0D, desired - window.applied);
            window.applied = Math.max(window.applied, desired);
            BattleMechanicsManager.adjustGroupMorale(level, group, (float) -extra);
        }
    }

    private static boolean validFootEnemy(AbstractRecruitEntity rider, AbstractRecruitEntity victim) {
        return rider != victim && victim.isAlive() && !victim.isRemoved() && victim.getVehicle() == null
                && (rider.getOwnerUUID() == null || !rider.getOwnerUUID().equals(victim.getOwnerUUID()))
                && !rider.isAlliedTo(victim) && rider.canAttack(victim);
    }

    private static boolean facingImpact(RiderState state, LivingEntity victim) {
        if (state.heading == null || state.heading.lengthSqr() < 0.001D) return false;
        Vec3 toward = horizontal(victim.position().subtract(state.mount.position()));
        return toward.lengthSqr() < 0.25D || state.heading.dot(toward.normalize()) > 0.10D;
    }

    private static Vec3 horizontal(Vec3 vector) { return new Vec3(vector.x, 0.0D, vector.z); }

    private static void setSpeed(Mob mount, double amount) {
        AttributeInstance attribute = mount.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attribute == null) return;
        AttributeModifier old = attribute.getModifier(SPEED_ID);
        if (old != null && Math.abs(old.getAmount() - amount) < 0.0001D) return;
        attribute.removeModifier(SPEED_ID);
        if (amount > 0.0001D) attribute.addTransientModifier(new AttributeModifier(SPEED_ID,
                "RTS cavalry combat pace", amount, AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    private static void applyMountedHealth(Mob mount) {
        AttributeInstance health = mount.getAttribute(Attributes.MAX_HEALTH);
        if (health == null || health.getModifier(MOUNT_HEALTH_ID) != null) return;
        double oldMaximum = health.getValue();
        health.addTransientModifier(new AttributeModifier(MOUNT_HEALTH_ID,
                "RTS recruited cavalry mount health", MOUNT_HEALTH_BONUS,
                AttributeModifier.Operation.MULTIPLY_TOTAL));
        mount.setHealth((float) Math.min(health.getValue(), mount.getHealth() + health.getValue() - oldMaximum));
    }

    private static void removeMountModifiers(Mob mount) {
        AttributeInstance attribute = mount.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attribute != null) attribute.removeModifier(SPEED_ID);
        AttributeInstance health = mount.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) {
            health.removeModifier(MOUNT_HEALTH_ID);
            if (mount.getHealth() > health.getValue()) mount.setHealth((float) health.getValue());
        }
    }

    @SubscribeEvent
    public static void onStop(ServerStoppedEvent event) {
        for (RiderState state : RIDERS.values()) removeMountModifiers(state.mount);
        RIDERS.clear();
        LAST_SHOCK.clear();
        LAST_PRESSURE.clear();
    }

    private static final class RiderState {
        final Mob mount;
        final ServerLevel level;
        final ChargeMomentum momentum = new ChargeMomentum();
        final Set<UUID> victims = new HashSet<>();
        Vec3 position;
        Vec3 heading;
        long sampleTick;
        boolean physicalImpact;
        UUID hitVictim;
        long hitTick;
        double hitQuality;

        RiderState(Mob mount, ServerLevel level) { this.mount = mount; this.level = level; }
    }

    private static final class ShockWindow {
        final long started;
        double applied;
        ShockWindow(long started) { this.started = started; }
    }
}
