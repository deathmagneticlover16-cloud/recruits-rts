package ee.lastpagans.recruitsrtscamera.client;

import org.lwjgl.glfw.GLFW;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Client-side Total War style selection bundles and direct group hotkeys. */
final class SelectionGroupManager {
    private static final int[] COLORS = {
            0xFFE34B4B, 0xFF4E83E6, 0xFF50B867, 0xFFE2C14D, 0xFF9B63D8,
            0xFF3CC6C3, 0xFFE78A42, 0xFFE8E8E8, 0xFFCE65A5, 0xFF7C9A50
    };
    private static final Map<Integer, LinkedHashSet<UUID>> BUNDLES = new LinkedHashMap<>();
    private static final Map<Integer, UUID> DIRECT_HOTKEYS = new LinkedHashMap<>();
    private static UUID pendingDirectGroup;

    private SelectionGroupManager() { }

    static boolean makeBundle(Collection<UUID> selected) {
        if (selected == null || selected.isEmpty()) return false;
        int slot = 0;
        for (int candidate = 1; candidate <= 10; candidate++) {
            if (!BUNDLES.containsKey(candidate) && !DIRECT_HOTKEYS.containsKey(keyForBundleSlot(candidate))) {
                slot = candidate;
                break;
            }
        }
        if (slot == 0) {
            RTSCameraController.commanderStatus("All ten selection group slots are already assigned.");
            return false;
        }
        Set<UUID> ids = Set.copyOf(selected);
        for (LinkedHashSet<UUID> bundle : BUNDLES.values()) bundle.removeAll(ids);
        BUNDLES.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        BUNDLES.put(slot, new LinkedHashSet<>(selected));
        RTSCameraController.commanderStatus("Selection group " + display(slot) + " created.");
        return true;
    }

    static void beginDirectAssignment(Collection<UUID> selected) {
        if (selected == null || selected.size() != 1) {
            RTSCameraController.commanderStatus("Select exactly one group before assigning a hotkey.");
            return;
        }
        pendingDirectGroup = selected.iterator().next();
        RTSCameraController.commanderStatus("Press an unused key, or Backspace to unbind this group.");
    }

    static boolean awaitingHotkey() {
        return pendingDirectGroup != null;
    }

    static boolean handleKeyPress(int key, int scanCode) {
        if (pendingDirectGroup != null) {
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                boolean removed = DIRECT_HOTKEYS.entrySet().removeIf(
                        entry -> entry.getValue().equals(pendingDirectGroup));
                pendingDirectGroup = null;
                RTSCameraController.commanderStatus(removed
                        ? "Group hotkey removed." : "This group had no direct hotkey.");
                return true;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                pendingDirectGroup = null;
                RTSCameraController.commanderStatus("Hotkey assignment cancelled.");
                return true;
            }
            int bundleSlot = bundleSlotForKey(key);
            if (invalidAssignmentKey(key) || RTSCameraKeyMappings.isReserved(key, scanCode)
                    || (bundleSlot != 0 && BUNDLES.containsKey(bundleSlot)) || DIRECT_HOTKEYS.containsKey(key)) {
                RTSCameraController.commanderStatus("That key is already reserved. Choose another key.");
                return true;
            }
            DIRECT_HOTKEYS.entrySet().removeIf(entry -> entry.getValue().equals(pendingDirectGroup));
            DIRECT_HOTKEYS.put(key, pendingDirectGroup);
            pendingDirectGroup = null;
            RTSCameraController.commanderStatus("Group hotkey assigned.");
            return true;
        }

        int bundleSlot = bundleSlotForKey(key);
        if (bundleSlot != 0 && !RTSCameraKeyMappings.isReserved(key, scanCode)) {
            Set<UUID> groups = BUNDLES.get(bundleSlot);
            if (groups != null && !groups.isEmpty()) {
                RTSCameraController.commanderSelectGroups(groups);
                RTSCameraController.commanderStatus("Selected group bundle " + display(bundleSlot) + ".");
                return true;
            }
        }
        UUID direct = DIRECT_HOTKEYS.get(key);
        if (direct != null && !RTSCameraKeyMappings.isReserved(key, scanCode)) {
            RTSCameraController.commanderSelectGroups(Set.of(direct));
            RTSCameraController.commanderStatus("Selected assigned group.");
            return true;
        }
        return false;
    }

    static int bundleSlot(UUID groupId) {
        if (groupId == null) return 0;
        for (Map.Entry<Integer, LinkedHashSet<UUID>> entry : BUNDLES.entrySet()) {
            if (entry.getValue().contains(groupId)) return entry.getKey();
        }
        return 0;
    }

    static int bundleColor(UUID groupId) {
        int slot = bundleSlot(groupId);
        return slot == 0 ? 0 : COLORS[(slot - 1) % COLORS.length];
    }

    static String display(int slot) {
        return slot == 10 ? "0" : Integer.toString(slot);
    }

    private static int bundleSlotForKey(int key) {
        if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) return key - GLFW.GLFW_KEY_0;
        return key == GLFW.GLFW_KEY_0 ? 10 : 0;
    }

    private static int keyForBundleSlot(int slot) {
        return slot == 10 ? GLFW.GLFW_KEY_0 : GLFW.GLFW_KEY_0 + slot;
    }

    private static boolean invalidAssignmentKey(int key) {
        return key == GLFW.GLFW_KEY_UNKNOWN || key == GLFW.GLFW_KEY_LEFT_SHIFT
                || key == GLFW.GLFW_KEY_RIGHT_SHIFT || key == GLFW.GLFW_KEY_LEFT_CONTROL
                || key == GLFW.GLFW_KEY_RIGHT_CONTROL || key == GLFW.GLFW_KEY_LEFT_ALT
                || key == GLFW.GLFW_KEY_RIGHT_ALT || key == GLFW.GLFW_KEY_LEFT_SUPER
                || key == GLFW.GLFW_KEY_RIGHT_SUPER;
    }
}
