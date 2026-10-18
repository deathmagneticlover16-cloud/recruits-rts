package ee.lastpagans.recruitsrtscamera.server;

import ee.lastpagans.recruitsrtscamera.config.RTSServerConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Permission + lightweight packet-rate guard for public-server deployment. */
public final class ServerPacketGuard {
    public enum Lane { NORMAL, CUSTOM }

    private static final Map<UUID, Window> WINDOWS = new ConcurrentHashMap<>();

    private ServerPacketGuard() {
    }

    public static boolean allow(ServerPlayer sender) {
        return allow(sender, Lane.NORMAL);
    }

    public static boolean allow(ServerPlayer sender, Lane lane) {
        if (sender == null) {
            return false;
        }
        if (!RTSPermissions.isAllowed(sender)) {
            sender.displayClientMessage(Component.literal("You are not permitted to use Recruits: RTS Camera on this server."), true);
            return false;
        }

        long now = System.currentTimeMillis();
        Window window = WINDOWS.computeIfAbsent(sender.getUUID(), ignored -> new Window(now));
        synchronized (window) {
            if (now - window.startedAt >= 1000L) {
                window.startedAt = now;
                window.normal = 0;
                window.custom = 0;
            }
            int limit;
            if (lane == Lane.CUSTOM) {
                limit = RTSServerConfig.MAX_CUSTOM_ORDERS_PER_SECOND.get();
                if (++window.custom > limit) {
                    return false;
                }
            } else {
                limit = RTSServerConfig.MAX_ORDERS_PER_SECOND.get();
                if (++window.normal > limit) {
                    return false;
                }
            }
        }
        return true;
    }

    public static void clear(UUID playerId) {
        if (playerId != null) {
            WINDOWS.remove(playerId);
        }
    }

    private static final class Window {
        long startedAt;
        int normal;
        int custom;

        Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
