package ee.lastpagans.recruitsrtscamera.troops;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Group-scoped live storage boundary for authoritative troop statistics. The battle sampler
 * replaces this snapshot once per second, allowing integrations to read the same values sent
 * to Commander without rescanning recruit inventories.
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

    public static synchronized void replace(Map<UUID, TroopStatProfile> profiles) {
        BY_GROUP.clear();
        if (profiles != null) BY_GROUP.putAll(profiles);
    }

    public static synchronized void clear() { BY_GROUP.clear(); }
}
