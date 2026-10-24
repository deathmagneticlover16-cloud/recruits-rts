package ee.lastpagans.recruitsrtscamera.client;

import ee.lastpagans.recruitsrtscamera.battle.GroupBattleSnapshot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ClientBattleState {
    private static final Map<UUID, GroupBattleSnapshot> GROUPS = new HashMap<>();

    private ClientBattleState() {
    }

    public static GroupBattleSnapshot get(UUID groupId) {
        return groupId == null ? null : GROUPS.get(groupId);
    }

    public static void replace(Map<UUID, GroupBattleSnapshot> states) {
        Map<UUID, GroupBattleSnapshot> routed = new HashMap<>();
        GROUPS.forEach((id, state) -> {
            if (state != null && state.moraleState() >= 2) routed.put(id, state);
        });
        GROUPS.clear();
        if (states != null) GROUPS.putAll(states);
        // Routed members leave the ordinary runtime sample immediately, but their native group
        // card remains until escape. Preserve the authoritative ROUTING badge during that gap.
        routed.forEach(GROUPS::putIfAbsent);

        // Routing changes command authority, not Commander visibility. Keep the routed card,
        // selection and open command panels intact while the server rejects orders for it.
        RTSCommandFacade.invalidateCards();
    }

    public static void reset() {
        GROUPS.clear();
        RTSCommandFacade.invalidateCards();
    }

}
