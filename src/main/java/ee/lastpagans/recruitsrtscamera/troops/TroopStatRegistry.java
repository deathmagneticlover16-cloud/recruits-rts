package ee.lastpagans.recruitsrtscamera.troops;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Group-scoped storage boundary for troop statistics. No gameplay system consumes these
 * profiles yet; persistence/import code can be added behind this API without changing UI IDs.
 */
public final class TroopStatRegistry {
    private static final Map<UUID, TroopStatProfile> BY_GROUP = new HashMap<>();

    private TroopStatRegistry() { }

    public static synchronized TroopStatProfile get(UUID groupId) {
        return groupId == null ? TroopStatProfile.NEUTRAL
                : BY_GROUP.getOrDefault(groupId, TroopStatProfile.NEUTRAL);
    }

    public static synchronized void put(UUID groupId, TroopStatProfile profile) {
        if (groupId == null) throw new IllegalArgumentException("Group id is required");
        if (profile == null || profile == TroopStatProfile.NEUTRAL) BY_GROUP.remove(groupId);
        else BY_GROUP.put(groupId, profile);
    }

    public static synchronized Map<UUID, TroopStatProfile> snapshot() {
        return Map.copyOf(BY_GROUP);
    }

    public static synchronized void clear() { BY_GROUP.clear(); }
}
