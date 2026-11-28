package ee.lastpagans.recruitsrtscamera.client;

import ee.lastpagans.recruitsrtscamera.battle.GroupBattleSnapshot;

import java.util.Map;
import java.util.UUID;

public final class ClientBattleState {
    private static final Map<UUID, GroupBattleSnapshot> GROUPS = new java.util.HashMap<>();

    private ClientBattleState() {
    }

    public static GroupBattleSnapshot get(UUID groupId) {
        return groupId == null ? null : GROUPS.get(groupId);
    }

    public static void replace(Map<UUID, GroupBattleSnapshot> states) {
        GROUPS.clear();
        if (states != null) GROUPS.putAll(states);
        // Routing is now represented by authoritative persisted server snapshots through
        // retreat and rally. If the server omits a routed UUID, it is genuinely gone; keeping
        // the previous client row made destroyed/despawned units haunt the card layout forever.
        RTSCommandFacade.invalidateCards();
    }

    public static void reset() {
        GROUPS.clear();
        RTSCommandFacade.invalidateCards();
    }

}
