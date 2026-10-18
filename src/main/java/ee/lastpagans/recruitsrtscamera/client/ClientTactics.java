package ee.lastpagans.recruitsrtscamera.client;

import ee.lastpagans.recruitsrtscamera.combat.TacticalSettings;
import ee.lastpagans.recruitsrtscamera.network.*;
import java.util.*;

public final class ClientTactics {
    private static final Map<UUID, TacticalSettings> SETTINGS = new HashMap<>();
    private ClientTactics() {}
    public static void apply(Map<UUID, TacticalSettings> settings) { SETTINGS.clear(); SETTINGS.putAll(settings); }
    public static void reset() { SETTINGS.clear(); }
    public static void send(TacticsControlPacket.Action action) {
        var ids = RTSCameraController.commanderSelectedGroups();
        if (!ids.isEmpty()) RTSCameraNetwork.CHANNEL.sendToServer(new TacticsControlPacket(new ArrayList<>(ids), action));
    }
    public static String selectionLabel() {
        var ids = RTSCameraController.commanderSelectedGroups();
        if (ids.isEmpty()) return "Select a group";
        TacticalSettings first = SETTINGS.getOrDefault(ids.iterator().next(), TacticalSettings.DEFAULT);
        if (ids.stream().anyMatch(id -> !first.equals(SETTINGS.getOrDefault(id, TacticalSettings.DEFAULT)))) return "Mixed tactical modes";
        return first.combat().name() + " | " + (first.skirmish() ? "Skirmish" : "Stand and fight")
                + " | Shields " + first.shield().name() + (first.holdFire() ? " | Hold fire" : "");
    }
}
