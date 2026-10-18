package ee.lastpagans.recruitsrtscamera.client;

import java.util.UUID;

/** Small client mirror for server-authoritative Commander state. */
public final class ClientCommanderState {
    private static UUID bodyguardGroup;
    private static boolean bodyguardFollowsPlayer;

    private ClientCommanderState() {
    }

    public static UUID bodyguardGroup() {
        return bodyguardGroup;
    }

    public static boolean isBodyguard(UUID groupId) {
        return groupId != null && groupId.equals(bodyguardGroup);
    }

    public static boolean bodyguardFollowsPlayer() {
        return bodyguardGroup != null && bodyguardFollowsPlayer;
    }

    public static void setBodyguardGroup(UUID groupId) {
        setBodyguardState(groupId, bodyguardFollowsPlayer);
    }

    public static void setBodyguardState(UUID groupId, boolean followPlayer) {
        bodyguardGroup = groupId;
        bodyguardFollowsPlayer = groupId != null && followPlayer;
        RTSCommandFacade.invalidateCards();
    }

    public static void reset() {
        bodyguardGroup = null;
        bodyguardFollowsPlayer = false;
        RTSCommandFacade.invalidateCards();
    }
}
