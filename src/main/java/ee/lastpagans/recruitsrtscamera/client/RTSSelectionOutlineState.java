package ee.lastpagans.recruitsrtscamera.client;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tiny client-side UUID registry used by the entity team-color mixin.
 *
 * <p>We keep using Minecraft's real glowing outline render pass, but override only the
 * outline color for RTS-selected entities. Scoreboard team membership is never changed,
 * so faction colors, allies, and the Commander banner renderer remain untouched.</p>
 */
public final class RTSSelectionOutlineState {
    public static final int RTS_YELLOW = 0xFFFF00;
    private static final Set<UUID> SELECTED = ConcurrentHashMap.newKeySet();

    private RTSSelectionOutlineState() {
    }

    public static boolean contains(UUID entityId) {
        return entityId != null && SELECTED.contains(entityId);
    }

    public static void replace(Collection<UUID> entityIds) {
        SELECTED.clear();
        if (entityIds != null) {
            SELECTED.addAll(entityIds);
        }
    }

    public static void clear() {
        SELECTED.clear();
    }
}
