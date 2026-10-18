package ee.lastpagans.recruitsrtscamera.advisor;

import ee.lastpagans.recruitsrtscamera.client.CommanderGuiTextures;
import ee.lastpagans.recruitsrtscamera.config.RTSClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;

import java.util.ArrayDeque;
import java.util.Deque;

/** Small anti-spam queue for the supplied Latin-accent battle advisor pack. */
public final class AdvisorClient {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final Deque<AdvisorEvent> QUEUE = new ArrayDeque<>();
    private static final int PORTRAIT_SOURCE = 924;
    private static final int PORTRAIT_SIZE = 78;
    private static AdvisorEvent active;
    private static int activeTicks;
    private static boolean enabled = true;

    private AdvisorClient() {
    }

    public static void offer(AdvisorEvent event) {
        if (event == null || !enabled || !RTSClientConfig.ADVISOR_VOICES_ENABLED.get()) return;
        if (active != null && event.priority() > active.priority()) {
            QUEUE.clear();
            active = null;
            activeTicks = 0;
        }
        if (active == null) {
            play(event);
        } else if (QUEUE.size() < 3 && !QUEUE.contains(event)) {
            QUEUE.addLast(event);
        }
    }

    public static void tick() {
        if (activeTicks > 0) activeTicks--;
        if (activeTicks <= 0) {
            active = null;
            AdvisorEvent next = QUEUE.pollFirst();
            if (next != null) play(next);
        }
    }

    private static void play(AdvisorEvent event) {
        active = event;
        // Long enough for the portrait to be noticed, short enough not to live permanently
        // in the corner during a hectic battle. Voice length is intentionally not hard-coded.
        activeTicks = 115;
        if (MC.getSoundManager() != null) {
            MC.getSoundManager().play(SimpleSoundInstance.forUI(event.sound(), 1.0F));
        }
    }

    /**
     * Total War-style advisor portrait plus optional subtitle. The supplied portrait remains
     * a 924x924 source texture; it is sampled directly at render time rather than being exported
     * as a lower-resolution replacement asset.
     */
    public static void render(GuiGraphics graphics) {
        if (!enabled || active == null || graphics == null || MC.font == null) return;

        // Match Commander's GUI-scale normalization: GUI scale 2 and 3 get the same physical
        // advisor size instead of the portrait suddenly growing when Minecraft GUI scale changes.
        double guiScale = MC.getWindow().getGuiScale();
        float commanderScale = guiScale > 2.0D ? (float) (2.0D / guiScale) : 1.0F;
        int virtualWidth = Math.max(1, (int) Math.floor(MC.getWindow().getGuiScaledWidth() / commanderScale));
        graphics.pose().pushPose();
        graphics.pose().scale(commanderScale, commanderScale, 1.0F);
        try {
            int x = Math.max(6, virtualWidth - PORTRAIT_SIZE - 10);
            int y = 10;

            // Slight dark backing and gold rim keep the portrait readable over bright sky/terrain.
            graphics.fill(x - 2, y - 2, x + PORTRAIT_SIZE + 2, y + PORTRAIT_SIZE + 2, 0xD8141412);
            drawBorder(graphics, x - 2, y - 2, x + PORTRAIT_SIZE + 2, y + PORTRAIT_SIZE + 2, 0xFFD2B85B);
            graphics.pose().pushPose();
            graphics.pose().translate(x, y, 0.0F);
            float portraitScale = PORTRAIT_SIZE / (float) PORTRAIT_SOURCE;
            graphics.pose().scale(portraitScale, portraitScale, 1.0F);
            graphics.blit(CommanderGuiTextures.ADVISOR_LATIN,
                    0, 0, 0, 0, PORTRAIT_SOURCE, PORTRAIT_SOURCE, PORTRAIT_SOURCE, PORTRAIT_SOURCE);
            graphics.pose().popPose();

            if (!RTSClientConfig.ADVISOR_SUBTITLES.get()) return;
            String text = active.subtitle();
            int textWidth = Math.min(220, Math.max(110, MC.font.width(text) + 12));
            int boxX = Math.max(6, x - textWidth - 5);
            int boxY = y + PORTRAIT_SIZE - 28;
            graphics.fill(boxX, boxY, boxX + textWidth, boxY + 26, 0xE0181816);
            drawBorder(graphics, boxX, boxY, boxX + textWidth, boxY + 26, 0xFF8F825C);
            String shown = MC.font.width(text) <= textWidth - 10
                    ? text
                    : MC.font.plainSubstrByWidth(text, Math.max(0, textWidth - 10 - MC.font.width("..."))) + "...";
            graphics.drawString(MC.font, shown, boxX + 5, boxY + 9, 0xFFF1E8CE, false);
        } finally {
            graphics.pose().popPose();
        }
    }

    private static void drawBorder(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color) {
        graphics.fill(x1, y1, x2, y1 + 1, color);
        graphics.fill(x1, y2 - 1, x2, y2, color);
        graphics.fill(x1, y1, x1 + 1, y2, color);
        graphics.fill(x2 - 1, y1, x2, y2, color);
    }

    public static void reset() {
        QUEUE.clear();
        active = null;
        activeTicks = 0;
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void toggleEnabled() {
        enabled = !enabled;
        if (!enabled) reset();
    }
}
