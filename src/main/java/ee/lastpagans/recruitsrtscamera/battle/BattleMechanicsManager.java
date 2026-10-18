package ee.lastpagans.recruitsrtscamera.battle;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.HorsemanEntity;
import com.talhanation.recruits.entities.SiegeEngineerEntity;
import com.talhanation.recruits.world.RecruitsGroup;
import com.talhanation.recruits.world.RecruitsGroupsSaveData;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.advisor.AdvisorEvent;
import ee.lastpagans.recruitsrtscamera.animation.CommanderAnimationHints;
import ee.lastpagans.recruitsrtscamera.formation.FormationRuntime;
import ee.lastpagans.recruitsrtscamera.combat.RTSGroupAttackManager;
import ee.lastpagans.recruitsrtscamera.formation.FormationService;
import ee.lastpagans.recruitsrtscamera.movement.RTSMovementBoostManager;
import ee.lastpagans.recruitsrtscamera.network.AdvisorEventPacket;
import ee.lastpagans.recruitsrtscamera.network.BattleStateSyncPacket;
import ee.lastpagans.recruitsrtscamera.network.RTSCameraNetwork;
import ee.lastpagans.recruitsrtscamera.registry.RTSTags;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitRuntimeRegistry;
import ee.lastpagans.recruitsrtscamera.server.BodyguardManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * First real Commander battlefield layer: persistent fatigue and situational morale.
 * Recruits' own 0..100 morale value remains the source of truth; this manager only adds
 * battlefield causes (losses, rear pressure, commander death, exhaustion) and lets the
 * base mod's moral effects react to the resulting value.
 */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BattleMechanicsManager {
    private static final String FATIGUE_KEY = "RecruitsRTSCameraFatigue";
    private static final String FATIGUE_STAGE_KEY = "RecruitsRTSCameraFatigueStage";
    private static final UUID FATIGUE_SPEED_ID = UUID.fromString("fe4207b1-b731-4e95-a9f2-1ebd58d6c201");
    private static final UUID FATIGUE_DAMAGE_ID = UUID.fromString("2a6ad751-fbaa-43af-8c4f-a79bea8c20e4");

    private static final int SAMPLE_INTERVAL = 5;
    private static final int COMBAT_SESSION_TIMEOUT = 20 * 25;
    private static final int REAR_SHOCK_COOLDOWN = 30;
    private static final double STANDARD_GUARD_RADIUS = 10.0D;
    private static final int STANDARD_GUARD_COUNT = 4;
    private static final double BODYGUARD_GUARD_RADIUS = 12.0D;
    private static final int BODYGUARD_GUARD_COUNT = 6;
    // 0.10.7 deliberately makes morale much less brittle than the base Recruit scale.
    private static final float STANDARD_FALLEN_SHOCK = -10.0F;
    private static final float ROUTING_MORALE = 8.0F;
    private static final float WAVERING_MORALE = 24.0F;
    private static final double COMMANDER_AURA_RADIUS = 28.0D;
    private static final float COMMANDER_AURA_RECOVERY_PER_SAMPLE = 0.10F;

    private static final Map<UUID, Vec3> LAST_POSITIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, CombatState> COMBAT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> REAR_SHOCK = new ConcurrentHashMap<>();
    private static final Map<String, Long> ADVISOR_COOLDOWNS = new ConcurrentHashMap<>();
    private static int tickCounter;

    private BattleMechanicsManager() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            BodyguardManager.tick(player);
        }

        if (++tickCounter % SAMPLE_INTERVAL != 0) return;
        long now = server.overworld().getGameTime();
        Map<UUID, GroupRuntime> runtime = collectAndUpdateFatigue(server);
        updateCombatStates(server, runtime, now);
        if (tickCounter % 20 == 0) {
            syncPlayers(server, runtime);
        }
    }

    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        if (event.getEntity().level().isClientSide) return;
        LivingEntity victim = event.getEntity();
        Entity rawAttacker = event.getSource().getEntity();
        LivingEntity attacker = rawAttacker instanceof LivingEntity living ? living : null;
        long now = victim.level().getGameTime();

        if (victim instanceof ServerPlayer player) {
            if ((BodyguardManager.isRtsActive(player) || BodyguardManager.getBodyguard(player) != null)
                    && event.getAmount() > 0.0F) {
                notifyAdvisor(player, AdvisorEvent.GENERAL_ATTACKED, 20 * 9, now);
                if (attacker != null && !player.isAlliedTo(attacker)) {
                    rallyBodyguardGuard(player, attacker);
                }
            }
            return;
        }

        if (!(victim instanceof AbstractRecruitEntity recruit) || recruit.getOwnerUUID() == null) return;
        UUID groupId = recruit.getGroup();
        if (groupId == null) return;
        touchCombat((ServerLevel) recruit.level(), recruit, now);

        ServerPlayer owner = recruit.level().getServer().getPlayerList().getPlayer(recruit.getOwnerUUID());
        if (recruit instanceof SiegeEngineerEntity && owner != null) {
            notifyAdvisor(owner, AdvisorEvent.ARTILLERY_ATTACKED, 20 * 10, now);
        }

        if (attacker != null && !recruit.isAlliedTo(attacker)) {
            if (isMoraleBearer(recruit)) {
                rallyStandardGuard((ServerLevel) recruit.level(), recruit, attacker);
            }
            if (isRearAttack(recruit, attacker)) {
                long last = REAR_SHOCK.getOrDefault(groupId, Long.MIN_VALUE / 4);
                if (now - last >= REAR_SHOCK_COOLDOWN) {
                    REAR_SHOCK.put(groupId, now);
                    adjustGroupMorale((ServerLevel) recruit.level(), groupId, -2.0F);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide) return;

        if (event.getEntity() instanceof AbstractRecruitEntity recruit) {
            if (recruit.getGroup() != null && carriesMoraleItem(recruit)
                    && recruit.level() instanceof ServerLevel level) {
                // Losing the sacred/important standard is intentionally a one-time shock, not
                // a permanent aura penalty. The ordinary 20% resistance disappears naturally
                // because the dead bearer no longer counts as a living morale-item carrier.
                adjustGroupMorale(level, recruit.getGroup(), STANDARD_FALLEN_SHOCK);
            }
            return;
        }

        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!BodyguardManager.isRtsActive(player) && BodyguardManager.getBodyguard(player) == null) return;
        ServerLevel level = player.serverLevel();
        Set<UUID> shockedGroups = new HashSet<>();
        for (AbstractRecruitEntity recruit : RecruitRuntimeRegistry.loaded(level)) {
            if (recruit.isAlive()
                    && player.getUUID().equals(recruit.getOwnerUUID()) && recruit.getGroup() != null) {
                shockedGroups.add(recruit.getGroup());
            }
        }
        for (UUID groupId : shockedGroups) {
            adjustGroupMorale(level, groupId, -24.0F);
        }
        BodyguardManager.setRtsActive(player, false);
    }

    private static Map<UUID, GroupRuntime> collectAndUpdateFatigue(MinecraftServer server) {
        Map<UUID, GroupRuntime> groups = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (AbstractRecruitEntity recruit : RecruitRuntimeRegistry.loaded(level)) {
                if (!recruit.isAlive()
                        || recruit.getGroup() == null || recruit.getOwnerUUID() == null || RoutedGroupManager.isRouting(recruit)) {
                    continue;
                }
                updateFatigue(recruit);
                GroupRuntime group = groups.computeIfAbsent(recruit.getGroup(), id -> new GroupRuntime(
                        id, recruit.getOwnerUUID(), level
                ));
                group.add(recruit);
            }
        }
        for (GroupRuntime group : groups.values()) {
            FormationRuntime.repairGroundSlots(group.level, group.groupId, group.members);
        }
        return groups;
    }

    private static void updateFatigue(AbstractRecruitEntity recruit) {
        Vec3 current = recruit.position();
        Vec3 previous = LAST_POSITIONS.put(recruit.getUUID(), current);
        double moved = previous == null ? 0.0D : Math.hypot(current.x - previous.x, current.z - previous.z);
        UUID groupId = recruit.getGroup();
        boolean hasty = groupId != null && RTSMovementBoostManager.isHastyActive(groupId);
        boolean slow = groupId != null && RTSMovementBoostManager.isSlowMarchActive(groupId);
        boolean inMelee = recruit.getTarget() != null && recruit.distanceTo(recruit.getTarget()) < 4.5F;

        double fatigue = recruit.getPersistentData().getDouble(FATIGUE_KEY);
        if (hasty && moved > 0.035D) {
            fatigue += 0.12D * SAMPLE_INTERVAL;
        } else if (inMelee) {
            fatigue += 0.07D * SAMPLE_INTERVAL;
        } else if (moved > 0.075D && !slow) {
            fatigue += 0.020D * SAMPLE_INTERVAL;
        } else if (moved > 0.035D) {
            fatigue += 0.004D * SAMPLE_INTERVAL;
        } else {
            fatigue -= 0.100D * SAMPLE_INTERVAL;
        }
        if (recruit instanceof HorsemanEntity) {
            fatigue = recruit.getPersistentData().getDouble(FATIGUE_KEY)
                    + (fatigue - recruit.getPersistentData().getDouble(FATIGUE_KEY)) * 0.70D;
        }
        fatigue = Math.max(0.0D, Math.min(100.0D, fatigue));
        recruit.getPersistentData().putDouble(FATIGUE_KEY, fatigue);
        int stage = fatigueStage(fatigue);
        int oldStage = recruit.getPersistentData().getInt(FATIGUE_STAGE_KEY);
        if (stage != oldStage) recruit.getPersistentData().putInt(FATIGUE_STAGE_KEY, stage);
        applyFatigueModifiers(recruit, stage);
        if (isMoraleBearer(recruit)) {
            CommanderAnimationHints.offer(recruit, CommanderAnimationHints.Hint.STANDARD_BEARER, SAMPLE_INTERVAL + 4);
        }
        if (stage >= 3) {
            CommanderAnimationHints.offer(recruit, CommanderAnimationHints.Hint.EXHAUSTED, SAMPLE_INTERVAL + 4);
        } else if (stage == 2) {
            CommanderAnimationHints.offer(recruit, CommanderAnimationHints.Hint.TIRED, SAMPLE_INTERVAL + 4);
        } else if (stage == 1) {
            CommanderAnimationHints.offer(recruit, CommanderAnimationHints.Hint.WINDED, SAMPLE_INTERVAL + 4);
        }

        // Exhaustion makes an already-engaged soldier more vulnerable psychologically,
        // but does not drain morale while resting or simply travelling.
        if (stage >= 3 && inMelee) {
            recruit.setMoral(Math.max(0.0F, recruit.getMorale() - 0.05F * SAMPLE_INTERVAL));
        }
    }

    private static void applyFatigueModifiers(AbstractRecruitEntity recruit, int stage) {
        double speedPenalty = switch (stage) {
            case 1 -> -0.04D;
            case 2 -> -0.09D;
            case 3 -> -0.16D;
            default -> 0.0D;
        };
        double damagePenalty = switch (stage) {
            case 1 -> -0.07D;
            case 2 -> -0.14D;
            case 3 -> -0.24D;
            default -> 0.0D;
        };
        applyModifier(recruit.getAttribute(Attributes.MOVEMENT_SPEED), FATIGUE_SPEED_ID,
                "RTS fatigue movement", speedPenalty);
        applyModifier(recruit.getAttribute(Attributes.ATTACK_DAMAGE), FATIGUE_DAMAGE_ID,
                "RTS fatigue damage", damagePenalty);
    }

    private static void applyModifier(AttributeInstance attribute, UUID id, String name, double amount) {
        if (attribute == null) return;
        attribute.removeModifier(id);
        if (Math.abs(amount) > 1.0E-6D) {
            attribute.addTransientModifier(new AttributeModifier(
                    id, name, amount, AttributeModifier.Operation.MULTIPLY_TOTAL
            ));
        }
    }

    private static int fatigueStage(double fatigue) {
        if (fatigue >= 82.0D) return 3; // exhausted
        if (fatigue >= 58.0D) return 2; // tired
        if (fatigue >= 32.0D) return 1; // winded
        return 0;
    }

    private static void updateCombatStates(MinecraftServer server, Map<UUID, GroupRuntime> runtime, long now) {
        Set<UUID> seen = new HashSet<>();
        for (GroupRuntime group : runtime.values()) {
            seen.add(group.groupId);
            int persistentCount = persistentGroupSize(group.level, group.groupId, group.members.size());
            boolean fighting = group.members.stream().anyMatch(r -> r.getTarget() != null);
            CombatState state = COMBAT.get(group.groupId);
            if (fighting) {
                if (state == null || now - state.lastCombatTick > COMBAT_SESSION_TIMEOUT) {
                    state = new CombatState(group.ownerId, Math.max(1, persistentCount), persistentCount, now);
                    COMBAT.put(group.groupId, state);
                }
                state.lastCombatTick = now;
            }
            if (state == null) {
                applyMoraleRouting(group, averageMorale(group.members), null, now);
                continue;
            }

            if (persistentCount < state.lastCount) {
                int losses = state.lastCount - persistentCount;
                float proportionalShock = (float) losses / Math.max(1, state.baseline) * 28.0F;
                if (losses >= Math.max(4, (int) Math.ceil(state.baseline * 0.15D))) {
                    proportionalShock += 2.5F;
                }
                adjustGroupMorale(group.level, group.groupId, -proportionalShock);
                state.lastCount = persistentCount;
                state.lastCombatTick = now;
            } else if (persistentCount > state.lastCount) {
                // Reinforcements may join the group, but never rewrite the combat baseline.
                state.lastCount = persistentCount;
            }

            applyCommanderAura(server, group);
            float morale = averageMorale(group.members);
            int moraleState = applyMoraleRouting(group, morale, state, now);
            state.moraleState = moraleState;
            // Routing remains part of the same battle session even after targets are cleared.
            // Without this latch the 25-second generic combat timeout could create a fresh
            // CombatState while the same unit was still fleeing, allowing the same group UUID
            // to fire the routing advisor warning a second time.
            if (moraleState == 2) {
                state.lastCombatTick = now;
            }

            float fatigue = averageFatigue(group.members);
            if (fatigue >= 82.0F && !state.exhaustedWarned) {
                state.exhaustedWarned = true;
                ServerPlayer owner = server.getPlayerList().getPlayer(group.ownerId);
                notifyAdvisor(owner, AdvisorEvent.UNITS_EXHAUSTED, 20 * 14, now);
            } else if (fatigue >= 58.0F && !state.tiredWarned) {
                state.tiredWarned = true;
                ServerPlayer owner = server.getPlayerList().getPlayer(group.ownerId);
                notifyAdvisor(owner, AdvisorEvent.UNITS_TIRED, 20 * 12, now);
            }

            if (!fighting && state.moraleState != 2 && now - state.lastCombatTick > COMBAT_SESSION_TIMEOUT) {
                COMBAT.remove(group.groupId, state);
            }
        }

        // If an active combat group vanished entirely, treat it as destroyed rather than
        // waiting for another loaded member to report a zero count.
        for (Map.Entry<UUID, CombatState> entry : List.copyOf(COMBAT.entrySet())) {
            if (seen.contains(entry.getKey())) continue;
            CombatState state = entry.getValue();
            if (!state.routedThisBattle && state.lastCount > 0 && now - state.lastCombatTick <= COMBAT_SESSION_TIMEOUT) {
                ServerPlayer owner = server.getPlayerList().getPlayer(state.ownerId);
                notifyAdvisor(owner, AdvisorEvent.UNIT_FALLEN, 20 * 8, now);
            }
            COMBAT.remove(entry.getKey(), state);
        }
    }

    private static int applyMoraleRouting(GroupRuntime group, float morale, CombatState combat, long now) {
        if (morale < ROUTING_MORALE || RoutedGroupManager.isGroupRouting(group.groupId)
                || (combat != null && combat.routedThisBattle)) {
            if (combat == null) {
                combat = new CombatState(group.ownerId, group.members.size(), group.members.size(), now);
                COMBAT.put(group.groupId, combat);
            }
            combat.moraleState = 2;
            combat.routedThisBattle = true;
            combat.lastCombatTick = now;
            if (!combat.routAdvisorTriggered) {
                combat.routAdvisorTriggered = true;
                notifyAdvisor(group.level.getServer().getPlayerList().getPlayer(group.ownerId),
                        AdvisorEvent.UNIT_ROUT, 20 * 7, now);
            }
            RoutedGroupManager.begin(group.level, group.groupId, group.ownerId, group.members);
            return 2;
        }
        if (morale < WAVERING_MORALE) {
            for (AbstractRecruitEntity recruit : group.members) {
                CommanderAnimationHints.offer(recruit, CommanderAnimationHints.Hint.WAVERING, SAMPLE_INTERVAL + 8);
            }
            return 1;
        }
        return 0;
    }

    private static void applyCommanderAura(MinecraftServer server, GroupRuntime group) {
        if (server == null || group == null || group.members.isEmpty()) return;
        ServerPlayer owner = server.getPlayerList().getPlayer(group.ownerId);
        if (owner == null || !owner.isAlive() || owner.level() != group.level) return;
        double radiusSq = COMMANDER_AURA_RADIUS * COMMANDER_AURA_RADIUS;
        for (AbstractRecruitEntity recruit : group.members) {
            if (recruit == null || !recruit.isAlive() || recruit.distanceToSqr(owner) > radiusSq) continue;
            if (recruit.getMorale() < 100.0F) {
                recruit.setMoral(Math.min(100.0F, recruit.getMorale() + COMMANDER_AURA_RECOVERY_PER_SAMPLE));
            }
        }
    }

    /** Current routing state used by the server command gate. */
    public static boolean isGroupRouting(UUID groupId) {
        if (groupId == null) return false;
        CombatState state = COMBAT.get(groupId);
        return RoutedGroupManager.isGroupRouting(groupId) || (state != null && state.routedThisBattle);
    }

    /**
     * Battle-session memory: true once this exact persistent group UUID has routed at least
     * once in its current combat session. It resets only when that session expires.
     */
    public static boolean hasRoutedThisBattle(UUID groupId) {
        if (groupId == null) return false;
        CombatState state = COMBAT.get(groupId);
        return RoutedGroupManager.isGroupRouting(groupId) || (state != null && state.routedThisBattle);
    }

    private static void syncPlayers(MinecraftServer server, Map<UUID, GroupRuntime> runtime) {
        Map<UUID, Map<UUID, GroupBattleSnapshot>> byOwner = new HashMap<>();
        for (GroupRuntime group : runtime.values()) {
            CombatState combat = COMBAT.get(group.groupId);
            int persistentCount = persistentGroupSize(group.level, group.groupId, group.members.size());
            float morale = averageMorale(group.members);
            float fatigue = averageFatigue(group.members);
            int moraleState = combat != null ? combat.moraleState
                    : (morale < ROUTING_MORALE ? 2 : morale < WAVERING_MORALE ? 1 : 0);
            byOwner.computeIfAbsent(group.ownerId, ignored -> new HashMap<>()).put(
                    group.groupId,
                    new GroupBattleSnapshot(fatigue, morale, moraleState,
                            combat == null ? 0 : combat.baseline, persistentCount,
                            combat != null && combat.routedThisBattle)
            );
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            RTSCameraNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new BattleStateSyncPacket(byOwner.getOrDefault(player.getUUID(), Map.of()))
            );
        }
    }

    private static void touchCombat(ServerLevel level, AbstractRecruitEntity recruit, long now) {
        UUID groupId = recruit.getGroup();
        if (groupId == null) return;
        int count = persistentGroupSize(level, groupId, 1);
        CombatState state = COMBAT.get(groupId);
        if (state == null || now - state.lastCombatTick > COMBAT_SESSION_TIMEOUT) {
            COMBAT.put(groupId, new CombatState(recruit.getOwnerUUID(), Math.max(1, count), count, now));
        } else {
            state.lastCombatTick = now;
        }
    }

    private static boolean carriesMoraleItem(AbstractRecruitEntity recruit) {
        return recruit != null
                && (recruit.getMainHandItem().is(RTSTags.MORALE_ITEMS)
                || recruit.getOffhandItem().is(RTSTags.MORALE_ITEMS));
    }

    private static boolean isMoraleBearer(AbstractRecruitEntity recruit) {
        return recruit != null && recruit.isAlive() && carriesMoraleItem(recruit);
    }

    /** Protect the real commander without making the entire Bodyguard abandon formation. */
    private static void rallyBodyguardGuard(ServerPlayer player, LivingEntity attacker) {
        if (player == null || attacker == null || !attacker.isAlive()) return;
        UUID groupId = BodyguardManager.getBodyguard(player);
        if (groupId == null) return;
        ServerLevel level = player.serverLevel();
        double radiusSq = BODYGUARD_GUARD_RADIUS * BODYGUARD_GUARD_RADIUS;
        List<AbstractRecruitEntity> defenders = new ArrayList<>();
        for (AbstractRecruitEntity recruit : RecruitRuntimeRegistry.loaded(level)) {
            if (!recruit.isAlive()
                    || !player.getUUID().equals(recruit.getOwnerUUID())
                    || !groupId.equals(recruit.getGroup()) || recruit.isAlliedTo(attacker)
                    || recruit.distanceToSqr(player) > radiusSq) {
                continue;
            }
            defenders.add(recruit);
        }
        defenders.sort(Comparator.comparingDouble(recruit -> recruit.distanceToSqr(attacker)));
        int assigned = 0;
        for (AbstractRecruitEntity defender : defenders) {
            if (assigned >= BODYGUARD_GUARD_COUNT) break;
            LivingEntity current = defender.getTarget();
            if (current != null && current.isAlive() && current != attacker
                    && defender.distanceToSqr(current) <= defender.distanceToSqr(attacker) + 3.0D) {
                continue;
            }
            defender.setTarget(attacker);
            assigned++;
        }
    }

    /**
     * A standard bearer being struck is treated as an emergency local protection event.
     * Only the nearest few groupmates are redirected, and we deliberately leave their formation
     * movement/hold flags intact so the whole line does not collapse into a dog-pile.
     */
    private static void rallyStandardGuard(ServerLevel level, AbstractRecruitEntity bearer, LivingEntity attacker) {
        if (level == null || bearer == null || attacker == null || bearer.getGroup() == null) return;
        double radiusSq = STANDARD_GUARD_RADIUS * STANDARD_GUARD_RADIUS;
        List<AbstractRecruitEntity> defenders = new ArrayList<>();
        for (AbstractRecruitEntity recruit : RecruitRuntimeRegistry.loaded(level)) {
            if (recruit == bearer || !recruit.isAlive()
                    || !bearer.getGroup().equals(recruit.getGroup()) || recruit.isAlliedTo(attacker)
                    || recruit.distanceToSqr(bearer) > radiusSq) {
                continue;
            }
            defenders.add(recruit);
        }
        defenders.sort(Comparator.comparingDouble(recruit -> recruit.distanceToSqr(attacker)));
        int assigned = 0;
        for (AbstractRecruitEntity defender : defenders) {
            if (assigned >= STANDARD_GUARD_COUNT) break;
            LivingEntity current = defender.getTarget();
            // Do not pull a soldier off an equally immediate life-or-death melee unless the
            // standard's attacker is clearly the more urgent threat.
            if (current != null && current.isAlive() && current != attacker
                    && defender.distanceToSqr(current) <= defender.distanceToSqr(attacker) + 4.0D) {
                continue;
            }
            defender.setTarget(attacker);
            assigned++;
        }
    }

    private static boolean isRearAttack(AbstractRecruitEntity recruit, LivingEntity attacker) {
        float facing = recruit.getYRot();
        if (recruit.getGroup() != null && recruit.level() instanceof ServerLevel level) {
            var state = FormationService.state(level, recruit.getGroup());
            if (state != null && Float.isFinite(state.facingYaw())) facing = state.facingYaw();
        }
        double rad = Math.toRadians(facing);
        Vec3 forward = new Vec3(-Math.sin(rad), 0.0D, Math.cos(rad));
        Vec3 toAttacker = attacker.position().subtract(recruit.position());
        toAttacker = new Vec3(toAttacker.x, 0.0D, toAttacker.z);
        if (toAttacker.lengthSqr() < 1.0E-5D) return false;
        return forward.dot(toAttacker.normalize()) < -0.50D;
    }

    public static void adjustGroupMorale(ServerLevel level, UUID groupId, float delta) {
        if (level == null || groupId == null || Math.abs(delta) < 1.0E-5F) return;
        // A carried standard/eagle does not regenerate morale for free; it reduces incoming
        // group-wide morale shocks. The tag is intentionally external (#rts:morale_items)
        // so the server can nominate any banner/standard item through a datapack.
        float effectiveDelta = delta;
        if (delta < 0.0F && groupHasMoraleItem(level, groupId)) {
            effectiveDelta *= 0.80F;
        }
        for (AbstractRecruitEntity recruit : RecruitRuntimeRegistry.loaded(level)) {
            if (recruit.isAlive()
                    && groupId.equals(recruit.getGroup())) {
                recruit.setMoral(Math.max(0.0F, Math.min(100.0F, recruit.getMorale() + effectiveDelta)));
            }
        }
    }

    public static void recoverFatigue(AbstractRecruitEntity recruit, double amount) {
        if (recruit == null || RoutedGroupManager.isRouting(recruit)) return;
        double value = Math.max(0, recruit.getPersistentData().getDouble(FATIGUE_KEY) - Math.max(0, amount));
        recruit.getPersistentData().putDouble(FATIGUE_KEY, value);
        int stage = fatigueStage(value);
        recruit.getPersistentData().putInt(FATIGUE_STAGE_KEY, stage);
        applyFatigueModifiers(recruit, stage);
    }

    private static boolean groupHasMoraleItem(ServerLevel level, UUID groupId) {
        for (AbstractRecruitEntity recruit : RecruitRuntimeRegistry.loaded(level)) {
            if (recruit.isAlive()
                    && groupId.equals(recruit.getGroup())
                    && (recruit.getMainHandItem().is(RTSTags.MORALE_ITEMS)
                    || recruit.getOffhandItem().is(RTSTags.MORALE_ITEMS))) {
                return true;
            }
        }
        return false;
    }

    private static int persistentGroupSize(ServerLevel level, UUID groupId, int fallback) {
        if (level == null || groupId == null) return Math.max(0, fallback);
        for (RecruitsGroup group : RecruitsGroupsSaveData.get(level).getAllGroups()) {
            if (group != null && groupId.equals(group.getUUID()) && !group.removed) {
                return Math.max(0, group.getSize());
            }
        }
        return Math.max(0, fallback);
    }

    private static float averageMorale(List<AbstractRecruitEntity> members) {
        if (members == null || members.isEmpty()) return 0.0F;
        float total = 0.0F;
        int count = 0;
        for (AbstractRecruitEntity recruit : members) {
            if (recruit == null || !recruit.isAlive()) continue;
            total += Math.max(0.0F, Math.min(100.0F, recruit.getMorale()));
            count++;
        }
        return count == 0 ? 0.0F : total / count;
    }

    private static float averageFatigue(List<AbstractRecruitEntity> members) {
        if (members == null || members.isEmpty()) return 0.0F;
        double total = 0.0D;
        int count = 0;
        for (AbstractRecruitEntity recruit : members) {
            if (recruit == null || !recruit.isAlive()) continue;
            total += recruit.getPersistentData().getDouble(FATIGUE_KEY);
            count++;
        }
        return count == 0 ? 0.0F : (float) (total / count);
    }

    private static void notifyAdvisor(ServerPlayer player, AdvisorEvent event, int cooldown, long now) {
        if (player == null || event == null || !BodyguardManager.isRtsActive(player)) return;
        String key = player.getUUID() + ":" + event.name();
        long last = ADVISOR_COOLDOWNS.getOrDefault(key, Long.MIN_VALUE / 4);
        if (now - last < cooldown) return;
        ADVISOR_COOLDOWNS.put(key, now);
        RTSCameraNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new AdvisorEventPacket(event)
        );
    }

    public static void clearPlayer(UUID playerId) {
        if (playerId == null) return;
        ADVISOR_COOLDOWNS.keySet().removeIf(key -> key.startsWith(playerId.toString() + ":"));
    }

    private static final class GroupRuntime {
        final UUID groupId;
        final UUID ownerId;
        final ServerLevel level;
        final List<AbstractRecruitEntity> members = new ArrayList<>();

        GroupRuntime(UUID groupId, UUID ownerId, ServerLevel level) {
            this.groupId = groupId;
            this.ownerId = ownerId;
            this.level = level;
        }

        void add(AbstractRecruitEntity recruit) {
            members.add(recruit);
        }
    }

    private static final class CombatState {
        final UUID ownerId;
        final int baseline;
        int lastCount;
        long lastCombatTick;
        int moraleState;
        boolean tiredWarned;
        boolean exhaustedWarned;
        /** True once this group UUID has routed during this combat session. */
        boolean routedThisBattle;
        /** One-shot gate for UNIT_ROUT advisor audio in this combat session. */
        boolean routAdvisorTriggered;

        CombatState(UUID ownerId, int baseline, int lastCount, long lastCombatTick) {
            this.ownerId = ownerId;
            this.baseline = baseline;
            this.lastCount = lastCount;
            this.lastCombatTick = lastCombatTick;
        }
    }
}
