package ee.lastpagans.recruitsrtscamera.battle;

import com.talhanation.recruits.FactionEvents;
import com.talhanation.recruits.RecruitEvents;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.IStrategicFire;
import com.talhanation.recruits.util.FormationUtils;
import com.talhanation.recruits.world.RecruitsGroup;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.animation.CommanderAnimationHints;
import ee.lastpagans.recruitsrtscamera.api.RoutedDesertersEvent;
import ee.lastpagans.recruitsrtscamera.combat.RTSGroupAttackManager;
import ee.lastpagans.recruitsrtscamera.combat.ThrowableWeaponManager;
import ee.lastpagans.recruitsrtscamera.formation.FormationRuntime;
import ee.lastpagans.recruitsrtscamera.formation.CustomFormationExecutor;
import ee.lastpagans.recruitsrtscamera.mixin.MobGoalAccess;
import ee.lastpagans.recruitsrtscamera.movement.RTSMovementBoostManager;
import ee.lastpagans.recruitsrtscamera.persistence.RTSRoutingSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** A routed group permanently loses command; its soldiers remain vulnerable while escaping. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RoutedGroupManager {
    private static final String KEY = "RecruitsRTSCameraRout";
    private static final double ESCAPE_SPEED = 1.0D;
    private static final int ESCAPE_REPATH_TICKS = 10;
    private static final Map<UUID, EscapeGoal> ACTIVE = new HashMap<>();

    private RoutedGroupManager() { }

    public static boolean isRouting(AbstractRecruitEntity recruit) {
        if (recruit == null || !(recruit.level() instanceof ServerLevel level)) return false;
        return recruit.getPersistentData().contains(KEY)
                || RTSRoutingSavedData.get(level).member(recruit.getUUID()) != null;
    }

    public static boolean isGroupRouting(UUID id) {
        var server = ServerLifecycleHooks.getCurrentServer();
        return id != null && server != null && RTSRoutingSavedData.get(server.overworld()).containsGroup(id);
    }

    public static void begin(ServerLevel level, UUID groupId, UUID ownerId,
                             List<AbstractRecruitEntity> loaded) {
        if (RTSRoutingSavedData.get(level).containsGroup(groupId)) return;
        Set<UUID> members = new LinkedHashSet<>();
        RecruitsGroup nativeGroup = RecruitEvents.recruitsGroupsManager == null ? null
                : RecruitEvents.recruitsGroupsManager.getGroup(groupId);
        if (nativeGroup != null && nativeGroup.members != null) members.addAll(nativeGroup.members);
        for (AbstractRecruitEntity recruit : loaded) members.add(recruit.getUUID());
        double angle = (groupId.getLeastSignificantBits() & 65535L) * (Math.PI * 2.0D / 65536.0D);
        Vec3 direction = new Vec3(Math.cos(angle), 0.0D, Math.sin(angle));
        if (!loaded.isEmpty()) {
            AbstractRecruitEntity sample = loaded.get(0);
            LivingEntity enemy = nearestThreat(sample, 48.0D);
            if (enemy != null) direction = horizontal(sample.position().subtract(enemy.position())).normalize();
        }
        RTSRoutingSavedData.get(level).begin(groupId, ownerId, members, direction);
        RTSMovementBoostManager.cancelForcedOrders(List.of(groupId));
        RTSGroupAttackManager.cancel(List.of(groupId));
        FormationRuntime.haltFormation(groupId);
        CustomFormationExecutor.releaseForAttack(groupId, loaded);
        for (AbstractRecruitEntity recruit : loaded) enforce(recruit);

        // Remove and SAVE native bookkeeping now. Its command bar uses a different packet
        // from Commander, and hiding a Commander card never changed this registry.
        if (RecruitEvents.recruitsGroupsManager != null) {
            RecruitEvents.recruitsGroupsManager.removeGroup(groupId);
            RecruitEvents.recruitsGroupsManager.save(level);
            RecruitEvents.recruitsGroupsManager.broadCastGroupsToPlayer(level, ownerId);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (event.getEntity() instanceof AbstractRecruitEntity recruit && isRouting(recruit)) {
            enforce(recruit);
            CompoundTag rout = recruit.getPersistentData().getCompound(KEY);
            rout.putInt("ticks", Math.min(1_000_000, rout.getInt("ticks") + 1));
        }
    }

    private static void enforce(AbstractRecruitEntity recruit) {
        if (!recruit.isAlive() || !(recruit.level() instanceof ServerLevel level)) return;
        var record = RTSRoutingSavedData.get(level).member(recruit.getUUID());
        if (!recruit.getPersistentData().contains(KEY)) {
            if (record == null) return;
            CompoundTag rout = new CompoundTag();
            rout.putUUID("group", record.groupId);
            rout.putUUID("owner", record.ownerId);
            Vec3 origin = controlled(recruit).position();
            rout.putDouble("x", origin.x);
            rout.putDouble("z", origin.z);
            rout.putDouble("dx", record.direction.x);
            rout.putDouble("dz", record.direction.z);
            recruit.getPersistentData().put(KEY, rout);
        }
        if (!ACTIVE.containsKey(recruit.getUUID())) {
            MobGoalAccess goals = (MobGoalAccess) recruit;
            clearGoals(goals.rts$getGoals());
            clearGoals(goals.rts$getTargets());
            recruit.getNavigation().stop();
            EscapeGoal escape = new EscapeGoal(recruit);
            goals.rts$getGoals().addGoal(-100, escape);
            ACTIVE.put(recruit.getUUID(), escape);
            ThrowableWeaponManager.cleanup(recruit.getUUID());
        }
        recruit.setGroupUUID(null);
        recruit.needsGroupUpdate = false;
        recruit.setListen(false);
        recruit.setAggroState(3); // Native passive state, reinforced by the target/command gates.
        recruit.setFollowState(0);
        recruit.setShouldFollow(false);
        recruit.setShouldProtect(false);
        recruit.setIsFollowing(false);
        recruit.setShouldRest(false);
        recruit.setShouldRanged(false);
        recruit.setShouldBlock(false);
        recruit.setShouldMovePos(false);
        recruit.setShouldHoldPos(false);
        recruit.clearMovePos();
        recruit.clearHoldPos();
        recruit.holdFormation = false;
        recruit.isInFormation = false;
        recruit.setFleeing(true);
        recruit.setTarget(null);
        recruit.setLastHurtByMob(null);
        recruit.stopUsingItem();
        if (recruit instanceof IStrategicFire strategic) strategic.setShouldStrategicFire(false);
        CommanderAnimationHints.offer(recruit, CommanderAnimationHints.Hint.ROUTING, 12);
    }

    private static void clearGoals(GoalSelector selector) {
        List<Goal> goals = selector.getAvailableGoals().stream().map(WrappedGoal::getGoal).toList();
        for (Goal goal : goals) selector.removeGoal(goal);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTarget(LivingChangeTargetEvent event) {
        AbstractRecruitEntity routed = routedController(event.getEntity());
        if (routed != null) event.setNewTarget(null);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttack(LivingAttackEvent event) {
        // This also catches a previously launched projectile or an integration trying to
        // attack directly without a target. Victims can still damage routed soldiers.
        if (!event.getEntity().level().isClientSide
                && routedController(event.getSource().getEntity()) != null) event.setCanceled(true);
    }

    private static AbstractRecruitEntity routedController(Entity entity) {
        if (entity instanceof AbstractRecruitEntity recruit && isRouting(recruit)) return recruit;
        if (entity != null) for (Entity passenger : entity.getPassengers()) {
            if (passenger instanceof AbstractRecruitEntity recruit && isRouting(recruit)) return recruit;
        }
        return null;
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)
                || level.getGameTime() % 20 != 0) return;
        Map<UUID, List<AbstractRecruitEntity>> escaped = new HashMap<>();
        for (EscapeGoal goal : List.copyOf(ACTIVE.values())) {
            AbstractRecruitEntity recruit = goal.recruit;
            if (recruit.level() != level || !recruit.isAlive() || recruit.isRemoved()) continue;
            CompoundTag rout = recruit.getPersistentData().getCompound(KEY);
            if (!rout.hasUUID("group") || rout.getInt("ticks") < 80) continue;
            Entity mover = controlled(recruit);
            if (Math.hypot(mover.getX() - rout.getDouble("x"), mover.getZ() - rout.getDouble("z")) < 30.0D) continue;
            boolean safe = true;
            for (LivingEntity threat : level.getEntitiesOfClass(LivingEntity.class,
                    mover.getBoundingBox().inflate(56.0D, 24.0D, 56.0D))) {
                if (isThreat(recruit, threat)
                        && (threat.distanceToSqr(mover) < 900.0D || threat.hasLineOfSight(recruit))) {
                    safe = false;
                    break;
                }
            }
            if (safe) escaped.computeIfAbsent(rout.getUUID("group"), ignored -> new ArrayList<>()).add(recruit);
        }
        for (var entry : escaped.entrySet()) removeEscaped(level, entry.getKey(), entry.getValue());
    }

    private static void removeEscaped(ServerLevel level, UUID groupId, List<AbstractRecruitEntity> survivors) {
        UUID owner = survivors.get(0).getPersistentData().getCompound(KEY).getUUID("owner");
        Vec3 center = Vec3.ZERO;
        for (AbstractRecruitEntity recruit : survivors) center = center.add(controlled(recruit).position());
        MinecraftForge.EVENT_BUS.post(new RoutedDesertersEvent(level, groupId, owner,
                survivors.stream().map(Entity::getUUID).toList(), center.scale(1.0D / survivors.size())));
        for (AbstractRecruitEntity recruit : survivors) {
            // Native death events handle actual casualties. Only living escaped survivors
            // receive this accounting path, once, after the campaign hook has inspected them.
            RTSRoutingSavedData.get(level).removeMember(recruit.getUUID());
            if (recruit.isRemoved() || !recruit.isAlive()) continue;
            if (RecruitEvents.recruitsPlayerUnitManager != null && recruit.getOwnerUUID() != null) {
                RecruitEvents.recruitsPlayerUnitManager.removeRecruits(recruit.getOwnerUUID(), 1);
            }
            if (recruit.getTeam() != null) {
                FactionEvents.addNPCToData(level, recruit.getTeam().getName(), -1);
                FactionEvents.removeRecruitFromTeam(recruit, recruit.getTeam(), level);
            }
            recruit.setOwnerUUID(Optional.empty());
            recruit.setIsOwned(false);
            recruit.stopRiding();
            recruit.discard();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof AbstractRecruitEntity recruit
                && recruit.level() instanceof ServerLevel level && isRouting(recruit)) {
            RTSRoutingSavedData.get(level).removeMember(recruit.getUUID());
        }
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide) ACTIVE.remove(event.getEntity().getUUID());
        // Do not remove persisted membership here: unloading is not a death or an escape.
    }

    @SubscribeEvent
    public static void onStop(ServerStoppedEvent event) { ACTIVE.clear(); }

    private static Entity controlled(AbstractRecruitEntity recruit) {
        return recruit.getVehicle() instanceof Mob mob ? mob : recruit;
    }

    private static boolean isThreat(AbstractRecruitEntity recruit, LivingEntity other) {
        if (other == recruit || other == recruit.getVehicle() || !other.isAlive() || other.isRemoved()) return false;
        if (other instanceof AbstractRecruitEntity ally && recruit.getOwnerUUID() != null
                && recruit.getOwnerUUID().equals(ally.getOwnerUUID())) return false;
        return !recruit.isAlliedTo(other) && (RecruitEvents.canAttack(recruit, other)
                || (other instanceof Mob mob && mob.getTarget() == recruit));
    }

    private static LivingEntity nearestThreat(AbstractRecruitEntity recruit, double radius) {
        LivingEntity closest = null;
        double distance = radius * radius;
        for (LivingEntity other : recruit.level().getEntitiesOfClass(LivingEntity.class,
                controlled(recruit).getBoundingBox().inflate(radius, 16.0D, radius))) {
            if (!isThreat(recruit, other)) continue;
            double current = recruit.distanceToSqr(other);
            if (current < distance) { distance = current; closest = other; }
        }
        return closest;
    }

    private static Vec3 horizontal(Vec3 v) { return new Vec3(v.x, 0.0D, v.z); }

    private static final class EscapeGoal extends Goal {
        final AbstractRecruitEntity recruit;
        int repath;

        EscapeGoal(AbstractRecruitEntity recruit) {
            this.recruit = recruit;
            setFlags(EnumSet.allOf(Flag.class));
        }

        @Override public boolean canUse() { return recruit.isAlive() && isRouting(recruit); }
        @Override public boolean canContinueToUse() { return canUse(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }

        @Override public void tick() {
            Entity mover = controlled(recruit);
            // Routing is a retreat, not a forced march. The old 1.6 value made foot
            // soldiers sprint faster than ordinary recruits, and also leaked into mounted
            // navigation. Keep the native/default pace for both cases.
            recruit.setSprinting(false);
            recruit.moveSpeed = (float) ESCAPE_SPEED;
            if (++repath % ESCAPE_REPATH_TICKS != 1) return;
            CompoundTag rout = recruit.getPersistentData().getCompound(KEY);
            Vec3 direction = new Vec3(rout.getDouble("dx"), 0.0D, rout.getDouble("dz"));
            if (direction.lengthSqr() < 0.001D) direction = new Vec3(1.0D, 0.0D, 0.0D);
            direction = direction.normalize();
            // Keep the original escape heading. Replacing it with the current nearest
            // threat made separate enemies pull the route back and forth; alternating
            // +/-60-degree stall corrections made the classic 10-block shuttle even worse.
            // PathNavigation is allowed to route around terrain while the strategic heading
            // remains outward from the battle.
            rout.putDouble("dx", direction.x);
            rout.putDouble("dz", direction.z);
            Vec3 desired = mover.position().add(direction.scale(16.0D));
            BlockPos surface = FormationUtils.getPositionOrSurface((ServerLevel) recruit.level(), BlockPos.containing(desired));
            recruit.getNavigation().moveTo(desired.x, surface.getY(), desired.z, ESCAPE_SPEED);
            if (mover instanceof Mob mount && mover != recruit) {
                mount.setTarget(null);
                mount.setSprinting(false);
                mount.getNavigation().moveTo(desired.x, surface.getY(), desired.z, ESCAPE_SPEED);
            }
        }
    }
}
