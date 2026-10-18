package ee.lastpagans.recruitsrtscamera.client;

import com.mojang.blaze3d.platform.InputConstants;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class RTSCameraKeyMappings {
    public static final String CATEGORY = "key.categories.recruits_rts_camera";

    public static final KeyMapping TOGGLE = new KeyMapping(
            "key.recruits_rts_camera.toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            CATEGORY
    );

    public static final KeyMapping CLEAR = new KeyMapping(
            "key.recruits_rts_camera.clear",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_C,
            CATEGORY
    );

    public static final KeyMapping MOVEMENT_PACE = new KeyMapping(
            "key.recruits_rts_camera.movement_pace",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            CATEGORY
    );

    public static final KeyMapping FORMATION = new KeyMapping(
            "key.recruits_rts_camera.formation",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            CATEGORY
    );

    public static final KeyMapping ROTATE = new KeyMapping(
            "key.recruits_rts_camera.rotate",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Z,
            CATEGORY
    );

    public static final KeyMapping ARMY_PRESET = new KeyMapping(
            "key.recruits_rts_camera.army_preset",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_P,
            CATEGORY
    );


    public static final KeyMapping STRATEGIC_FIRE = new KeyMapping(
            "key.recruits_rts_camera.strategic_fire",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F,
            CATEGORY
    );

    public static final KeyMapping HOLD_FIRE = new KeyMapping(
            "key.recruits_rts_camera.hold_fire",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_H,
            CATEGORY
    );

    public static final KeyMapping FIRE_AT_WILL = new KeyMapping(
            "key.recruits_rts_camera.fire_at_will",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_J,
            CATEGORY
    );


    public static final KeyMapping ROUTE_MODE = new KeyMapping(
            "key.recruits_rts_camera.route_mode",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Y,
            CATEGORY
    );

    public static final KeyMapping COMMIT_ROUTE = new KeyMapping(
            "key.recruits_rts_camera.commit_route",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_ENTER,
            CATEGORY
    );

    public static final KeyMapping RESET_ROUTE = new KeyMapping(
            "key.recruits_rts_camera.reset_route",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_BACKSPACE,
            CATEGORY
    );

    public static final KeyMapping HALT = new KeyMapping(
            "key.recruits_rts_camera.halt",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            CATEGORY
    );

    private RTSCameraKeyMappings() {
    }

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE);
        event.register(CLEAR);
        event.register(MOVEMENT_PACE);
        event.register(FORMATION);
        event.register(ROTATE);
        event.register(ARMY_PRESET);
        event.register(STRATEGIC_FIRE);
        event.register(HOLD_FIRE);
        event.register(FIRE_AT_WILL);
        event.register(ROUTE_MODE);
        event.register(COMMIT_ROUTE);
        event.register(RESET_ROUTE);
        event.register(HALT);
    }

    /** True when a raw key is already owned by an RTS action. */
    public static boolean isReserved(int key, int scanCode) {
        return TOGGLE.matches(key, scanCode)
                || CLEAR.matches(key, scanCode)
                || MOVEMENT_PACE.matches(key, scanCode)
                || FORMATION.matches(key, scanCode)
                || ROTATE.matches(key, scanCode)
                || ARMY_PRESET.matches(key, scanCode)
                || STRATEGIC_FIRE.matches(key, scanCode)
                || HOLD_FIRE.matches(key, scanCode)
                || FIRE_AT_WILL.matches(key, scanCode)
                || ROUTE_MODE.matches(key, scanCode)
                || COMMIT_ROUTE.matches(key, scanCode)
                || RESET_ROUTE.matches(key, scanCode)
                || HALT.matches(key, scanCode);
    }
}
