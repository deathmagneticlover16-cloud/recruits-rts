package ee.lastpagans.recruitsrtscamera.network;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.world.RecruitsGroup;
import com.talhanation.recruits.world.RecruitsGroupsSaveData;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.battle.BattleMechanicsManager;
import ee.lastpagans.recruitsrtscamera.config.RTSServerConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class ServerGroupResolver {
    /** Protocol/hard-safety cap. Operational limits are server configurable below this value. */
    static final int MAX_GROUPS_PER_ORDER = 128;
    static final int MAX_RECRUITS_PER_ORDER = 4096;
    static final double MAX_RECRUIT_DISTANCE = 144.0D;

    private ServerGroupResolver() {
    }

    static Map<UUID, List<AbstractRecruitEntity>> resolve(ServerPlayer sender, List<UUID> requestedIds) {
        if (sender == null || requestedIds == null || requestedIds.isEmpty()) {
            return Map.of();
        }

        int configuredGroupLimit = Math.min(MAX_GROUPS_PER_ORDER, RTSServerConfig.MAX_GROUPS_PER_ORDER.get());
        int configuredRecruitLimit = Math.min(MAX_RECRUITS_PER_ORDER, RTSServerConfig.MAX_RECRUITS_PER_ORDER.get());
        Set<UUID> requested = new LinkedHashSet<>(requestedIds);
        requested.remove(null);
        if (requested.size() > configuredGroupLimit) {
            rejectTooLarge(sender, "groups", requested.size(), configuredGroupLimit);
            return Map.of();
        }

        ServerLevel level = sender.serverLevel();
        Map<UUID, List<AbstractRecruitEntity>> result = new LinkedHashMap<>();
        int total = 0;

        for (RecruitsGroup group : RecruitsGroupsSaveData.get(level).getAllGroups()) {
            if (group == null || group.removed || !requested.contains(group.getUUID())) {
                continue;
            }
            if (!sender.getUUID().equals(group.getPlayerUUID())) {
                continue;
            }
            // Routing is a loss of command, not another tactical stance. Even a stale client
            // packet or hidden selected UUID may not issue a fresh order until a new battle
            // session/rally has returned the group to command.
            if (BattleMechanicsManager.isGroupRouting(group.getUUID())) {
                continue;
            }

            List<AbstractRecruitEntity> loaded = new ArrayList<>();
            if (group.members != null) {
                for (UUID memberId : group.members) {
                    if (memberId == null) {
                        continue;
                    }
                    Entity entity = level.getEntity(memberId);
                    if (!(entity instanceof AbstractRecruitEntity recruit)) {
                        continue;
                    }
                    if (!sender.getUUID().equals(recruit.getOwnerUUID())) {
                        continue;
                    }
                    if (!recruit.isAlive()
                            || recruit.distanceToSqr(sender) > MAX_RECRUIT_DISTANCE * MAX_RECRUIT_DISTANCE) {
                        continue;
                    }
                    if (total + 1 > configuredRecruitLimit) {
                        // Old builds silently stopped here, leaving the tail of a large formation
                        // completely uncommanded. Reject the whole order instead so an army never
                        // receives a half-order without explanation.
                        rejectTooLarge(sender, "loaded recruits", total + 1, configuredRecruitLimit);
                        return Map.of();
                    }
                    loaded.add(recruit);
                    total++;
                }
            }
            if (!loaded.isEmpty()) {
                result.put(group.getUUID(), loaded);
            }
        }
        return result;
    }

    private static void rejectTooLarge(ServerPlayer sender, String kind, int actual, int limit) {
        sender.displayClientMessage(Component.literal(
                "RTS order rejected: " + actual + " " + kind + " exceeds the server limit of " + limit + "."
        ), true);
        RecruitsRTSCameraMod.LOGGER.warn(
                "Rejected oversized RTS order from {}: {} {} (limit {}).",
                sender.getGameProfile().getName(), actual, kind, limit
        );
    }
}
