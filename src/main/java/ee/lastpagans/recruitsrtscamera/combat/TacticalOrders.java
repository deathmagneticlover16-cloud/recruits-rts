package ee.lastpagans.recruitsrtscamera.combat;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.IRangedRecruit;
import com.talhanation.recruits.util.RecruitCommanderUtil;
import ee.lastpagans.recruitsrtscamera.battle.BattleMechanicsManager;
import ee.lastpagans.recruitsrtscamera.battle.RoutedGroupManager;
import ee.lastpagans.recruitsrtscamera.compat.BetterFormationsCompat;
import ee.lastpagans.recruitsrtscamera.formation.FormationService;
import ee.lastpagans.recruitsrtscamera.movement.RTSMovementBoostManager;
import ee.lastpagans.recruitsrtscamera.network.RouteManager;
import ee.lastpagans.recruitsrtscamera.network.TacticsControlPacket.Action;
import ee.lastpagans.recruitsrtscamera.persistence.RTSGroupTacticsSavedData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import java.util.*;

public final class TacticalOrders {
    public static final String DISMOUNTED = "RTSRemainDismounted";
    public static final String REMEMBERED_MOUNT = "RTSRememberedMount";
    private TacticalOrders() {}

    public static void execute(ServerPlayer sender, Map<UUID, List<AbstractRecruitEntity>> groups, Action action) {
        var level = sender.serverLevel();
        var data = RTSGroupTacticsSavedData.get(level);
        int changed = 0, unavailable = 0;
        Set<UUID> reserved = new HashSet<>();
        Set<UUID> affectedEnemies = new HashSet<>();
        for (var entry : groups.entrySet()) {
            UUID id = entry.getKey();
            var members = entry.getValue();
            var s = data.settings(id);
            switch (action) {
                case SKIRMISH_ON -> s = s.withSkirmish(true);
                case SKIRMISH_OFF -> s = s.withSkirmish(false);
                case RANGED -> s = s.withCombat(TacticalSettings.CombatMode.RANGED);
                case MELEE -> s = s.withCombat(TacticalSettings.CombatMode.MELEE);
                case NATIVE_COMBAT -> s = s.withCombat(TacticalSettings.CombatMode.NATIVE);
                case SHIELD_FRONT -> s = s.withShield(TacticalSettings.ShieldMode.FRONT);
                case SHIELD_UP -> s = s.withShield(TacticalSettings.ShieldMode.UP);
                case SHIELD_AUTO -> s = s.withShield(TacticalSettings.ShieldMode.AUTO);
                case SHIELD_NATIVE -> s = s.withShield(TacticalSettings.ShieldMode.NATIVE);
                case MOUNT, DISMOUNT -> {
                    cancelMovement(sender, id, members);
                    for (var recruit : members) {
                        if (action == Action.DISMOUNT) {
                            if (recruit.getVehicle() instanceof AbstractHorse horse) {
                                recruit.getPersistentData().putUUID(REMEMBERED_MOUNT, horse.getUUID());
                                RecruitCommanderUtil.setRecruitsDismount(List.of(recruit));
                                recruit.stopRiding();
                            }
                            recruit.getPersistentData().putBoolean(DISMOUNTED, true);
                            recruit.setShouldMount(false);
                            changed++;
                        } else {
                            AbstractHorse horse = findMount(level, sender, recruit, reserved);
                            if (horse == null) { unavailable++; continue; }
                            reserved.add(horse.getUUID());
                            recruit.getPersistentData().putBoolean(DISMOUNTED, false);
                            recruit.getPersistentData().putUUID(REMEMBERED_MOUNT, horse.getUUID());
                            recruit.shouldMount(true, horse.getUUID());
                            changed++;
                        }
                    }
                    continue;
                }
                case SECOND_WIND, HOLD_LINE, WAR_CRY -> {
                    long now = level.getGameTime();
                    String key = action.name();
                    if (data.timer(id, key) > now) { unavailable++; continue; }
                    if (action == Action.SECOND_WIND) {
                        for (var recruit : members) BattleMechanicsManager.recoverFatigue(recruit, 25);
                        data.setTimer(id, key, now + 1200);
                    } else if (action == Action.HOLD_LINE) {
                        data.setTimer(id, "holdLineUntil", now + 300);
                        data.setTimer(id, key, now + 1200);
                    } else {
                        for (var recruit : members) {
                            for (var enemy : level.getEntitiesOfClass(AbstractRecruitEntity.class,
                                    recruit.getBoundingBox().inflate(18, 8, 18),
                                    e -> e.isAlive() && e.getGroup() != null && !RoutedGroupManager.isRouting(e)
                                            && recruit.canAttack(e) && !recruit.isAlliedTo(e))) {
                                if (recruit.distanceToSqr(enemy) <= 18 * 18 && recruit.hasLineOfSight(enemy))
                                    affectedEnemies.add(enemy.getGroup());
                            }
                        }
                        data.setTimer(id, key, now + 1800);
                    }
                    changed++;
                    continue;
                }
            }
            data.setSettings(id, s);
            for (var recruit : members) {
                if (recruit instanceof IRangedRecruit && (action == Action.RANGED || action == Action.MELEE || action == Action.NATIVE_COMBAT)) {
                    recruit.stopUsingItem();
                    recruit.setShouldRanged(action != Action.MELEE);
                }
                TacticalCombatManager.applyMode(recruit);
            }
            changed++;
        }
        // One morale shock per enemy formation, even when many selected groups shout together.
        for (UUID enemy : affectedEnemies) {
            if (data.timer(enemy, "warCryProtectionUntil") <= level.getGameTime()) {
                BattleMechanicsManager.adjustGroupMorale(level, enemy, -8);
                data.setTimer(enemy, "warCryProtectionUntil", level.getGameTime() + 200);
            }
        }
        sender.displayClientMessage(Component.literal(action.name().replace('_', ' ') + ": " + changed
                + " applied" + (unavailable == 0 ? "" : " · " + unavailable + " unavailable/on cooldown")), true);
    }

    private static AbstractHorse findMount(ServerLevel level, ServerPlayer owner, AbstractRecruitEntity recruit, Set<UUID> reserved) {
        if (recruit.getVehicle() instanceof AbstractHorse current) return current;
        UUID remembered = recruit.getPersistentData().hasUUID(REMEMBERED_MOUNT)
                ? recruit.getPersistentData().getUUID(REMEMBERED_MOUNT) : recruit.getMountUUID();
        Entity raw = remembered == null ? null : level.getEntity(remembered);
        if (raw instanceof AbstractHorse horse && availableHorse(recruit, owner, horse, reserved, true)) return horse;
        return level.getEntitiesOfClass(AbstractHorse.class, recruit.getBoundingBox().inflate(24),
                        h -> availableHorse(recruit, owner, h, reserved, false)).stream()
                .min(Comparator.comparingDouble(recruit::distanceToSqr)).orElse(null);
    }

    private static boolean availableHorse(AbstractRecruitEntity recruit, ServerPlayer owner, AbstractHorse horse,
                                          Set<UUID> reserved, boolean remembered) {
        return horse.isAlive() && !horse.isVehicle() && !reserved.contains(horse.getUUID())
                && recruit.distanceToSqr(horse) <= 32 * 32 && recruit.canMountEntity(horse)
                && (owner.getUUID().equals(horse.getOwnerUUID()) || (remembered && horse.getOwnerUUID() == null));
    }

    public static void cancelMovement(ServerPlayer sender, UUID id, List<AbstractRecruitEntity> members) {
        RTSGroupAttackManager.cancel(List.of(id));
        RouteManager.cancel(sender, List.of(id));
        RTSMovementBoostManager.cancelForcedOrders(List.of(id));
        BetterFormationsCompat.releaseForAttack(sender, id);
        FormationService.releaseCustomForAttack(sender, id, members);
        for (var recruit : members) {
            recruit.getNavigation().stop(); recruit.clearMovePos(); recruit.clearHoldPos();
            recruit.setShouldMovePos(false); recruit.setShouldHoldPos(false);
            recruit.setShouldFollow(false); recruit.setIsFollowing(false); recruit.setTarget(null);
            recruit.reachedMovePos = false; recruit.holdFormation = false; recruit.isInFormation = false;
        }
    }
}
