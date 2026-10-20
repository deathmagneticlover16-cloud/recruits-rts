package ee.lastpagans.recruitsrtscamera.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.util.Set;

/**
 * Command-mode key isolation for large modpacks.
 *
 * While RTS Camera is active in-world, standard Minecraft/Forge KeyMappings from
 * unrelated mods are forced up and their queued clicks are drained. RTS Camera and
 * Recruits are allowed, plus only the vanilla movement keys the embedded Freecam view
 * relies on. Freecam's own toggle/config keybinds are deliberately blocked while RTS
 * Camera owns it.
 *
 * Mods that deliberately poll raw GLFW input can bypass any KeyMapping-level gate;
 * those can be handled with a specific compatibility shim if one is ever encountered.
 */
public final class InputGate {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final Set<String> ALLOWED_VANILLA = Set.of(
            "key.forward",
            "key.back",
            "key.left",
            "key.right",
            "key.sprint"
    );

    private InputGate() {
    }

    public static boolean isBlocking() {
        return RTSCameraController.isActive() && MC.screen == null;
    }

    public static void suppressDisallowedKeyMappings() {
        if (!isBlocking() || MC.options == null || MC.options.keyMappings == null) {
            return;
        }
        for (KeyMapping mapping : MC.options.keyMappings) {
            if (mapping == null || isAllowed(mapping)) {
                continue;
            }
            mapping.setDown(false);
            while (mapping.consumeClick()) {
                // Drain queued clicks so another mod cannot consume them later this tick.
            }
        }
    }

    public static boolean shouldBlockRawMouse() {
        return isBlocking();
    }

    public static boolean shouldSuppress(KeyMapping mapping) {
        return isBlocking() && mapping != null && !isAllowed(mapping);
    }

    /** True when the physical key belongs to at least one command-mode-whitelisted mapping. */
    public static boolean isPhysicalKeyAllowed(int keyCode, int scanCode) {
        if (!isBlocking() || MC.options == null || MC.options.keyMappings == null) {
            return true;
        }
        for (KeyMapping mapping : MC.options.keyMappings) {
            if (mapping != null && isAllowed(mapping) && mapping.matches(keyCode, scanCode)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAllowed(KeyMapping mapping) {
        String name = mapping.getName();
        if (name == null) {
            return false;
        }
        return name.startsWith("key.recruits_rts_camera.")
                || name.startsWith("key.recruits.")
                || ALLOWED_VANILLA.contains(name);
    }
}
