package ee.lastpagans.recruitsrtscamera.client;

import ee.lastpagans.recruitsrtscamera.formation.FormationKind;
import ee.lastpagans.recruitsrtscamera.config.RTSClientConfig;
import ee.lastpagans.recruitsrtscamera.math.ScreenProjection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Private-server Total War command interface.
 *
 * <p>The overlay is rendered directly over the battlefield rather than opening a normal
 * Minecraft Screen. This keeps RTS camera/selection/orders live while the interface is visible.</p>
 */
public final class CommanderOverlay {
    private static final Minecraft MC = Minecraft.getInstance();

    private static final int MARGIN = 4;
    private static final int PANEL_HEIGHT = 116;
    private static final int COMMAND_BAR_HEIGHT = 22;
    private static final int COMMAND_BUTTON_GAP = 3;
    private static final int COMMAND_BUTTON_SIZE = 18;
    private static final int COMMAND_ICON_SIZE = 16;
    // Cards are intentionally narrow and tall enough to preserve the authored 32x32
    // tactical sprites and chevrons at native cell size.
    private static final int CARD_WIDTH = 46;
    private static final int CARD_HEIGHT = 84;
    private static final int CARD_GAP = 3;
    private static final int NAV_WIDTH = 18;
    private static final int CELL = 32;
    private static final int BADGE_RENDER_SIZE = 16;
    private static final int CHEVRON_RENDER_SIZE = 16;
    private static final int CARD_MORALE_HEIGHT = 2;
    private static final int CARD_FATIGUE_HEIGHT = 2;
    private static final int MORALE_STATUS_RENDER_SIZE = 12;
    private static final int FORMATION_ICON_SIZE = 16;
    private static final int FORMATION_BUTTON_SIZE = 23;
    private static final int FORMATION_GRID_GAP = 2;
    private static final int BANNER_RENDER_WIDTH = 32;
    private static final int BANNER_RENDER_HEIGHT = 64;
    private static final int BANNER_SOURCE_WIDTH = 64;
    private static final int BANNER_SOURCE_HEIGHT = 128;
    private static final int UNIT_ICON_SHEET_HEIGHT = 256;

    private static final int GOLD = 0xFFE1C46F;
    private static final int TEXT = 0xFFE8E2D4;
    private static final int MUTED = 0xFFAFA99B;

    private static int page;
    private static boolean formationMenuOpen;
    private static boolean tacticsMenuOpen;
    private static Layout lastLayout = Layout.EMPTY;
    private static Boolean enemyBannersVisibleOverride;

    private CommanderOverlay() {
    }

    public static void reset() {
        page = 0;
        formationMenuOpen = false;
        tacticsMenuOpen = false;
        lastLayout = Layout.EMPTY;
        enemyBannersVisibleOverride = null;
    }

    public static void render(GuiGraphics graphics) {
        renderInternal(graphics, false);
    }

    /** Rendered after ChatScreen so chat can never visually cover the Commander controls. */
    public static void renderOverChat(GuiGraphics graphics) {
        if (!(MC.screen instanceof ChatScreen)) return;
        renderInternal(graphics, true);
    }

    private static void renderInternal(GuiGraphics graphics, boolean chatMode) {
        if (!RTSCommandFacade.active() || MC.font == null) {
            lastLayout = Layout.EMPTY;
            return;
        }
        if (!chatMode && MC.screen != null) {
            lastLayout = Layout.EMPTY;
            return;
        }

        float scale = commanderScale();
        graphics.pose().pushPose();
        graphics.pose().scale(scale, scale, 1.0F);
        try {
            List<RTSCommandFacade.UnitCardView> groups = RTSCommandFacade.unitCards();
            Layout layout = buildLayout(groups.size(), chatMode);
            lastLayout = layout;
            page = Math.max(0, Math.min(page, Math.max(0, layout.pageCount() - 1)));

            renderFloatingBanners(graphics, groups, layout);
            renderShell(graphics, layout);
            renderUnitCards(graphics, groups, layout);
            renderSelectedLabel(graphics, layout);
            renderCommandPanel(graphics, layout);
            if (formationMenuOpen) renderFormationMenu(graphics, layout);
            if (tacticsMenuOpen) renderTacticsMenu(graphics, layout);
        } finally {
            graphics.pose().popPose();
        }
    }

    public static boolean handleMouseButton(int button, int action, int modifiers) {
        if (!RTSCommandFacade.active() || !RTSCommandFacade.commandCursor() || MC.screen != null) {
            return false;
        }

        int mouseX = guiMouseX();
        int mouseY = guiMouseY();
        Layout layout = currentLayout();
        Bounds popup = formationPopupBounds(layout);
        boolean overPopup = formationMenuOpen && popup.contains(mouseX, mouseY);
        boolean overTactics = tacticsMenuOpen && tacticsBounds(layout).contains(mouseX, mouseY);
        if (overTactics) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && action == GLFW.GLFW_PRESS
                    && !RTSCameraController.commanderSelectedGroups().isEmpty()) {
                for (TacticEntry entry : tacticEntries(layout)) if (entry.bounds.contains(mouseX, mouseY)) {
                    ClientTactics.send(entry.action);
                    return true;
                }
            }
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && action == GLFW.GLFW_PRESS
                && !layout.tacticsButton().contains(mouseX, mouseY)) tacticsMenuOpen = false;

        // Floating banners are genuine RTS hit targets, not decoration. Friendly LMB selects;
        // hostile RMB issues the same attack order as right-clicking one of that group's recruits.
        if (action == GLFW.GLFW_PRESS) {
            for (BannerHitbox banner : bannerHitboxes(layout)) {
                if (!banner.bounds().contains(mouseX, mouseY)) continue;
                if (!banner.enemy() && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    boolean additive = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
                    RTSCommandFacade.selectUnit(banner.groupId(), additive);
                } else if (banner.enemy() && button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    RTSCommandFacade.attackEntityId(banner.representativeEntityId());
                }
                return true;
            }
        }

        if (!layout.contains(mouseX, mouseY) && !overPopup) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && action == GLFW.GLFW_PRESS && formationMenuOpen) {
                formationMenuOpen = false;
            }
            return false;
        }

        // Any mouse input over the fixed Commander UI belongs to the UI, never the battlefield.
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || action != GLFW.GLFW_PRESS) {
            return true;
        }

        if (overPopup) {
            for (FormationHitbox hitbox : formationHitboxes(layout)) {
                if (hitbox.bounds().contains(mouseX, mouseY)) {
                    RTSCommandFacade.setFormation(hitbox.kind());
                    formationMenuOpen = false;
                    return true;
                }
            }
            return true;
        }

        if (layout.pageCount() > 1 && layout.prevButton().contains(mouseX, mouseY)) {
            page = Math.floorMod(page - 1, layout.pageCount());
            return true;
        }
        if (layout.pageCount() > 1 && layout.nextButton().contains(mouseX, mouseY)) {
            page = Math.floorMod(page + 1, layout.pageCount());
            return true;
        }

        if (layout.tacticsButton().contains(mouseX, mouseY)) {
            tacticsMenuOpen = !tacticsMenuOpen;
            formationMenuOpen = false;
            return true;
        }
        if (layout.formationButton().contains(mouseX, mouseY)) {
            tacticsMenuOpen = false;
            formationMenuOpen = !formationMenuOpen;
            return true;
        }
        if (layout.haltButton().contains(mouseX, mouseY)) {
            RTSCommandFacade.halt();
            return true;
        }
        if (layout.aggressionButton().contains(mouseX, mouseY)) {
            RTSCommandFacade.toggleAggression();
            return true;
        }
        if (layout.shieldsButton().contains(mouseX, mouseY)) {
            RTSCommandFacade.toggleShields();
            return true;
        }
        if (layout.slowMarchButton().contains(mouseX, mouseY)) {
            RTSCommandFacade.toggleSlowMarch();
            return true;
        }
        if (layout.looseSpacingButton().contains(mouseX, mouseY)) {
            RTSCommandFacade.toggleLooseSpacing();
            return true;
        }
        if (layout.fireControlButton().contains(mouseX, mouseY)) {
            RTSCommandFacade.toggleFireControl();
            return true;
        }
        if (layout.missileButton().contains(mouseX, mouseY)) {
            RTSCommandFacade.toggleUseMissiles();
            return true;
        }
        if (layout.bodyguardButton().contains(mouseX, mouseY)) {
            RTSCommandFacade.toggleBodyguard();
            return true;
        }
        if (layout.bodyguardFollowButton().contains(mouseX, mouseY)) {
            RTSCommandFacade.toggleBodyguardFollow();
            return true;
        }
        if (layout.enemyBannersButton().contains(mouseX, mouseY)) {
            toggleEnemyBanners();
            return true;
        }
        List<RTSCommandFacade.UnitCardView> groups = RTSCommandFacade.unitCards();
        for (CardHitbox hitbox : cardHitboxes(groups, layout)) {
            if (hitbox.bounds().contains(mouseX, mouseY)) {
                boolean additive = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
                RTSCommandFacade.selectUnit(hitbox.view().groupId(), additive);
                return true;
            }
        }
        return true;
    }

    public static boolean handleMouseScroll(double delta) {
        if (!RTSCommandFacade.active() || !RTSCommandFacade.commandCursor() || MC.screen != null) {
            return false;
        }
        Layout layout = currentLayout();
        int mouseX = guiMouseX();
        int mouseY = guiMouseY();
        if (tacticsMenuOpen && tacticsBounds(layout).contains(mouseX, mouseY)) return true;
        if (!layout.contains(mouseX, mouseY)
                && !(formationMenuOpen && formationPopupBounds(layout).contains(mouseX, mouseY))) {
            return false;
        }
        if (layout.pageCount() > 1 && Math.abs(delta) > 0.001D) {
            page = Math.floorMod(page + (delta < 0.0D ? 1 : -1), layout.pageCount());
        }
        return true;
    }

    public static boolean isMouseOver() {
        Layout layout = currentLayout();
        int x = guiMouseX();
        int y = guiMouseY();
        if (tacticsMenuOpen && tacticsBounds(layout).contains(x, y)) return true;
        if (layout.contains(x, y) || (formationMenuOpen && formationPopupBounds(layout).contains(x, y))) return true;
        for (BannerHitbox banner : bannerHitboxes(layout)) if (banner.bounds().contains(x, y)) return true;
        return false;
    }

    /** Friendly group currently hovered through a unit card or friendly floating banner. */
    static java.util.UUID hoveredFriendlyGroupId() {
        if (!RTSCommandFacade.active() || !RTSCommandFacade.commandCursor() || MC.screen != null) return null;
        Layout layout = currentLayout();
        int x = guiMouseX();
        int y = guiMouseY();
        for (CardHitbox hitbox : cardHitboxes(RTSCommandFacade.unitCards(), layout)) {
            if (hitbox.bounds().contains(x, y)) return hitbox.view().groupId();
        }
        for (BannerHitbox banner : bannerHitboxes(layout)) {
            if (!banner.enemy() && banner.bounds().contains(x, y)) return banner.groupId();
        }
        return null;
    }

    private static void renderShell(GuiGraphics graphics, Layout layout) {
        int panelWidth = layout.panelRight() - layout.panelX();
        int panelHeight = layout.panelBottom() - layout.panelY();
        blitScaled(graphics, CommanderGuiTextures.MAIN_COMMAND_BAR,
                layout.panelX(), layout.panelY(), panelWidth, panelHeight, 512, 110);

        // 0.9.8: the Commander UI no longer burns a permanent left information column
        // or a right orders column. Unit cards own the full upper strip, while one long
        // Total War-style command bar spans underneath them.
        Bounds commandBar = commandBarBounds(layout);
        // Keep this prototype bar pixel-clean. Stretching the old tall orders-panel texture
        // down to a thin strip eats one-pixel borders, so the strip is drawn directly until
        // the dedicated human-made command-bar asset arrives.
        graphics.fill(commandBar.x1(), commandBar.y1(), commandBar.x2(), commandBar.y2(), 0xE8161614);
        drawBorder(graphics, commandBar, 0xFF6F6544);
    }

    private static void renderUnitCards(
            GuiGraphics graphics,
            List<RTSCommandFacade.UnitCardView> groups,
            Layout layout
    ) {
        if (groups.isEmpty()) {
            graphics.drawCenteredString(MC.font,
                    "No commandable Recruits groups",
                    (layout.cardsX() + layout.cardsRight()) / 2,
                    layout.panelY() + 52,
                    0xFFAAA69B);
            return;
        }

        List<CardHitbox> hitboxes = cardHitboxes(groups, layout);
        int mouseX = guiMouseX();
        int mouseY = guiMouseY();

        for (CardHitbox hitbox : hitboxes) {
            Bounds b = hitbox.bounds();
            RTSCommandFacade.UnitCardView view = hitbox.view();
            boolean hovered = b.contains(mouseX, mouseY) && RTSCommandFacade.commandCursor();

            // Never draw an opaque selection texture over the user's card artwork.
            // Selection/hover live outside the exact runtime card texture.
            if (view.selected()) {
                drawBorder(graphics, new Bounds(b.x1() - 2, b.y1() - 2, b.x2() + 2, b.y2() + 2), 0xFFFFE58A);
                drawBorder(graphics, new Bounds(b.x1() - 1, b.y1() - 1, b.x2() + 1, b.y2() + 1), GOLD);
            } else if (hovered) {
                drawBorder(graphics, new Bounds(b.x1() - 1, b.y1() - 1, b.x2() + 1, b.y2() + 1), 0xFFB6B2A7);
            }
            graphics.blit(CommanderGuiTextures.CARD_BACKGROUND,
                    b.x1(), b.y1(), 0, 0, CARD_WIDTH, CARD_HEIGHT, CARD_WIDTH, CARD_HEIGHT);

            // Restore the compact Total War card composition: small tactical type left,
            // small veterancy chevrons right. Source cells remain the author's full 32x32;
            // they are only scaled at render time and are never rewritten/compressed on disk.
            int iconX = b.x1() + 4;
            int iconY = b.y1() + 4;
            drawNative32CellScaled(graphics, CommanderGuiTextures.UNIT_ICONS,
                    iconX, iconY, view.unitType().spriteRow(), UNIT_ICON_SHEET_HEIGHT, BADGE_RENDER_SIZE);

            int chevronRow = RTSCommandFacade.chevronRow(view.averageLevel());
            if (chevronRow >= 0) {
                int chevronX = b.x2() - 4 - CHEVRON_RENDER_SIZE;
                int chevronY = b.y1() + 4;
                drawNative32CellScaled(graphics, CommanderGuiTextures.CHEVRONS,
                        chevronX, chevronY, chevronRow, 288, CHEVRON_RENDER_SIZE);
            }

            if (ClientCommanderState.isBodyguard(view.groupId())) {
                // Temporary crown remains only on the unit card. Floating banners receive no
                // special General trim/marker until the user's dedicated top-piece is supplied.
                drawStandalone32Scaled(graphics, CommanderGuiTextures.BODYGUARD_CROWN,
                        b.x1() + (CARD_WIDTH - 16) / 2, b.y1() - 12, 16);
            }
            if (view.moraleState() > 0) {
                int row = view.moraleState() >= 2 ? 0 : 1;
                // Temporary battle-state markers own the quiet bottom row of the card.
                // This keeps WAVERING/ROUTING away from the group name and tactical badge.
                int sx = b.x1() + 4;
                int sy = b.y2() - MORALE_STATUS_RENDER_SIZE - 3;
                drawStatusCell(graphics, sx, sy, row);
            }

            String name = view.name() == null || view.name().isBlank() ? "Unnamed" : view.name();
            drawCenteredTrimmed(graphics, name,
                    b.x1() + 3, b.y1() + 29, CARD_WIDTH - 6,
                    view.selected() ? 0xFFFFE8AC : TEXT);

            String count = Integer.toString(view.troopCount());
            int countX = b.x1() + (CARD_WIDTH - MC.font.width(count)) / 2;
            graphics.drawString(MC.font, count, countX, b.y1() + 44,
                    view.selected() ? GOLD : 0xFFC8C3B7, false);

            if (view.averageMorale() >= 0.0F) {
                renderMoraleBar(graphics, b.x1() + 5, b.y2() + 1,
                        CARD_WIDTH - 10, view.averageMorale());
            }
            if (view.averageFatigue() >= 0.0F) {
                renderFatigueBar(graphics, b.x1() + 5, b.y2() + 4,
                        CARD_WIDTH - 10, view.averageFatigue());
            }
        }

        if (layout.pageCount() > 1) {
            renderNavButton(graphics, layout.prevButton(), "<");
            renderNavButton(graphics, layout.nextButton(), ">");
            String pageText = (page + 1) + "/" + layout.pageCount();
            int px = layout.nextButton().x2() - MC.font.width(pageText);
            graphics.drawString(MC.font, pageText, px, layout.panelY() + 7, MUTED, false);
        }
    }

    private static void renderFloatingBanners(
            GuiGraphics graphics,
            List<RTSCommandFacade.UnitCardView> groups,
            Layout layout
    ) {
        if (enemyBannersVisible()) {
            for (RTSCommandFacade.EnemyBannerView view : RTSCommandFacade.enemyBanners()) {
                Bounds bounds = bannerBounds(view.centerX(), view.anchorY(), view.centerZ(), layout);
                if (bounds == null) continue;
                drawStaticBanner(graphics, bounds.x1(), bounds.y1(), view.unitType(),
                        view.teamColorIndex(), legacyTrimRow(view.teamColorIndex()));
            }
        }

        for (RTSCommandFacade.UnitCardView view : groups) {
            Bounds bounds = bannerBounds(view.centerX(), view.anchorY(), view.centerZ(), layout);
            if (bounds == null) continue;
            if (view.selected()) {
                drawBorder(graphics,
                        new Bounds(bounds.x1() - 1, bounds.y1() - 1, bounds.x2() + 1, bounds.y2() + 1),
                        0xFFE8D26A);
            }
            // General/Bodyguard uses exactly the same banner as the rest of its faction.
            // A dedicated top-piece will be added only when the user supplies that artwork.
            drawStaticBanner(graphics, bounds.x1(), bounds.y1(), view.unitType(),
                    view.teamColorIndex(), legacyTrimRow(view.teamColorIndex()));
        }
    }

    /**
     * Restore the older trim palette. The new cloth sheets use a different row order, so this
     * table is the old Minecraft-team-colour rule translated onto the new 16-row assets.
     * In particular both light red and dark red deliberately use GOLD trim.
     */
    private static int legacyTrimRow(int colorRow) {
        return switch (Math.max(0, Math.min(15, colorRow))) {
            case 0, 1, 2, 4, 5, 8, 9 -> 1;       // black
            case 3, 6, 10, 11, 12, 13 -> 2;     // gold
            case 7, 14, 15 -> 0;                 // silver
            default -> 0;
        };
    }

    private static ResourceLocation bannerColors(CommanderUnitType.BannerShape shape) {
        return switch (shape) {
            case POINTED -> CommanderGuiTextures.BANNER_POINTED_COLORS;
            case ROUNDED -> CommanderGuiTextures.BANNER_ROUNDED_COLORS;
            case SQUARE -> CommanderGuiTextures.BANNER_SQUARE_COLORS;
        };
    }

    private static ResourceLocation bannerTrims(CommanderUnitType.BannerShape shape) {
        return switch (shape) {
            case POINTED -> CommanderGuiTextures.BANNER_POINTED_TRIMS;
            case ROUNDED -> CommanderGuiTextures.BANNER_ROUNDED_TRIMS;
            case SQUARE -> CommanderGuiTextures.BANNER_SQUARE_TRIMS;
        };
    }

    /**
     * Static Minecraft-style 2D banner with the user's trim-matched 64x128 emblem layered
     * directly over the cloth/trim. The emblem source canvas is already aligned to the
     * supplied banners, so it is never cropped/repacked or destructively resized.
     */
    private static void drawStaticBanner(
            GuiGraphics graphics, int x, int y, CommanderUnitType unitType, int colorRow, int trimRow
    ) {
        CommanderUnitType.BannerShape shape = unitType.bannerShape();
        ResourceLocation colors = bannerColors(shape);
        ResourceLocation trims = bannerTrims(shape);
        int safeColor = Math.max(0, Math.min(15, colorRow));
        int safeTrim = Math.max(0, Math.min(2, trimRow));
        ResourceLocation emblem = switch (safeTrim) {
            case 0 -> CommanderGuiTextures.BANNER_ICONS_SILVER;
            case 2 -> CommanderGuiTextures.BANNER_ICONS_GOLD;
            default -> CommanderGuiTextures.BANNER_ICONS_GRAY;
        };
        int emblemRow = Math.max(0, Math.min(7, unitType.spriteRow()));
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().scale(0.5F, 0.5F, 1.0F);
        graphics.blit(colors, 0, 0, 0, safeColor * BANNER_SOURCE_HEIGHT,
                BANNER_SOURCE_WIDTH, BANNER_SOURCE_HEIGHT,
                BANNER_SOURCE_WIDTH, BANNER_SOURCE_HEIGHT * 16);
        graphics.blit(trims, 0, 0, 0, safeTrim * BANNER_SOURCE_HEIGHT,
                BANNER_SOURCE_WIDTH, BANNER_SOURCE_HEIGHT,
                BANNER_SOURCE_WIDTH, BANNER_SOURCE_HEIGHT * 3);
        graphics.blit(emblem, 0, 0, 0, emblemRow * BANNER_SOURCE_HEIGHT,
                BANNER_SOURCE_WIDTH, BANNER_SOURCE_HEIGHT,
                BANNER_SOURCE_WIDTH, BANNER_SOURCE_HEIGHT * 8);
        graphics.pose().popPose();
    }

    private static Bounds bannerBounds(double worldX, double worldY, double worldZ, Layout layout) {
        if (!Double.isFinite(worldX) || !Double.isFinite(worldY) || !Double.isFinite(worldZ)) return null;
        ScreenProjection.ProjectedPoint point = RTSCameraController.commanderProjectWorld(worldX, worldY, worldZ);
        if (point == null || point.depth() <= 0.0D || point.depth() > 180.0D) return null;
        int centerX = (int) Math.round(point.x() * virtualGuiWidth() / Math.max(1, MC.getWindow().getWidth()));
        int centerY = (int) Math.round(point.y() * virtualGuiHeight() / Math.max(1, MC.getWindow().getHeight()));
        int x = centerX - BANNER_RENDER_WIDTH / 2;
        int y = centerY - BANNER_RENDER_HEIGHT / 2;
        if (x + BANNER_RENDER_WIDTH < 0 || x >= virtualGuiWidth() || y + BANNER_RENDER_HEIGHT < 0) return null;
        if (y + BANNER_RENDER_HEIGHT >= layout.panelY()) return null;
        return new Bounds(x, y, x + BANNER_RENDER_WIDTH, y + BANNER_RENDER_HEIGHT);
    }

    private static List<BannerHitbox> bannerHitboxes(Layout layout) {
        List<BannerHitbox> result = new ArrayList<>();
        for (RTSCommandFacade.UnitCardView view : RTSCommandFacade.unitCards()) {
            Bounds bounds = bannerBounds(view.centerX(), view.anchorY(), view.centerZ(), layout);
            if (bounds != null) result.add(new BannerHitbox(bounds, false, view.groupId(), -1));
        }
        if (enemyBannersVisible()) {
            for (RTSCommandFacade.EnemyBannerView view : RTSCommandFacade.enemyBanners()) {
                Bounds bounds = bannerBounds(view.centerX(), view.anchorY(), view.centerZ(), layout);
                if (bounds != null) result.add(new BannerHitbox(bounds, true, view.groupId(), view.representativeEntityId()));
            }
        }
        return result;
    }

    private static boolean enemyBannersVisible() {
        return enemyBannersVisibleOverride != null
                ? enemyBannersVisibleOverride.booleanValue()
                : RTSClientConfig.ENEMY_BANNERS_DEFAULT.get();
    }

    private static void toggleEnemyBanners() {
        enemyBannersVisibleOverride = !enemyBannersVisible();
    }

    private static void renderFormationMenu(GuiGraphics graphics, Layout layout) {
        Bounds popup = formationPopupBounds(layout);
        graphics.fill(popup.x1(), popup.y1(), popup.x2(), popup.y2(), 0xE0181816);
        drawBorder(graphics, popup, 0xFFD7B84F);

        FormationKind active = RTSCommandFacade.commonSelectedFormationKind();
        int mouseX = guiMouseX();
        int mouseY = guiMouseY();
        FormationHitbox hovered = null;

        for (FormationHitbox hitbox : formationHitboxes(layout)) {
            Bounds b = hitbox.bounds();
            boolean isActive = active == hitbox.kind();
            boolean isHovered = b.contains(mouseX, mouseY);
            int fill = isActive ? 0xCC554817 : (isHovered ? 0xCC3A3934 : 0xAA24231F);
            graphics.fill(b.x1(), b.y1(), b.x2(), b.y2(), fill);
            drawBorder(graphics, b, isActive ? GOLD : 0xFF777166);

            int iconX = b.x1() + (b.width() - FORMATION_ICON_SIZE) / 2;
            int iconY = b.y1() + (b.height() - FORMATION_ICON_SIZE) / 2;
            drawFormationIcon(graphics, hitbox.kind(), iconX, iconY);
            if (isHovered) hovered = hitbox;
        }

        if (hovered != null) {
            renderFormationTooltip(graphics, hovered.kind(), popup);
        }
    }

    private static void drawFormationIcon(GuiGraphics graphics, FormationKind kind, int x, int y) {
        int row = Math.max(0, Math.min(FormationKind.values().length - 1, kind.ordinal()));
        graphics.blit(
                CommanderGuiTextures.FORMATION_ICONS,
                x, y,
                0, row * FORMATION_ICON_SIZE,
                FORMATION_ICON_SIZE, FORMATION_ICON_SIZE,
                FORMATION_ICON_SIZE, FORMATION_ICON_SIZE * FormationKind.values().length
        );
    }

    private static void renderFormationTooltip(GuiGraphics graphics, FormationKind kind, Bounds popup) {
        List<String> lines = new ArrayList<>();
        lines.add(kind.displayName().toUpperCase(java.util.Locale.ROOT));
        lines.addAll(RTSCommandFacade.formationTooltip(kind));

        int width = 0;
        for (String line : lines) width = Math.max(width, MC.font.width(line));
        width += 10;
        int height = 6 + lines.size() * 10;
        int x = Math.max(4, popup.x1() - width - 4);
        int y = Math.max(4, Math.min(guiMouseY() - 6, virtualGuiHeight() - height - 4));

        graphics.fill(x, y, x + width, y + height, 0xEE11110F);
        drawBorder(graphics, new Bounds(x, y, x + width, y + height), 0xFF8F825C);
        int ty = y + 4;
        for (int i = 0; i < lines.size(); i++) {
            int color = i == 0 ? GOLD : (lines.get(i).startsWith("+") ? 0xFF91D982
                    : lines.get(i).startsWith("-") ? 0xFFE07A70 : TEXT);
            graphics.drawString(MC.font, lines.get(i), x + 5, ty, color, false);
            ty += 10;
        }
    }

    private static Bounds formationPopupBounds(Layout layout) {
        int cols = 3;
        int rows = 3;
        int innerW = cols * FORMATION_BUTTON_SIZE + (cols - 1) * FORMATION_GRID_GAP;
        int innerH = rows * FORMATION_BUTTON_SIZE + (rows - 1) * FORMATION_GRID_GAP;
        int width = innerW + 8;
        int height = innerH + 8;
        int x2 = layout.formationButton().x2();
        int x1 = x2 - width;
        int y2 = layout.panelY() - 3;
        int y1 = y2 - height;
        return new Bounds(x1, y1, x2, y2);
    }

    private static List<FormationHitbox> formationHitboxes(Layout layout) {
        Bounds popup = formationPopupBounds(layout);
        List<FormationHitbox> result = new ArrayList<>();
        FormationKind[] values = FormationKind.values();
        for (int i = 0; i < values.length; i++) {
            int col = i % 3;
            int row = i / 3;
            int x = popup.x1() + 4 + col * (FORMATION_BUTTON_SIZE + FORMATION_GRID_GAP);
            int y = popup.y1() + 4 + row * (FORMATION_BUTTON_SIZE + FORMATION_GRID_GAP);
            result.add(new FormationHitbox(
                    new Bounds(x, y, x + FORMATION_BUTTON_SIZE, y + FORMATION_BUTTON_SIZE),
                    values[i]
            ));
        }
        return result;
    }

    private static void renderCommandPanel(GuiGraphics graphics, Layout layout) {
        boolean hasSelection = RTSCommandFacade.selectedGroupCount() > 0;
        int aggro = RTSCommandFacade.selectedAggroState();
        boolean aggressive = aggro == 2;
        renderItemButton(graphics, layout.tacticsButton(), net.minecraft.world.item.Items.WRITABLE_BOOK, tacticsMenuOpen);
        if (layout.tacticsButton().contains(guiMouseX(), guiMouseY()))
            renderCommandTooltip(graphics, "Tactics and abilities", "Skirmish, weapon modes, mounts, shields and battle abilities.");
        boolean shieldsUp = RTSCommandFacade.selectedShieldsUp();
        boolean slowMarch = RTSCommandFacade.selectedSlowMarch();
        boolean loose = RTSCommandFacade.selectedLooseSpacing();
        boolean ranged = RTSCommandFacade.selectedSupportsRangedControl();
        boolean fireAtWill = ranged && RTSCommandFacade.selectedFireAtWill();
        boolean useMissiles = RTSCommandFacade.selectedUseMissiles();
        boolean canBodyguard = RTSCommandFacade.canToggleBodyguard();
        boolean bodyguard = RTSCommandFacade.selectedIsBodyguard();
        boolean canFollowBodyguard = RTSCommandFacade.canToggleBodyguardFollow();
        boolean followBodyguard = RTSCommandFacade.bodyguardFollowsPlayer();
        boolean enemyBanners = enemyBannersVisible();

        renderIconCommandButton(graphics, layout.formationButton(),
                CommanderGuiTextures.COMMAND_FORMATION, hasSelection, formationMenuOpen);
        renderIconCommandButton(graphics, layout.haltButton(),
                CommanderGuiTextures.COMMAND_HALT, hasSelection, false);
        renderIconCommandButton(graphics, layout.aggressionButton(),
                aggressive ? CommanderGuiTextures.COMMAND_STANCE_AGGRESSIVE
                        : CommanderGuiTextures.COMMAND_STANCE_NEUTRAL,
                hasSelection, aggressive);
        renderSpriteCommandButton(graphics, layout.shieldsButton(),
                CommanderGuiTextures.COMMAND_SHIELD_STATES,
                shieldsUp ? 1 : 0, 64, hasSelection, shieldsUp);
        renderIconCommandButton(graphics, layout.slowMarchButton(),
                CommanderGuiTextures.COMMAND_SLOW_MARCH, hasSelection, slowMarch);
        renderIconCommandButton(graphics, layout.looseSpacingButton(),
                CommanderGuiTextures.COMMAND_LOOSE_SPACING, hasSelection, loose);
        renderIconCommandButton(graphics, layout.fireControlButton(),
                fireAtWill ? CommanderGuiTextures.COMMAND_FIRE_AT_WILL
                        : CommanderGuiTextures.COMMAND_HOLD_FIRE,
                hasSelection && ranged, fireAtWill);
        renderIconCommandButton(graphics, layout.missileButton(),
                CommanderGuiTextures.COMMAND_USE_MISSILES, hasSelection, useMissiles);
        renderIconCommandButton(graphics, layout.bodyguardButton(),
                CommanderGuiTextures.COMMAND_BODYGUARD, canBodyguard, bodyguard);
        renderIconCommandButton(graphics, layout.bodyguardFollowButton(),
                CommanderGuiTextures.COMMAND_BODYGUARD_FOLLOW, canFollowBodyguard, followBodyguard);
        renderIconCommandButton(graphics, layout.enemyBannersButton(),
                CommanderGuiTextures.COMMAND_ENEMY_BANNERS, true, enemyBanners);

        if (RTSCommandFacade.commandCursor()) {
            int mx = guiMouseX();
            int my = guiMouseY();
            if (layout.formationButton().contains(mx, my)) {
                renderCommandTooltip(graphics, "Formations",
                        formationMenuOpen ? "Close the preset formation drop-up."
                                : "Choose a preset formation.");
            } else if (layout.haltButton().contains(mx, my)) {
                renderCommandTooltip(graphics, "Halt", "Stop the selected groups immediately.");
            } else if (layout.aggressionButton().contains(mx, my)) {
                renderCommandTooltip(graphics, aggressive ? "Aggressive" : "Neutral",
                        aggressive ? "Actively seek and engage enemies."
                                : "Defend and respond without hunting targets.");
            } else if (layout.shieldsButton().contains(mx, my)) {
                renderCommandTooltip(graphics, shieldsUp ? "Shields Up" : "Shields Down",
                        shieldsUp ? "Lower shields on shield-capable troops."
                                : "Raise shields on shield-capable troops.");
            } else if (layout.slowMarchButton().contains(mx, my)) {
                renderCommandTooltip(graphics, slowMarch ? "Slow March: ON" : "Slow March",
                        slowMarch ? "Normal RMB moves use an orderly reduced pace. Click to disable."
                                : "Use an orderly reduced pace for normal RMB moves.");
            } else if (layout.looseSpacingButton().contains(mx, my)) {
                renderCommandTooltip(graphics, loose ? "Loose Spacing: ON" : "Loose Spacing",
                        loose ? "Return selected preset formations to normal spacing."
                                : "Spread selected preset formations to maximum supported spacing.");
            } else if (layout.fireControlButton().contains(mx, my)) {
                renderCommandTooltip(graphics, fireAtWill ? "Fire At Will" : "Hold Fire",
                        !ranged ? "Select only ranged or artillery groups to use this control."
                                : (fireAtWill ? "Click to order the selected ranged units to hold fire."
                                : "Click to let the selected ranged units fire at will."));
            } else if (layout.missileButton().contains(mx, my)) {
                renderCommandTooltip(graphics, useMissiles ? "Use Missiles: ON" : "Hold Missiles",
                        useMissiles ? "Tagged spears/javelins may be thrown on attack orders. Click to preserve them."
                                : "Allow selected groups to throw tagged melee missiles on attack orders.");
            } else if (layout.bodyguardButton().contains(mx, my)) {
                renderCommandTooltip(graphics, bodyguard ? "General / Bodyguard" : "Designate Bodyguard",
                        canBodyguard ? (bodyguard ? "Remove the selected group as your General's Bodyguard."
                                : "Place your real player in this formation as its commander slot while RTS view stays detached.")
                                : "Select exactly one owned group.");
            } else if (layout.bodyguardFollowButton().contains(mx, my)) {
                renderCommandTooltip(graphics, followBodyguard ? "Bodyguard Follows: ON" : "Bodyguard Follows",
                        canFollowBodyguard ? (followBodyguard
                                ? "Outside RTS view, the Bodyguard reforms around you. Click to stop following."
                                : "Keep the Bodyguard with your real player while RTS view is closed.")
                                : "Assign a General's Bodyguard first.");
            } else if (layout.enemyBannersButton().contains(mx, my)) {
                renderCommandTooltip(graphics, enemyBanners ? "Enemy Banners: ON" : "Enemy Banners: OFF",
                        enemyBanners
                                ? "Hide hostile banners. Right-click a hostile banner to attack its group."
                                : "Show hostile banners for targeting and battlefield awareness.");
            }
        }
    }

    private static void renderIconCommandButton(
            GuiGraphics graphics,
            Bounds bounds,
            ResourceLocation icon,
            boolean enabled,
            boolean selected
    ) {
        boolean hovered = enabled && bounds.contains(guiMouseX(), guiMouseY()) && RTSCommandFacade.commandCursor();
        int fill = !enabled ? 0xAA181816
                : selected ? 0xCC554817
                : hovered ? 0xCC3A3934
                : 0xB824231F;
        int border = !enabled ? 0xFF4D4A43 : selected ? GOLD : (hovered ? 0xFF9B927F : 0xFF6F6544);
        graphics.fill(bounds.x1(), bounds.y1(), bounds.x2(), bounds.y2(), fill);
        drawBorder(graphics, bounds, border);

        int x = bounds.x1() + (bounds.width() - COMMAND_ICON_SIZE) / 2;
        int y = bounds.y1() + (bounds.height() - COMMAND_ICON_SIZE) / 2;
        graphics.blit(icon, x, y, 0, 0, COMMAND_ICON_SIZE, COMMAND_ICON_SIZE,
                COMMAND_ICON_SIZE, COMMAND_ICON_SIZE);
    }

    private static void renderSpriteCommandButton(
            GuiGraphics graphics,
            Bounds bounds,
            ResourceLocation texture,
            int row,
            int textureHeight,
            boolean enabled,
            boolean selected
    ) {
        boolean hovered = enabled && bounds.contains(guiMouseX(), guiMouseY()) && RTSCommandFacade.commandCursor();
        int fill = !enabled ? 0xAA181816
                : selected ? 0xCC554817
                : hovered ? 0xCC3A3934
                : 0xB824231F;
        int border = !enabled ? 0xFF4D4A43 : selected ? GOLD : (hovered ? 0xFF9B927F : 0xFF6F6544);
        graphics.fill(bounds.x1(), bounds.y1(), bounds.x2(), bounds.y2(), fill);
        drawBorder(graphics, bounds, border);
        int x = bounds.x1() + (bounds.width() - COMMAND_ICON_SIZE) / 2;
        int y = bounds.y1() + (bounds.height() - COMMAND_ICON_SIZE) / 2;
        drawNative32CellScaled(graphics, texture, x, y, row, textureHeight, COMMAND_ICON_SIZE);
    }

    private static void renderCommandTooltip(GuiGraphics graphics, String title, String description) {
        int width = Math.max(MC.font.width(title), MC.font.width(description)) + 10;
        int height = 26;
        int x = Math.max(4, Math.min(guiMouseX() + 8,
                virtualGuiWidth() - width - 4));
        int y = Math.max(4, guiMouseY() - height - 8);
        graphics.fill(x, y, x + width, y + height, 0xEE11110F);
        drawBorder(graphics, new Bounds(x, y, x + width, y + height), 0xFF8F825C);
        graphics.drawString(MC.font, title, x + 5, y + 4, GOLD, false);
        graphics.drawString(MC.font, description, x + 5, y + 14, TEXT, false);
    }

    private static void renderSelectedLabel(GuiGraphics graphics, Layout layout) {
        String label = RTSCommandFacade.selectedGroupLabel();
        if (label == null || label.isBlank()) return;
        int x = layout.panelRight() - 8 - MC.font.width(label);
        int y = Math.max(3, layout.panelY() - 10);
        graphics.drawString(MC.font, label, x, y, GOLD, true);
    }

    private static void drawStatusCell(GuiGraphics graphics, int x, int y, int row) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        float scale = MORALE_STATUS_RENDER_SIZE / 16.0F;
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.blit(CommanderGuiTextures.MORALE_STATUS, 0, 0, 0, row * 16, 16, 16, 16, 32);
        graphics.pose().popPose();
    }

    private static void renderFatigueBar(GuiGraphics graphics, int x, int y, int width, float fatigue) {
        float clamped = Math.max(0.0F, Math.min(100.0F, fatigue));
        graphics.fill(x, y, x + width, y + CARD_FATIGUE_HEIGHT, 0xFF1B1A17);
        int fill = Math.round(width * clamped / 100.0F);
        if (fill > 0) graphics.fill(x, y, x + fill, y + CARD_FATIGUE_HEIGHT, 0xFFE4B74A);
    }

    private static void renderMoraleBar(GuiGraphics graphics, int x, int y, int width, float morale) {
        float clamped = Math.max(0.0F, Math.min(100.0F, morale));
        blitScaled(graphics, CommanderGuiTextures.MORALE_BAR_BACKGROUND,
                x, y, width, CARD_MORALE_HEIGHT, 36, 3);
        int filled = Math.round(width * (clamped / 100.0F));
        if (filled > 0) {
            blitScaled(graphics, CommanderGuiTextures.MORALE_BAR_FILL,
                    x, y, filled, CARD_MORALE_HEIGHT, 36, 3);
        }
    }

    /** Draw one authored 32x32 sprite-sheet cell exactly 1:1. */
    private static void drawNative32Cell(
            GuiGraphics graphics,
            ResourceLocation texture,
            int x,
            int y,
            int row,
            int textureHeight
    ) {
        graphics.blit(texture, x, y, 0, row * CELL, CELL, CELL, CELL, textureHeight);
    }

    private static void drawNative32CellScaled(
            GuiGraphics graphics, ResourceLocation texture, int x, int y, int row, int textureHeight, int renderSize
    ) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        float scale = renderSize / (float) CELL;
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.blit(texture, 0, 0, 0, row * CELL, CELL, CELL, CELL, textureHeight);
        graphics.pose().popPose();
    }

    private static void drawStandalone32Scaled(
            GuiGraphics graphics,
            ResourceLocation texture,
            int x,
            int y,
            int renderSize
    ) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        float scale = renderSize / (float) CELL;
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.blit(texture, 0, 0, 0, 0, CELL, CELL, CELL, CELL);
        graphics.pose().popPose();
    }

    private static void blitScaled(
            GuiGraphics graphics,
            ResourceLocation texture,
            int x,
            int y,
            int destWidth,
            int destHeight,
            int sourceWidth,
            int sourceHeight
    ) {
        if (destWidth <= 0 || destHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
            return;
        }
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().scale(destWidth / (float) sourceWidth, destHeight / (float) sourceHeight, 1.0F);
        graphics.blit(texture, 0, 0, 0, 0, sourceWidth, sourceHeight, sourceWidth, sourceHeight);
        graphics.pose().popPose();
    }

    private static void renderNavButton(GuiGraphics graphics, Bounds bounds, String text) {
        boolean hovered = bounds.contains(guiMouseX(), guiMouseY()) && RTSCommandFacade.commandCursor();
        ResourceLocation texture = hovered
                ? CommanderGuiTextures.NAV_BUTTON_HOVER
                : CommanderGuiTextures.NAV_BUTTON_IDLE;
        blitScaled(graphics, texture, bounds.x1(), bounds.y1(), bounds.width(), bounds.height(), 18, 25);
        int x = bounds.x1() + (bounds.width() - MC.font.width(text)) / 2;
        int y = bounds.y1() + (bounds.height() - 8) / 2;
        graphics.drawString(MC.font, text, x, y, TEXT, false);
    }

    private static List<CardHitbox> cardHitboxes(
            List<RTSCommandFacade.UnitCardView> groups,
            Layout layout
    ) {
        if (groups.isEmpty() || layout.cardsPerPage() <= 0) {
            return List.of();
        }
        int start = page * layout.cardsPerPage();
        int end = Math.min(groups.size(), start + layout.cardsPerPage());
        List<CardHitbox> result = new ArrayList<>(Math.max(0, end - start));
        for (int i = start; i < end; i++) {
            int local = i - start;
            int x = layout.cardsX() + local * (CARD_WIDTH + CARD_GAP);
            int y = layout.panelY() + 3;
            result.add(new CardHitbox(
                    new Bounds(x, y, x + CARD_WIDTH, y + CARD_HEIGHT),
                    groups.get(i)
            ));
        }
        return result;
    }

    private static Layout currentLayout() {
        int count = RTSCommandFacade.unitCards().size();
        Layout built = buildLayout(count, false);
        lastLayout = built;
        page = Math.max(0, Math.min(page, Math.max(0, built.pageCount() - 1)));
        return built;
    }

    private static Layout buildLayout(int groupCount, boolean chatMode) {
        int width = virtualGuiWidth();
        int height = virtualGuiHeight();
        int panelX = MARGIN;
        int panelRight = Math.max(panelX + 360, width - MARGIN);
        int chatLift = 0;
        int panelY = Math.max(MARGIN, height - PANEL_HEIGHT - MARGIN - chatLift);
        int panelBottom = height - MARGIN - chatLift;

        int panelWidth = panelRight - panelX;
        int cardsX = panelX + 8;
        int cardsRight = panelRight - 8;
        int cardsArea = Math.max(CARD_WIDTH, cardsRight - cardsX);

        int withoutNav = Math.max(1, cardsArea / (CARD_WIDTH + CARD_GAP));
        boolean needsPaging = groupCount > withoutNav;
        int navSpace = needsPaging ? NAV_WIDTH * 2 + CARD_GAP * 2 + 2 : 0;
        int cardsAvailable = Math.max(CARD_WIDTH, cardsArea - navSpace);
        int cardsPerPage = Math.max(1, cardsAvailable / (CARD_WIDTH + CARD_GAP));
        int pageCount = Math.max(1, (groupCount + cardsPerPage - 1) / cardsPerPage);

        int navBaseX = cardsRight - (NAV_WIDTH * 2 + CARD_GAP);
        int navY1 = panelY + 34;
        int navY2 = navY1 + 25;
        Bounds prev = new Bounds(navBaseX, navY1, navBaseX + NAV_WIDTH, navY2);
        Bounds next = new Bounds(navBaseX + NAV_WIDTH + CARD_GAP, navY1,
                navBaseX + NAV_WIDTH * 2 + CARD_GAP, navY2);

        int commandCount = 12;
        int totalButtons = COMMAND_BUTTON_SIZE * commandCount + COMMAND_BUTTON_GAP * (commandCount - 1);
        int buttonX = panelX + Math.max(8, (panelWidth - totalButtons) / 2);
        int buttonY1 = panelBottom - COMMAND_BAR_HEIGHT + 2;
        int buttonY2 = buttonY1 + COMMAND_BUTTON_SIZE;

        Bounds formation = new Bounds(buttonX, buttonY1, buttonX + COMMAND_BUTTON_SIZE, buttonY2);
        buttonX += COMMAND_BUTTON_SIZE + COMMAND_BUTTON_GAP;
        Bounds halt = new Bounds(buttonX, buttonY1, buttonX + COMMAND_BUTTON_SIZE, buttonY2);
        buttonX += COMMAND_BUTTON_SIZE + COMMAND_BUTTON_GAP;
        Bounds aggression = new Bounds(buttonX, buttonY1, buttonX + COMMAND_BUTTON_SIZE, buttonY2);
        buttonX += COMMAND_BUTTON_SIZE + COMMAND_BUTTON_GAP;
        Bounds shields = new Bounds(buttonX, buttonY1, buttonX + COMMAND_BUTTON_SIZE, buttonY2);
        buttonX += COMMAND_BUTTON_SIZE + COMMAND_BUTTON_GAP;
        Bounds slowMarch = new Bounds(buttonX, buttonY1, buttonX + COMMAND_BUTTON_SIZE, buttonY2);
        buttonX += COMMAND_BUTTON_SIZE + COMMAND_BUTTON_GAP;
        Bounds looseSpacing = new Bounds(buttonX, buttonY1, buttonX + COMMAND_BUTTON_SIZE, buttonY2);
        buttonX += COMMAND_BUTTON_SIZE + COMMAND_BUTTON_GAP;
        Bounds fireControl = new Bounds(buttonX, buttonY1, buttonX + COMMAND_BUTTON_SIZE, buttonY2);
        buttonX += COMMAND_BUTTON_SIZE + COMMAND_BUTTON_GAP;
        Bounds missile = new Bounds(buttonX, buttonY1, buttonX + COMMAND_BUTTON_SIZE, buttonY2);
        buttonX += COMMAND_BUTTON_SIZE + COMMAND_BUTTON_GAP;
        Bounds bodyguard = new Bounds(buttonX, buttonY1, buttonX + COMMAND_BUTTON_SIZE, buttonY2);
        buttonX += COMMAND_BUTTON_SIZE + COMMAND_BUTTON_GAP;
        Bounds bodyguardFollow = new Bounds(buttonX, buttonY1, buttonX + COMMAND_BUTTON_SIZE, buttonY2);
        buttonX += COMMAND_BUTTON_SIZE + COMMAND_BUTTON_GAP;
        Bounds enemyBanners = new Bounds(buttonX, buttonY1, buttonX + COMMAND_BUTTON_SIZE, buttonY2);

        buttonX += COMMAND_BUTTON_SIZE + COMMAND_BUTTON_GAP;
        Bounds tactics = new Bounds(buttonX, buttonY1, buttonX + COMMAND_BUTTON_SIZE, buttonY2);

        return new Layout(
                panelX, panelY, panelRight, panelBottom,
                0, 0,
                cardsX, cardsRight - navSpace,
                cardsPerPage, pageCount,
                prev, next, formation, halt, aggression, shields,
                slowMarch, looseSpacing, fireControl, missile, bodyguard, bodyguardFollow, enemyBanners, tactics
        );
    }


    private record TacticEntry(Bounds bounds, String label, String detail,
            ee.lastpagans.recruitsrtscamera.network.TacticsControlPacket.Action action) {}

    private static Bounds tacticsBounds(Layout layout) {
        int width = Math.min(350, virtualGuiWidth() - 8);
        int x = Math.max(4, (virtualGuiWidth() - width) / 2);
        int y = Math.max(4, layout.panelY() - 204);
        return new Bounds(x, y, x + width, y + 200);
    }

    private static List<TacticEntry> tacticEntries(Layout layout) {
        var actions = ee.lastpagans.recruitsrtscamera.network.TacticsControlPacket.Action.values();
        String[] labels = {"Skirmish ON", "Skirmish OFF", "Ranged weapons", "Melee weapons", "Native weapon choice",
                "Mount", "Dismount", "Shields FRONT", "Shields UP", "Shields AUTO", "Native shields",
                "Second Wind", "Hold the Line", "War Cry"};
        String[] details = {
                "Keep firing distance; withdraw when enemies close.", "Fight in place using normal combat movement.",
                "Prefer ranged weapons. Hold Fire still applies.", "Close for melee; equip an available backup weapon.",
                "Return weapon choice to native Recruits AI.", "Return to a remembered or nearby owned horse.",
                "Dismount and stay on foot until ordered to mount.", "Face melee threats and cover between attacks.",
                "Brace shields; also intercept overhead arrows and javelins.", "Nearby incoming missiles alert the whole formation.",
                "Return shield decisions to native Recruits AI.", "Recover 25 fatigue. Cooldown: 60 seconds.",
                "Halve morale shocks for 15 seconds. Cooldown: 60 seconds.", "Frighten visible enemies within 18 blocks. Cooldown: 90 seconds."};
        Bounds box = tacticsBounds(layout);
        int columnWidth = (box.width() - 12) / 2;
        List<TacticEntry> result = new ArrayList<>();
        for (int i = 0; i < actions.length; i++) {
            int x = box.x1() + 4 + (i % 2) * (columnWidth + 4);
            int y = box.y1() + 32 + (i / 2) * 19;
            result.add(new TacticEntry(new Bounds(x, y, x + columnWidth, y + 17), labels[i], details[i], actions[i]));
        }
        return result;
    }

    private static void renderTacticsMenu(GuiGraphics graphics, Layout layout) {
        Bounds box = tacticsBounds(layout);
        graphics.fill(box.x1(), box.y1(), box.x2(), box.y2(), 0xF51B1A17);
        drawBorder(graphics, box, GOLD);
        graphics.drawString(MC.font, "TACTICS & ABILITIES", box.x1() + 6, box.y1() + 5, GOLD, false);
        graphics.drawString(MC.font, MC.font.plainSubstrByWidth(ClientTactics.selectionLabel(), box.width() - 12),
                box.x1() + 6, box.y1() + 18, 0xFFD6CCB6, false);
        String detail = "Orders apply to the selected groups.";
        for (TacticEntry entry : tacticEntries(layout)) {
            boolean hovered = entry.bounds.contains(guiMouseX(), guiMouseY());
            graphics.fill(entry.bounds.x1(), entry.bounds.y1(), entry.bounds.x2(), entry.bounds.y2(),
                    hovered ? 0xFF54492F : 0xFF302D25);
            graphics.drawString(MC.font, entry.label, entry.bounds.x1() + 4, entry.bounds.y1() + 4, 0xFFE6DECE, false);
            if (hovered) detail = entry.detail;
        }
        int wrap = box.width() - 12;
        var lines = MC.font.split(net.minecraft.network.chat.Component.literal(detail), wrap);
        for (int i = 0; i < Math.min(2, lines.size()); i++)
            graphics.drawString(MC.font, lines.get(i), box.x1() + 6, box.y2() - 26 + i * 10, 0xFFBBAF99, false);
    }

    private static void renderItemButton(GuiGraphics graphics, Bounds bounds, net.minecraft.world.item.Item item, boolean active) {
        graphics.fill(bounds.x1(), bounds.y1(), bounds.x2(), bounds.y2(), active ? 0xCC554817 : 0xB824231F);
        drawBorder(graphics, bounds, active ? GOLD : 0xFF6F6544);
        graphics.renderItem(new net.minecraft.world.item.ItemStack(item), bounds.x1() + 1, bounds.y1() + 1);
    }

    private static Bounds commandBarBounds(Layout layout) {
        return new Bounds(
                layout.panelX() + 4,
                layout.panelBottom() - COMMAND_BAR_HEIGHT,
                layout.panelRight() - 4,
                layout.panelBottom()
        );
    }

    /** Screen-GUI pixels reserved at the bottom while Commander mode is active. */
    public static int reservedChatPixels() {
        return Math.max(24, (int) Math.ceil((PANEL_HEIGHT + MARGIN + 4) * commanderScale()));
    }

    private static int guiMouseX() {
        double normal = MC.mouseHandler.xpos() * MC.getWindow().getGuiScaledWidth()
                / Math.max(1.0D, MC.getWindow().getWidth());
        return (int) Math.floor(normal / commanderScale());
    }

    private static int guiMouseY() {
        double normal = MC.mouseHandler.ypos() * MC.getWindow().getGuiScaledHeight()
                / Math.max(1.0D, MC.getWindow().getHeight());
        return (int) Math.floor(normal / commanderScale());
    }

    private static float commanderScale() {
        double guiScale = MC.getWindow().getGuiScale();
        return guiScale > 2.0D ? (float) (2.0D / guiScale) : 1.0F;
    }

    private static int virtualGuiWidth() {
        return Math.max(1, (int) Math.floor(MC.getWindow().getGuiScaledWidth() / commanderScale()));
    }

    private static int virtualGuiHeight() {
        return Math.max(1, (int) Math.floor(MC.getWindow().getGuiScaledHeight() / commanderScale()));
    }

    private static String oneDecimal(float value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private static void drawTrimmed(GuiGraphics graphics, String text, int x, int y, int maxWidth, int color) {
        if (text == null || maxWidth <= 0) {
            return;
        }
        String shown = MC.font.width(text) <= maxWidth
                ? text
                : MC.font.plainSubstrByWidth(text, Math.max(0, maxWidth - MC.font.width("..."))) + "...";
        graphics.drawString(MC.font, shown, x, y, color, false);
    }

    private static void drawCenteredTrimmed(GuiGraphics graphics, String text, int x, int y, int maxWidth, int color) {
        if (text == null || maxWidth <= 0) {
            return;
        }
        String shown = MC.font.width(text) <= maxWidth
                ? text
                : MC.font.plainSubstrByWidth(text, Math.max(0, maxWidth - MC.font.width("..."))) + "...";
        int tx = x + (maxWidth - MC.font.width(shown)) / 2;
        graphics.drawString(MC.font, shown, tx, y, color, false);
    }

    private static void drawBorder(GuiGraphics graphics, Bounds b, int color) {
        graphics.fill(b.x1(), b.y1(), b.x2(), b.y1() + 1, color);
        graphics.fill(b.x1(), b.y2() - 1, b.x2(), b.y2(), color);
        graphics.fill(b.x1(), b.y1(), b.x1() + 1, b.y2(), color);
        graphics.fill(b.x2() - 1, b.y1(), b.x2(), b.y2(), color);
    }

    private record CardHitbox(Bounds bounds, RTSCommandFacade.UnitCardView view) {
    }

    private record FormationHitbox(Bounds bounds, FormationKind kind) {
    }

    private record BannerHitbox(Bounds bounds, boolean enemy, java.util.UUID groupId, int representativeEntityId) {
    }

    private record Bounds(int x1, int y1, int x2, int y2) {
        boolean contains(int x, int y) {
            return x >= x1 && x < x2 && y >= y1 && y < y2;
        }

        int width() {
            return x2 - x1;
        }

        int height() {
            return y2 - y1;
        }
    }

    private record Layout(
            int panelX,
            int panelY,
            int panelRight,
            int panelBottom,
            int infoWidth,
            int commandWidth,
            int cardsX,
            int cardsRight,
            int cardsPerPage,
            int pageCount,
            Bounds prevButton,
            Bounds nextButton,
            Bounds formationButton,
            Bounds haltButton,
            Bounds aggressionButton,
            Bounds shieldsButton,
            Bounds slowMarchButton,
            Bounds looseSpacingButton,
            Bounds fireControlButton,
            Bounds missileButton,
            Bounds bodyguardButton,
            Bounds bodyguardFollowButton,
            Bounds enemyBannersButton,
            Bounds tacticsButton
    ) {
        private static final Layout EMPTY = new Layout(0, 0, 0, 0, 0, 0, 0, 0, 1, 1,
                new Bounds(0, 0, 0, 0), new Bounds(0, 0, 0, 0),
                new Bounds(0, 0, 0, 0), new Bounds(0, 0, 0, 0),
                new Bounds(0, 0, 0, 0), new Bounds(0, 0, 0, 0),
                new Bounds(0, 0, 0, 0), new Bounds(0, 0, 0, 0),
                new Bounds(0, 0, 0, 0), new Bounds(0, 0, 0, 0),
                new Bounds(0, 0, 0, 0), new Bounds(0, 0, 0, 0),
                new Bounds(0, 0, 0, 0), new Bounds(0, 0, 0, 0));

        boolean contains(int x, int y) {
            return x >= panelX && x < panelRight && y >= panelY && y < panelBottom;
        }
    }
}
