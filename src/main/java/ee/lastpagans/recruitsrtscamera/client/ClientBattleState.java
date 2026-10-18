package ee.lastpagans.recruitsrtscamera.client;

import ee.lastpagans.recruitsrtscamera.battle.GroupBattleSnapshot;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class ClientBattleState {
    private static final Map<UUID, GroupBattleSnapshot> GROUPS = new HashMap<>();

    private ClientBattleState() {
    }

    public static GroupBattleSnapshot get(UUID groupId) {
        return groupId == null ? null : GROUPS.get(groupId);
    }

    public static void replace(Map<UUID, GroupBattleSnapshot> states) {
        GROUPS.clear();
        if (states != null) GROUPS.putAll(states);

        // Routed groups are no longer commandable units. Drop them from the live RTS
        // selection as soon as the authoritative battle-state packet reaches the client,
        // rather than leaving an invisible selected UUID capable of receiving commands.
        Set<UUID> routed = GROUPS.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().moraleState() >= 2)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        RTSCameraController.commanderDropRoutedGroups(routed);
        RTSCommandFacade.invalidateCards();
    }

    public static void reset() {
        GROUPS.clear();
        RTSCommandFacade.invalidateCards();
    }

}
