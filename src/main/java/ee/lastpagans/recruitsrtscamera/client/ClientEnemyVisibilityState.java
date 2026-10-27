package ee.lastpagans.recruitsrtscamera.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Smooth client presentation of server-owned formation visibility. */
public final class ClientEnemyVisibilityState {
    private static final float FADE_OUT_PER_TICK = 0.055F;
    private static final float FADE_IN_PER_TICK = 0.14F;
    private static final Map<UUID, Entry> ENTRIES = new HashMap<>();

    private ClientEnemyVisibilityState() { }

    public static void apply(Map<UUID, Boolean> snapshot) {
        Set<UUID> absent = new HashSet<>(ENTRIES.keySet());
        snapshot.forEach((id, visible) -> {
            absent.remove(id);
            Entry entry = ENTRIES.computeIfAbsent(id, ignored -> new Entry());
            entry.visible = visible;
        });
        absent.forEach(id -> ENTRIES.get(id).visible = false);
    }

    public static void tick() {
        ENTRIES.values().forEach(entry -> {
            float target = entry.visible ? 1.0F : 0.0F;
            float step = entry.visible ? FADE_IN_PER_TICK : FADE_OUT_PER_TICK;
            entry.alpha += Math.max(-step, Math.min(step, target - entry.alpha));
        });
    }

    /** Unknown groups remain visible until their first authoritative snapshot arrives. */
    public static float alpha(UUID groupId) {
        Entry entry = ENTRIES.get(groupId);
        return entry == null ? 1.0F : entry.alpha;
    }

    public static boolean targetable(UUID groupId) {
        Entry entry = ENTRIES.get(groupId);
        return entry == null || entry.visible;
    }

    public static void clear() { ENTRIES.clear(); }

    private static final class Entry {
        boolean visible = true;
        float alpha = 1.0F;
    }
}
