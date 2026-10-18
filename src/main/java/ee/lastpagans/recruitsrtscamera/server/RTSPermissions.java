package ee.lastpagans.recruitsrtscamera.server;

import ee.lastpagans.recruitsrtscamera.config.RTSServerConfig;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

/** Authoritative server-side access check. Every consequential packet goes through here. */
public final class RTSPermissions {
    private RTSPermissions() {
    }

    public static boolean whitelistMode() {
        return RTSServerConfig.WHITELIST_MODE.get();
    }

    public static boolean isAllowed(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        if (!whitelistMode()) {
            return true;
        }
        if (RTSServerConfig.OPS_BYPASS_WHITELIST.get() && player.hasPermissions(2)) {
            return true;
        }

        String uuid = player.getUUID().toString().toLowerCase(Locale.ROOT);
        String name = player.getGameProfile().getName().toLowerCase(Locale.ROOT);
        for (String raw : RTSServerConfig.WHITELIST.get()) {
            if (raw == null) {
                continue;
            }
            String entry = raw.trim().toLowerCase(Locale.ROOT);
            if (entry.equals(uuid) || entry.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
