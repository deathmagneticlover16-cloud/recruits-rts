package ee.lastpagans.recruitsrtscamera.runtime;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Event-maintained recruit index; callers never need to rediscover managed recruits globally. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RecruitRuntimeRegistry {
    private static final Map<UUID, RecruitRuntimeBrain> BRAINS = new HashMap<>();
    private static final Map<UUID, Set<UUID>> BY_GROUP = new HashMap<>();
    /** One immutable army snapshot per level/tick, shared by every runtime manager. */
    private static final Map<ServerLevel, LoadedSnapshot> LOADED_SNAPSHOTS = new HashMap<>();
    private static long rosterRevision;

    private RecruitRuntimeRegistry() {
    }

    public static RecruitRuntimeBrain brain(AbstractRecruitEntity recruit) {
        RecruitRuntimeBrain brain = BRAINS.get(recruit.getUUID());
        if (brain == null) {
            brain = new RecruitRuntimeBrain(recruit);
            BRAINS.put(recruit.getUUID(), brain);
            rosterChanged();
        }
        UUID oldGroup = brain.groupId();
        brain.attach(recruit);
        if (oldGroup != null && !oldGroup.equals(brain.groupId())) {
            removeFromGroup(oldGroup, recruit.getUUID());
            rosterChanged();
        }
        if (brain.groupId() != null
                && BY_GROUP.computeIfAbsent(brain.groupId(), ignored -> new HashSet<>()).add(recruit.getUUID())) {
            rosterChanged();
        }
        return brain;
    }

    public static List<RecruitRuntimeBrain> group(UUID groupId) {
        Set<UUID> ids = BY_GROUP.get(groupId);
        if (ids == null || ids.isEmpty()) return List.of();
        List<RecruitRuntimeBrain> result = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            RecruitRuntimeBrain brain = BRAINS.get(id);
            if (brain != null) result.add(brain);
        }
        return List.copyOf(result);
    }

    public static List<AbstractRecruitEntity> loaded(ServerLevel level) {
        if (level == null) return List.of();
        LoadedSnapshot cached = LOADED_SNAPSHOTS.get(level);
        long gameTime = level.getGameTime();
        if (cached != null && cached.gameTime == gameTime && cached.rosterRevision == rosterRevision) {
            return cached.recruits;
        }
        List<AbstractRecruitEntity> result = new ArrayList<>();
        for (RecruitRuntimeBrain brain : BRAINS.values()) {
            AbstractRecruitEntity recruit = brain.recruit();
            if (recruit != null && recruit.level() == level && recruit.isAlive() && !recruit.isRemoved()) {
                result.add(recruit);
            }
        }
        List<AbstractRecruitEntity> snapshot = List.copyOf(result);
        LOADED_SNAPSHOTS.put(level, new LoadedSnapshot(gameTime, rosterRevision, snapshot));
        return snapshot;
    }

    public static int brainCount() {
        return BRAINS.size();
    }

    public static long authorityCount(RecruitRuntimeBrain.MovementAuthority authority) {
        return BRAINS.values().stream().filter(brain -> brain.authority() == authority).count();
    }

    public static long simulationCount(RecruitRuntimeBrain.SimulationState simulation) {
        return BRAINS.values().stream().filter(brain -> brain.simulation() == simulation).count();
    }

    public static void claimFormation(AbstractRecruitEntity recruit, Vec3 slot, Vec3 velocity) {
        brain(recruit).claimFormation(slot, velocity, recruit.level().getGameTime());
    }

    public static void releaseFormation(AbstractRecruitEntity recruit) {
        brain(recruit).releaseFormation(recruit.level().getGameTime());
    }

    public static void wakeUnderFire(AbstractRecruitEntity recruit, Entity attacker) {
        brain(recruit).underFire(recruit.level().getGameTime(), attacker == null ? null : attacker.getUUID());
        UUID groupId = recruit.getGroup();
        if (groupId == null) return;
        for (RecruitRuntimeBrain member : group(groupId)) {
            if (!member.recruitId().equals(recruit.getUUID())) {
                member.underFire(recruit.level().getGameTime(), attacker == null ? null : attacker.getUUID());
            }
        }
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof AbstractRecruitEntity recruit) brain(recruit);
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof AbstractRecruitEntity recruit) remove(recruit);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!event.getEntity().level().isClientSide && event.getEntity() instanceof AbstractRecruitEntity recruit) remove(recruit);
    }

    private static void remove(AbstractRecruitEntity recruit) {
        RecruitRuntimeBrain brain = BRAINS.remove(recruit.getUUID());
        if (brain != null && brain.groupId() != null) removeFromGroup(brain.groupId(), recruit.getUUID());
        if (brain != null) rosterChanged();
    }

    private static void removeFromGroup(UUID groupId, UUID recruitId) {
        Set<UUID> members = BY_GROUP.get(groupId);
        if (members == null) return;
        members.remove(recruitId);
        if (members.isEmpty()) BY_GROUP.remove(groupId);
    }

    @SubscribeEvent
    public static void onStop(ServerStoppedEvent event) {
        BRAINS.clear();
        BY_GROUP.clear();
        LOADED_SNAPSHOTS.clear();
        rosterRevision = 0L;
    }

    private static void rosterChanged() {
        rosterRevision++;
        LOADED_SNAPSHOTS.clear();
    }

    private record LoadedSnapshot(long gameTime, long rosterRevision, List<AbstractRecruitEntity> recruits) { }
}
