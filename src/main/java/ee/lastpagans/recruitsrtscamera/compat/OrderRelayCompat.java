package ee.lastpagans.recruitsrtscamera.compat;

import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.network.NetworkEvent;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optional bridge into Recruits: Order Relay 3.1.x.
 *
 * <p>Order Relay normally sees only Villager Recruits' native packet handlers, while RTS
 * Camera uses its own server-authoritative packets for frontage, rotation, routes, etc.
 * This bridge feeds RTS orders into Order Relay's existing delayPacket(...) path without
 * making Order Relay a required dependency.</p>
 *
 * <p>0.9.6 makes multi-group RTS orders group-aware. The old bridge submitted the whole
 * army under whichever UUID happened to be first, so one group's relay requirement could
 * silently govern every selected formation. We now queue one relay stage per group and run
 * the geometry-changing RTS action once all participating groups are ready. If any group is
 * deliberately blocked by Order Relay (for example, too large and no relay bearer), the
 * combined order remains blocked instead of partially moving the army.</p>
 */
public final class OrderRelayCompat {
    private static final String MOD_ID = "recruitsorderrelay";
    private static final String HANDLER = "ee.lastpagans.recruitsorderrelay.OrderRelayHandler";
    private static final String COMMAND = "ee.lastpagans.recruitsorderrelay.RelayCommand";

    private static boolean initialized;
    private static boolean usable;
    private static Method commandById;
    private static Method delayPacket;
    private static Field pendingField;
    private static Field pendingPacketField;
    private static boolean failureLogged;

    private OrderRelayCompat() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    public static void executeOrRelay(
            NetworkEvent.Context context,
            Collection<UUID> groupIds,
            String commandId,
            Runnable action
    ) {
        if (action == null) {
            return;
        }
        if (context == null || context.getSender() == null || groupIds == null || groupIds.isEmpty()
                || commandId == null || commandId.isBlank() || !ensureReady()) {
            action.run();
            return;
        }

        LinkedHashSet<UUID> unique = new LinkedHashSet<>();
        for (UUID groupId : groupIds) {
            if (groupId != null) unique.add(groupId);
        }
        if (unique.isEmpty()) {
            action.run();
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Optional<Object> command = (Optional<Object>) commandById.invoke(null, commandId);
            if (command.isEmpty()) {
                action.run();
                return;
            }

            if (unique.size() == 1) {
                submitSingle(context, unique.iterator().next(), command.get(), action);
                return;
            }

            MultiGroupBarrier barrier = new MultiGroupBarrier(unique.size(), action);
            MinecraftServer server = context.getSender().getServer();
            for (UUID groupId : unique) {
                RTSRelayAdapter adapter = new RTSRelayAdapter(barrier::ready);
                CallbackInfo callback = new CallbackInfo("recruits_rts_camera_order_relay", true);
                delayPacket.invoke(null, adapter, context, groupId, command.get(), callback);

                if (!callback.isCancelled()) {
                    // Order Relay deliberately declined this group (for example no matching
                    // sound pack), so it is ready immediately and normal RTS behavior remains.
                    barrier.ready();
                } else if (!pendingContains(server, groupId, adapter)) {
                    // Cancelled but not placed in PENDING means Order Relay deliberately
                    // rejected the group (typically no required relay bearer). Never let the
                    // rest of a multi-group frontage move while this group stays behind.
                    barrier.block();
                }
            }
        } catch (ReflectiveOperationException | LinkageError error) {
            disableAfterFailure(error);
            action.run();
        }
    }

    private static void submitSingle(
            NetworkEvent.Context context,
            UUID relayGroup,
            Object command,
            Runnable action
    ) throws ReflectiveOperationException {
        CallbackInfo callback = new CallbackInfo("recruits_rts_camera_order_relay", true);
        delayPacket.invoke(null, new RTSRelayAdapter(action), context, relayGroup, command, callback);
        if (!callback.isCancelled()) {
            action.run();
        }
    }

    /** Adapter object stored only in Order Relay's in-memory pending map; never serialized. */
    public static final class RTSRelayAdapter {
        private final Runnable action;

        public RTSRelayAdapter(Runnable action) {
            this.action = action;
        }

        /** Method signature intentionally matches what Order Relay's replay reflection expects. */
        public void executeServerSide(NetworkEvent.Context ignored) {
            if (action != null) {
                action.run();
            }
        }
    }

    private static final class MultiGroupBarrier {
        private final AtomicInteger remaining;
        private final AtomicBoolean blocked = new AtomicBoolean(false);
        private final AtomicBoolean executed = new AtomicBoolean(false);
        private final Runnable action;

        private MultiGroupBarrier(int count, Runnable action) {
            this.remaining = new AtomicInteger(Math.max(1, count));
            this.action = action;
        }

        private void block() {
            blocked.set(true);
        }

        private void ready() {
            if (remaining.decrementAndGet() <= 0
                    && !blocked.get()
                    && executed.compareAndSet(false, true)) {
                action.run();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean pendingContains(MinecraftServer server, UUID groupId, Object expectedPacket) {
        if (server == null || groupId == null || pendingField == null || pendingPacketField == null) {
            // If 3.1.x changes the private queue field, fail conservative: a cancelled
            // multi-group submission is treated as blocked rather than guessed to be queued.
            return false;
        }
        try {
            Object value = pendingField.get(null);
            if (!(value instanceof Map<?, ?> allServers)) {
                return false;
            }
            Object serverValue = allServers.get(server);
            if (!(serverValue instanceof Map<?, ?> serverOrders)) {
                return false;
            }
            Object pending = serverOrders.get(groupId);
            return pending != null && pendingPacketField.get(pending) == expectedPacket;
        } catch (IllegalAccessException | LinkageError error) {
            disableAfterFailure(error);
            return false;
        }
    }

    private static synchronized boolean ensureReady() {
        if (initialized) {
            return usable;
        }
        initialized = true;
        if (!isLoaded()) {
            return false;
        }

        try {
            Class<?> commandClass = Class.forName(COMMAND);
            Class<?> handlerClass = Class.forName(HANDLER);
            commandById = commandClass.getMethod("byId", String.class);
            delayPacket = handlerClass.getMethod(
                    "delayPacket",
                    Object.class,
                    NetworkEvent.Context.class,
                    UUID.class,
                    commandClass,
                    CallbackInfo.class
            );
            pendingField = handlerClass.getDeclaredField("PENDING");
            pendingField.setAccessible(true);
            Class<?> pendingClass = null;
            for (Class<?> nested : handlerClass.getDeclaredClasses()) {
                if ("PendingOrder".equals(nested.getSimpleName())) {
                    pendingClass = nested;
                    break;
                }
            }
            if (pendingClass == null) {
                throw new ClassNotFoundException("Order Relay PendingOrder nested class not found");
            }
            pendingPacketField = pendingClass.getDeclaredField("packet");
            pendingPacketField.setAccessible(true);
            usable = true;
            RecruitsRTSCameraMod.LOGGER.info(
                    "Recruits: Order Relay compatibility enabled for RTS-native commands (group-aware barrier)."
            );
            return true;
        } catch (ReflectiveOperationException | LinkageError error) {
            disableAfterFailure(error);
            return false;
        }
    }

    private static void disableAfterFailure(Throwable error) {
        usable = false;
        if (!failureLogged) {
            failureLogged = true;
            RecruitsRTSCameraMod.LOGGER.warn(
                    "Order Relay is installed but its 3.1.x bridge API could not be resolved; RTS orders will execute immediately.",
                    error
            );
        }
    }
}
