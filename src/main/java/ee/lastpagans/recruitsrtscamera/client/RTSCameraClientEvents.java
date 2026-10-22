package ee.lastpagans.recruitsrtscamera.client;

import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.advisor.AdvisorClient;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class RTSCameraClientEvents {
    private RTSCameraClientEvents() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        // Suppress foreign mappings at both ends of the client tick. The controller's
        // own mappings are whitelisted, so it can consume them normally below.
        InputGate.suppressDisallowedKeyMappings();
        if (event.phase == TickEvent.Phase.END) {
            RTSCameraController.clientTick();
            CavalryRumbleClient.tick();
            AdvisorClient.tick();
            InputGate.suppressDisallowedKeyMappings();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onKey(InputEvent.Key event) {
        InputGate.suppressDisallowedKeyMappings();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteractionKeyMapping(InputEvent.InteractionKeyMappingTriggered event) {
        // Also block vanilla attack/use/pick actions when those mappings are rebound to
        // keyboard keys. Mouse-bound versions are already stopped by MouseButton.Pre.
        if (InputGate.isBlocking()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (RTSCameraController.handleMouseButton(
                event.getButton(),
                event.getAction(),
                event.getModifiers()
        )) {
            event.setCanceled(true);
            return;
        }
        if (InputGate.shouldBlockRawMouse()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (RTSCameraController.handleMouseScroll(event.getScrollDelta())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        if (RTSCameraController.isActive()) {
            RTSCameraController.stop("RTS Camera closed because the connection ended.");
        }
        RTSCommandFacade.clearPresentationCache();
        ClientPermissionState.reset();
        ClientCommanderState.reset();
        ClientBattleState.reset();
        ClientTactics.reset();
        AdvisorClient.reset();
        CavalryRumbleClient.reset();
        FogOfWarWorldRenderer.reset();
    }


    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderVanillaOverlay(RenderGuiOverlayEvent.Pre event) {
        if (!RTSCameraController.isActive()) {
            return;
        }

        // Total War mode owns the normal gameplay HUD area. Keep chat, titles, subtitles,
        // scoreboards and debug/F3 available, but suppress the Minecraft combat/status HUD.
        String path = event.getOverlay().id().getPath();
        if (path.equals("hotbar")
                || path.equals("crosshair")
                || path.equals("player_health")
                || path.equals("armor_level")
                || path.equals("food_level")
                || path.equals("mount_health")
                || path.equals("air_level")
                || path.equals("jump_bar")
                || path.equals("experience_bar")
                || path.equals("item_name")) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderLevel(RenderLevelStageEvent event) {
        GroundSelectionMarkerRenderer.render(event);
        FogOfWarWorldRenderer.render(event);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderHud(RenderGuiEvent.Post event) {
        RTSCameraController.renderHud(event.getGuiGraphics());
        AdvisorClient.render(event.getGuiGraphics());
    }

    /** Keep vanilla chat messages completely out of Commander's reserved lower HUD region. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onCustomizeChat(CustomizeGuiOverlayEvent.Chat event) {
        if (!RTSCameraController.isActive()) {
            return;
        }
        event.setPosY(event.getPosY() - CommanderOverlay.reservedChatPixels());
    }

    /** Move ChatScreen's text entry line above the Commander panel as well. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!RTSCameraController.isActive() || !(event.getScreen() instanceof ChatScreen)) {
            return;
        }
        int y = Math.max(4, event.getScreen().height - 12 - CommanderOverlay.reservedChatPixels());
        for (var listener : event.getListenersList()) {
            if (listener instanceof EditBox editBox) {
                editBox.setY(y);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (RTSCameraController.isActive() && event.getScreen() instanceof ChatScreen) {
            CommanderOverlay.renderOverChat(event.getGuiGraphics());
            AdvisorClient.render(event.getGuiGraphics());
        }
    }
}
