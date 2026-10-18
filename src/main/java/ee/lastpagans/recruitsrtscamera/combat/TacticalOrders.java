package ee.lastpagans.recruitsrtscamera.combat;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.util.RecruitCommanderUtil;
import ee.lastpagans.recruitsrtscamera.formation.FormationRuntime;
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
import com.talhanation.recruits.entities.HorsemanEntity;
import com.talhanation.recruits.entities.NomadEntity;
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
        for (var entry : groups.entrySet()) {
            UUID id = entry.getKey();
            var members = entry.getValue();
            var s = data.settings(id);
            boolean cavalryMajority = majority(members, recruit -> recruit instanceof HorsemanEntity
                    || recruit instanceof NomadEntity);
            boolean rangedMajority = majority(members, RangedWeaponEligibility::hasRangedWeapon);
            if ((action == Action.MOUNT || action == Action.DISMOUNT) && !cavalryMajority
                    || (action == Action.SKIRMISH_ON || action == Action.SKIRMISH_OFF) && !rangedMajority) {
                unavailable++;
                continue;
            }
            switch (action) {
                case SKIRMISH_ON -> s = s.withSkirmish(true);
                case SKIRMISH_OFF -> s = s.withSkirmish(false);
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
            }
            data.setSettings(id, s);
            for (var recruit : members) {
                TacticalCombatManager.applyMode(recruit);
            }
            changed++;
        }
        sender.displayClientMessage(Component.literal(action.name().replace('_', ' ') + ": " + changed
                + " applied" + (unavailable == 0 ? "" : " · " + unavailable + " unavailable/on cooldown")), true);
    }

    private static boolean majority(List<AbstractRecruitEntity> members,
                                    java.util.function.Predicate<AbstractRecruitEntity> predicate) {
        if (members == null || members.isEmpty()) return false;
        long eligible = members.stream().filter(Objects::nonNull).filter(predicate).count();
        return eligible * 2 > members.size();
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
        FormationRuntime.releaseForAttack(sender, id);
        FormationService.releaseCustomForAttack(sender, id, members);
        for (var recruit : members) {
            recruit.getNavigation().stop(); recruit.clearMovePos(); recruit.clearHoldPos();
            recruit.setShouldMovePos(false); recruit.setShouldHoldPos(false);
            recruit.setShouldFollow(false); recruit.setIsFollowing(false); recruit.setTarget(null);
            recruit.reachedMovePos = false; recruit.holdFormation = false; recruit.isInFormation = false;
        }
    }
}
