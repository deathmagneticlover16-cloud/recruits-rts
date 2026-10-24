package ee.lastpagans.recruitsrtscamera.client;

import ee.lastpagans.recruitsrtscamera.formation.FormationKind;
import ee.lastpagans.recruitsrtscamera.formation.ArmyPreset;
import ee.lastpagans.recruitsrtscamera.config.RTSClientConfig;
import ee.lastpagans.recruitsrtscamera.advisor.AdvisorClient;
import ee.lastpagans.recruitsrtscamera.math.ScreenProjection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
    private static boolean selectionMenuOpen;
    private static boolean settingsMenuOpen;
    private static Layout lastLayout = Layout.EMPTY;
    private static Boolean enemyBannersVisibleOverride;
    private static UUID cardSelectionAnchor;

    private CommanderOverlay() {
    }

    public static void reset() {
        page = 0;
        formationMenuOpen = false;
        selectionMenuOpen = false;
        settingsMenuOpen = false;
        lastLayout = Layout.EMPTY;
        enemyBannersVisibleOverride = null;
        cardSelectionAnchor = null;
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
        CommanderUiRegistry.beginFrame();
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

            renderShell(graphics, layout);
            renderUnitCards(graphics, groups, layout);
            renderSelectedLabel(graphics, layout);
            renderCommandPanel(graphics, layout);
            if (formationMenuOpen) renderFormationMenu(graphics, layout);
            updateSelectionMenuHover(layout);
            if (selectionMenuOpen) renderSelectionMenu(graphics, layout);
            renderSettingsButton(graphics);
            if (settingsMenuOpen) renderSettingsMenu(graphics);
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
        boolean overSelectionPopup = selectionMenuOpen && selectionPopupBounds(layout).contains(mouseX, mouseY);
        boolean overSettings = settingsButtonBounds().contains(mouseX, mouseY);
        boolean overSettingsPopup = settingsMenuOpen && settingsPopupBounds().contains(mouseX, mouseY);

        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && action == GLFW.GLFW_PRESS && overSettings) {
            settingsMenuOpen = !settingsMenuOpen;
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && action == GLFW.GLFW_PRESS && overSettingsPopup) {
            List<Bounds> rows = settingsChoiceBounds();
            if (rows.get(0).contains(mouseX, mouseY)) toggleEnemyBanners();
            else if (rows.get(1).contains(mouseX, mouseY)) AdvisorClient.toggleEnabled();
            return true;
        }

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

        if (!layout.contains(mouseX, mouseY) && !overPopup && !overSelectionPopup && !overSettings && !overSettingsPopup) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && action == GLFW.GLFW_PRESS && formationMenuOpen) {
                formationMenuOpen = false;
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && action == GLFW.GLFW_PRESS) settingsMenuOpen = false;
            return false;
        }

        // Any mouse input over the fixed Commander UI belongs to the UI, never the battlefield.
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || action != GLFW.GLFW_PRESS) {
            return true;
        }

        if (overPopup) {
            for (ArmyPresetHitbox hitbox : formationHitboxes(layout)) {
                if (hitbox.bounds().contains(mouseX, mouseY)) {
                    RTSCommandFacade.applyArmyPreset(hitbox.preset());
                    formationMenuOpen = false;
                    return true;
                }
            }
            return true;
        }

        if (overSelectionPopup) {
            List<Bounds> choices = selectionChoiceBounds(layout);
            if (choices.get(0).contains(mouseX, mouseY)) {
                RTSCommandFacade.makeSelectionGroup();
                selectionMenuOpen = false;
            } else if (choices.get(1).contains(mouseX, mouseY)
                    && RTSCommandFacade.selectedGroupCount() == 1) {
                RTSCommandFacade.beginGroupHotkeyAssignment();
                selectionMenuOpen = false;
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

        if (layout.formationButton().contains(mouseX, mouseY)) {
            formationMenuOpen = !formationMenuOpen;
            settingsMenuOpen = false;
            return true;
        }
        if (layout.selectionGroupButton().contains(mouseX, mouseY)) return true;
        if (layout.paceButton().contains(mouseX, mouseY)) {
            RTSCommandFacade.toggleMovementPace();
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
        if (RTSCommandFacade.selectedHasCavalryMajority() && layout.mountButton().contains(mouseX, mouseY)) {
            RTSCommandFacade.toggleMount();
            return true;
        }
        if (layout.looseSpacingButton().contains(mouseX, mouseY)) {
            RTSCommandFacade.toggleLooseSpacing();
            return true;
        }
        if (RTSCommandFacade.selectedHasRangedWeapons() && layout.fireControlButton().contains(mouseX, mouseY)) {
            RTSCommandFacade.toggleFireControl();
            return true;
        }
        if (RTSCommandFacade.selectedHasRangedMajority() && layout.skirmishButton().contains(mouseX, mouseY)) {
            RTSCommandFacade.toggleSkirmish();
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
        if (RTSCommandFacade.selectedHasCavalryMajority()
                && layout.cavalryWedgeButton().contains(mouseX, mouseY)) {
            FormationKind current = RTSCommandFacade.commonSelectedFormationKind();
            RTSCommandFacade.setFormation(current == FormationKind.CAVALRY_WEDGE
                    ? FormationKind.SQUARE
                    : FormationKind.CAVALRY_WEDGE);
            return true;
        }
        List<RTSCommandFacade.UnitCardView> groups = RTSCommandFacade.unitCards();
        for (CardHitbox hitbox : cardHitboxes(groups, layout)) {
            if (hitbox.bounds().contains(mouseX, mouseY)) {
                boolean additive = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
                boolean range = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
                UUID clicked = hitbox.view().groupId();
                if (range && cardSelectionAnchor != null
                        && RTSCommandFacade.selectCardRange(cardSelectionAnchor, clicked, additive)) {
                    return true;
                }
                RTSCommandFacade.selectUnit(clicked, additive);
                // Ctrl toggles and Shift ranges preserve the last plain-click anchor.
                if (!additive && !range) cardSelectionAnchor = clicked;
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
        if (!layout.contains(mouseX, mouseY)
                && !(formationMenuOpen && formationPopupBounds(layout).contains(mouseX, mouseY))
                && !(selectionMenuOpen && selectionPopupBounds(layout).contains(mouseX, mouseY))
                && !settingsButtonBounds().contains(mouseX, mouseY)
                && !(settingsMenuOpen && settingsPopupBounds().contains(mouseX, mouseY))) {
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
        if (layout.contains(x, y) || (formationMenuOpen && formationPopupBounds(layout).contains(x, y))
                || (selectionMenuOpen && selectionPopupBounds(layout).contains(x, y))
                || settingsButtonBounds().contains(x, y)
                || (settingsMenuOpen && settingsPopupBounds().contains(x, y))) return true;
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
        CommanderUiRegistry.record(CommanderUiElementIds.MAIN_PANEL,
                layout.panelX(), layout.panelY(), panelWidth, panelHeight);
        blitScaled(graphics, CommanderGuiTextures.MAIN_COMMAND_BAR,
                layout.panelX(), layout.panelY(), panelWidth, panelHeight, 512, 110);

        // 0.9.8: the Commander UI no longer burns a permanent left information column
        // or a right orders column. Unit cards own the full upper strip, while one long
        // Total War-style command bar spans underneath them.
        Bounds commandBar = commandBarBounds(layout);
        recordUi(CommanderUiElementIds.MAIN_COMMAND_BAR, commandBar);
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
        CommanderUiRegistry.record(CommanderUiElementIds.MAIN_UNIT_CARDS,
                layout.cardsX(), layout.panelY(), layout.cardsRight() - layout.cardsX(), CARD_HEIGHT + 6);
        int mouseX = guiMouseX();
        int mouseY = guiMouseY();

        for (CardHitbox hitbox : hitboxes) {
            Bounds b = hitbox.bounds();
            RTSCommandFacade.UnitCardView view = hitbox.view();
            CommanderUiRegistry.record(CommanderUiElementIds.UNIT_CARD, view.groupId(),
                    b.x1(), b.y1(), b.width(), b.height(), Float.NaN);
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
                CommanderUiRegistry.record(CommanderUiElementIds.STAT_MORALE, view.groupId(),
                        b.x1() + 5, b.y2() + 1, CARD_WIDTH - 10, CARD_MORALE_HEIGHT, view.averageMorale());
                renderMoraleBar(graphics, b.x1() + 5, b.y2() + 1,
                        CARD_WIDTH - 10, view.averageMorale());
            }
            if (view.averageFatigue() >= 0.0F) {
                CommanderUiRegistry.record(CommanderUiElementIds.STAT_FATIGUE, view.groupId(),
                        b.x1() + 5, b.y2() + 4, CARD_WIDTH - 10, CARD_FATIGUE_HEIGHT, view.averageFatigue());
                renderFatigueBar(graphics, b.x1() + 5, b.y2() + 4,
                        CARD_WIDTH - 10, view.averageFatigue());
            }
        }

        // Draw the shared frame last so selected-card and hover borders cannot erase its
        // continuous outer edge. The frame and tab occur once for the whole visible bundle.
        renderSelectionBundleFrames(graphics, hitboxes);

        if (layout.pageCount() > 1) {
            renderNavButton(graphics, layout.prevButton(), "<");
            renderNavButton(graphics, layout.nextButton(), ">");
            String pageText = (page + 1) + "/" + layout.pageCount();
            int px = layout.nextButton().x2() - MC.font.width(pageText);
            graphics.drawString(MC.font, pageText, px, layout.panelY() + 7, MUTED, false);
        }
    }

    private static void renderSelectionBundleFrames(GuiGraphics graphics, List<CardHitbox> hitboxes) {
        java.util.Map<Integer, List<CardHitbox>> bundles = new java.util.LinkedHashMap<>();
        for (CardHitbox hitbox : hitboxes) {
            int slot = RTSCommandFacade.selectionGroupSlot(hitbox.view().groupId());
            if (slot > 0) bundles.computeIfAbsent(slot, ignored -> new ArrayList<>()).add(hitbox);
        }
        for (java.util.Map.Entry<Integer, List<CardHitbox>> entry : bundles.entrySet()) {
            int slot = entry.getKey();
            List<CardHitbox> cards = entry.getValue();
            int left = cards.stream().mapToInt(card -> card.bounds().x1()).min().orElse(0);
            int top = cards.stream().mapToInt(card -> card.bounds().y1()).min().orElse(0);
            int right = cards.stream().mapToInt(card -> card.bounds().x2()).max().orElse(0);
            int bottom = cards.stream().mapToInt(card -> card.bounds().y2()).max().orElse(0);
            int color = RTSCommandFacade.selectionGroupColor(cards.get(0).view().groupId());
            drawBorder(graphics, new Bounds(left - 3, top - 3, right + 3, bottom + 3), color);
            drawBorder(graphics, new Bounds(left - 2, top - 2, right + 2, bottom + 2), color);
            int tabX = left - 3;
            int tabY = top - 13;
            graphics.fill(tabX, tabY, tabX + 13, top - 2, color);
            drawBorder(graphics, new Bounds(tabX, tabY, tabX + 13, top - 2), 0xFF171714);
            String label = RTSCommandFacade.selectionGroupLabel(slot);
            graphics.drawString(MC.font, label, tabX + (13 - MC.font.width(label)) / 2, tabY + 1,
                    0xFFFFFFFF, false);
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
        ScreenProjection.ProjectedPoint point = RTSCameraController.commanderProjectWorld(
                worldX, worldY + 6.4D, worldZ);
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

    /** World renderer shares the same user-facing enemy-marker setting as banner hit testing. */
    public static boolean worldEnemyBannersVisible() {
        return enemyBannersVisible();
    }

    private static void toggleEnemyBanners() {
        enemyBannersVisibleOverride = !enemyBannersVisible();
    }

    private static void updateSelectionMenuHover(Layout layout) {
        int x = guiMouseX();
        int y = guiMouseY();
        selectionMenuOpen = layout.selectionGroupButton().contains(x, y)
                || selectionMenuOpen && selectionPopupBounds(layout).contains(x, y);
    }

    private static Bounds selectionPopupBounds(Layout layout) {
        int width = 45;
        int height = 24;
        int x1 = layout.selectionGroupButton().x1();
        int y2 = layout.selectionGroupButton().y1();
        return new Bounds(x1, y2 - height, x1 + width, y2);
    }

    private static List<Bounds> selectionChoiceBounds(Layout layout) {
        Bounds popup = selectionPopupBounds(layout);
        return List.of(
                new Bounds(popup.x1() + 3, popup.y1() + 3, popup.x1() + 21, popup.y2() - 3),
                new Bounds(popup.x1() + 24, popup.y1() + 3, popup.x2() - 3, popup.y2() - 3)
        );
    }

    private static void renderSelectionMenu(GuiGraphics graphics, Layout layout) {
        Bounds popup = selectionPopupBounds(layout);
        graphics.fill(popup.x1(), popup.y1(), popup.x2(), popup.y2(), 0xEE181816);
        drawBorder(graphics, popup, GOLD);
        List<Bounds> choices = selectionChoiceBounds(layout);
        for (int i = 0; i < choices.size(); i++) {
            Bounds row = choices.get(i);
            boolean enabled = i == 0 ? RTSCommandFacade.selectedGroupCount() > 0
                    : RTSCommandFacade.selectedGroupCount() == 1;
            boolean hovered = enabled && row.contains(guiMouseX(), guiMouseY());
            graphics.fill(row.x1(), row.y1(), row.x2(), row.y2(), hovered ? 0xCC3A3934 : 0xAA24231F);
            drawBorder(graphics, row, enabled ? (hovered ? GOLD : 0xFF777166) : 0xFF4D4A43);
            graphics.renderItem(new net.minecraft.world.item.ItemStack(i == 0
                    ? net.minecraft.world.item.Items.BUNDLE : net.minecraft.world.item.Items.TRIPWIRE_HOOK),
                    row.x1() + 1, row.y1() + 1);
        }
        if (choices.get(0).contains(guiMouseX(), guiMouseY())) {
            renderHotkeyTooltip(graphics, RTSCameraKeyMappings.SELECTION_GROUP,
                    "Bundles the selected groups into the next numbered slot");
        } else if (choices.get(1).contains(guiMouseX(), guiMouseY())) {
            renderCommandTooltip(graphics, "Add Hotkey",
                    RTSCommandFacade.selectedGroupCount() == 1
                            ? "Press an unused key to assign it; Backspace removes its hotkey."
                            : "Select exactly one group first.");
        }
    }

    private static Bounds settingsButtonBounds() {
        int right = virtualGuiWidth() - 5;
        return new Bounds(right - 18, 5, right, 23);
    }

    private static Bounds settingsPopupBounds() {
        Bounds button = settingsButtonBounds();
        return new Bounds(button.x2() - 112, button.y2() + 2, button.x2(), button.y2() + 44);
    }

    private static List<Bounds> settingsChoiceBounds() {
        Bounds popup = settingsPopupBounds();
        return List.of(
                new Bounds(popup.x1() + 2, popup.y1() + 2, popup.x2() - 2, popup.y1() + 20),
                new Bounds(popup.x1() + 2, popup.y1() + 21, popup.x2() - 2, popup.y2() - 2)
        );
    }

    private static void renderSettingsButton(GuiGraphics graphics) {
        Bounds button = settingsButtonBounds();
        recordUi(CommanderUiElementIds.SETTINGS_BUTTON, button);
        boolean hovered = button.contains(guiMouseX(), guiMouseY());
        graphics.fill(button.x1(), button.y1(), button.x2(), button.y2(), hovered ? 0xDD3A3934 : 0xCC181816);
        drawBorder(graphics, button, settingsMenuOpen ? GOLD : 0xFF8F825C);
        graphics.drawCenteredString(MC.font, "⚙", (button.x1() + button.x2()) / 2, button.y1() + 5, TEXT);
    }

    private static void renderSettingsMenu(GuiGraphics graphics) {
        Bounds popup = settingsPopupBounds();
        recordUi(CommanderUiElementIds.SETTINGS_PANEL, popup);
        graphics.fill(popup.x1(), popup.y1(), popup.x2(), popup.y2(), 0xEE181816);
        drawBorder(graphics, popup, 0xFF8F825C);
        List<Bounds> rows = settingsChoiceBounds();
        for (Bounds row : rows) if (row.contains(guiMouseX(), guiMouseY()))
            graphics.fill(row.x1(), row.y1(), row.x2(), row.y2(), 0xCC34332F);
        String label = "Enemy Banners: " + (enemyBannersVisible() ? "ON" : "OFF");
        graphics.drawString(MC.font, label, rows.get(0).x1() + 3, rows.get(0).y1() + 5,
                enemyBannersVisible() ? GOLD : MUTED, false);
        String advisor = "Advisor: " + (AdvisorClient.enabled() ? "ON" : "OFF");
        graphics.drawString(MC.font, advisor, rows.get(1).x1() + 3, rows.get(1).y1() + 5,
                AdvisorClient.enabled() ? GOLD : MUTED, false);
    }

    private static void renderFormationMenu(GuiGraphics graphics, Layout layout) {
        Bounds popup = formationPopupBounds(layout);
        graphics.fill(popup.x1(), popup.y1(), popup.x2(), popup.y2(), 0xE0181816);
        drawBorder(graphics, popup, 0xFFD7B84F);

        int mouseX = guiMouseX();
        int mouseY = guiMouseY();
        ArmyPresetHitbox hovered = null;

        for (ArmyPresetHitbox hitbox : formationHitboxes(layout)) {
            Bounds b = hitbox.bounds();
            boolean isHovered = b.contains(mouseX, mouseY);
            int fill = isHovered ? 0xCC3A3934 : 0xAA24231F;
            graphics.fill(b.x1(), b.y1(), b.x2(), b.y2(), fill);
            drawBorder(graphics, b, isHovered ? GOLD : 0xFF777166);

            int iconX = b.x1() + (b.width() - FORMATION_ICON_SIZE) / 2;
            int iconY = b.y1() + (b.height() - FORMATION_ICON_SIZE) / 2;
            drawArmyPresetIcon(graphics, hitbox.preset(), iconX, iconY);
            if (isHovered) hovered = hitbox;
        }

        if (hovered != null) {
            renderArmyPresetTooltip(graphics, hovered.preset(), popup);
        }
    }

    private static void drawArmyPresetIcon(GuiGraphics graphics, ArmyPreset preset, int x, int y) {
        FormationKind[] icons = {FormationKind.LINE, FormationKind.LINE,
                FormationKind.TRIANGLE, FormationKind.VEE, FormationKind.HOLLOW_SQUARE};
        drawFormationIcon(graphics, icons[Math.min(preset.ordinal(), icons.length - 1)], x, y);
    }

    private static void drawFormationIcon(GuiGraphics graphics, FormationKind kind, int x, int y) {
        // The authored sheet has the original nine rows. Cavalry wedge deliberately reuses
        // the triangle artwork while remaining a separate command and runtime formation.
        int row = kind == FormationKind.CAVALRY_WEDGE ? FormationKind.TRIANGLE.ordinal()
                : Math.max(0, Math.min(8, kind.ordinal()));
        graphics.blit(
                CommanderGuiTextures.FORMATION_ICONS,
                x, y,
                0, row * FORMATION_ICON_SIZE,
                FORMATION_ICON_SIZE, FORMATION_ICON_SIZE,
                FORMATION_ICON_SIZE, FORMATION_ICON_SIZE * 9
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

    private static void renderArmyPresetTooltip(GuiGraphics graphics, ArmyPreset preset, Bounds popup) {
        List<String> lines = List.of(preset.displayName().toUpperCase(java.util.Locale.ROOT), preset.description());
        int width = Math.max(MC.font.width(lines.get(0)), MC.font.width(lines.get(1))) + 10;
        int height = 26;
        int x = Math.max(4, popup.x1() - width - 4);
        int y = Math.max(4, Math.min(guiMouseY() - 6, virtualGuiHeight() - height - 4));
        graphics.fill(x, y, x + width, y + height, 0xEE11110F);
        drawBorder(graphics, new Bounds(x, y, x + width, y + height), 0xFF8F825C);
        graphics.drawString(MC.font, lines.get(0), x + 5, y + 4, GOLD, false);
        graphics.drawString(MC.font, lines.get(1), x + 5, y + 14, TEXT, false);
    }

    private static Bounds formationPopupBounds(Layout layout) {
        int cols = 3;
        int rows = (ArmyPreset.values().length + cols - 1) / cols;
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

    private static List<ArmyPresetHitbox> formationHitboxes(Layout layout) {
        Bounds popup = formationPopupBounds(layout);
        List<ArmyPresetHitbox> result = new ArrayList<>();
        ArmyPreset[] values = ArmyPreset.values();
        for (int i = 0; i < values.length; i++) {
            int col = i % 3;
            int row = i / 3;
            int x = popup.x1() + 4 + col * (FORMATION_BUTTON_SIZE + FORMATION_GRID_GAP);
            int y = popup.y1() + 4 + row * (FORMATION_BUTTON_SIZE + FORMATION_GRID_GAP);
            result.add(new ArmyPresetHitbox(
                    new Bounds(x, y, x + FORMATION_BUTTON_SIZE, y + FORMATION_BUTTON_SIZE),
                    values[i]
            ));
        }
        return result;
    }

    private static void renderCommandPanel(GuiGraphics graphics, Layout layout) {
        boolean hasSelection = RTSCommandFacade.selectedGroupCount() > 0;
        boolean aggressive = RTSCommandFacade.selectedAggroState() == 2;
        boolean running = RTSCommandFacade.selectedRunning();
        boolean loose = RTSCommandFacade.selectedLooseSpacing();
        boolean ranged = RTSCommandFacade.selectedHasRangedWeapons();
        boolean rangedMajority = RTSCommandFacade.selectedHasRangedMajority();
        boolean cavalryMajority = RTSCommandFacade.selectedHasCavalryMajority();
        boolean mounted = cavalryMajority && RTSCommandFacade.selectedCavalryMounted();
        boolean fireAtWill = ranged && RTSCommandFacade.selectedFireAtWill();
        boolean skirmishing = rangedMajority && RTSCommandFacade.selectedSkirmishing();
        boolean canBodyguard = RTSCommandFacade.canToggleBodyguard();
        boolean bodyguard = RTSCommandFacade.selectedIsBodyguard();
        boolean canFollowBodyguard = RTSCommandFacade.canToggleBodyguardFollow();
        boolean followBodyguard = RTSCommandFacade.bodyguardFollowsPlayer();

        recordUi(CommanderUiElementIds.COMMAND_PACE, layout.paceButton());
        recordUi(CommanderUiElementIds.COMMAND_FORMATION, layout.formationButton());
        recordUi(CommanderUiElementIds.COMMAND_SELECTION_GROUP, layout.selectionGroupButton());
        recordUi(CommanderUiElementIds.COMMAND_HALT, layout.haltButton());
        recordUi(CommanderUiElementIds.COMMAND_AGGRESSION, layout.aggressionButton());
        if (cavalryMajority) recordUi(CommanderUiElementIds.COMMAND_MOUNT, layout.mountButton());
        recordUi(CommanderUiElementIds.COMMAND_SPACING, layout.looseSpacingButton());
        if (ranged) recordUi(CommanderUiElementIds.COMMAND_FIRE_CONTROL, layout.fireControlButton());
        if (rangedMajority) recordUi(CommanderUiElementIds.COMMAND_SKIRMISH, layout.skirmishButton());
        recordUi(CommanderUiElementIds.COMMAND_BODYGUARD, layout.bodyguardButton());
        recordUi(CommanderUiElementIds.COMMAND_BODYGUARD_FOLLOW, layout.bodyguardFollowButton());
        if (cavalryMajority) recordUi(CommanderUiElementIds.COMMAND_CAVALRY_WEDGE, layout.cavalryWedgeButton());

        renderTextCommandButton(graphics, layout.paceButton(), running ? "<<" : "<", hasSelection, running);
        renderIconCommandButton(graphics, layout.formationButton(),
                CommanderGuiTextures.COMMAND_FORMATION, hasSelection, formationMenuOpen);
        renderItemButton(graphics, layout.selectionGroupButton(), net.minecraft.world.item.Items.NAME_TAG, false);
        renderIconCommandButton(graphics, layout.haltButton(),
                CommanderGuiTextures.COMMAND_HALT, hasSelection, false);
        renderIconCommandButton(graphics, layout.aggressionButton(),
                aggressive ? CommanderGuiTextures.COMMAND_STANCE_AGGRESSIVE
                        : CommanderGuiTextures.COMMAND_STANCE_NEUTRAL,
                hasSelection, aggressive);
        if (cavalryMajority) renderItemButton(graphics, layout.mountButton(),
                net.minecraft.world.item.Items.SADDLE, mounted);
        renderIconCommandButton(graphics, layout.looseSpacingButton(),
                CommanderGuiTextures.COMMAND_LOOSE_SPACING, hasSelection, loose);
        if (ranged) renderIconCommandButton(graphics, layout.fireControlButton(),
                fireAtWill ? CommanderGuiTextures.COMMAND_FIRE_AT_WILL
                        : CommanderGuiTextures.COMMAND_HOLD_FIRE, true, fireAtWill);
        if (rangedMajority) renderItemButton(graphics, layout.skirmishButton(),
                net.minecraft.world.item.Items.BOW, skirmishing);
        renderIconCommandButton(graphics, layout.bodyguardButton(),
                CommanderGuiTextures.COMMAND_BODYGUARD, canBodyguard, bodyguard);
        renderIconCommandButton(graphics, layout.bodyguardFollowButton(),
                CommanderGuiTextures.COMMAND_BODYGUARD_FOLLOW, canFollowBodyguard, followBodyguard);
        if (cavalryMajority) renderFormationCommandButton(graphics, layout.cavalryWedgeButton(),
                FormationKind.CAVALRY_WEDGE,
                RTSCommandFacade.commonSelectedFormationKind() == FormationKind.CAVALRY_WEDGE);

        if (!RTSCommandFacade.commandCursor()) return;
        int mx = guiMouseX();
        int my = guiMouseY();
        if (layout.paceButton().contains(mx, my)) {
            renderHotkeyTooltip(graphics, RTSCameraKeyMappings.MOVEMENT_PACE,
                    "Toggles the selected group(s) between walk and run");
        } else if (layout.formationButton().contains(mx, my)) {
            renderHotkeyTooltip(graphics, RTSCameraKeyMappings.ARMY_PRESET,
                    "Opens or applies Army Presets");
        } else if (layout.selectionGroupButton().contains(mx, my)) {
            renderHotkeyTooltip(graphics, RTSCameraKeyMappings.SELECTION_GROUP,
                    "Creates a numbered Selection Group");
        } else if (layout.haltButton().contains(mx, my)) {
            renderHotkeyTooltip(graphics, RTSCameraKeyMappings.HALT, "Halts the selected group(s)");
        } else if (layout.aggressionButton().contains(mx, my)) {
            renderCommandTooltip(graphics, aggressive ? "Aggressive" : "Neutral",
                    aggressive ? "Actively seek and engage enemies." : "Defend and respond without hunting targets.");
        } else if (cavalryMajority && layout.mountButton().contains(mx, my)) {
            renderCommandTooltip(graphics, mounted ? "Dismount" : "Mount",
                    mounted ? "Dismount the selected cavalry-majority groups."
                            : "Mount the selected cavalry-majority groups.");
        } else if (layout.looseSpacingButton().contains(mx, my)) {
            renderCommandTooltip(graphics, loose ? "Loose Spacing: ON" : "Loose Spacing",
                    "Toggle wider spacing for preset formations.");
        } else if (ranged && layout.fireControlButton().contains(mx, my)) {
            renderHotkeyTooltip(graphics,
                    fireAtWill ? RTSCameraKeyMappings.HOLD_FIRE : RTSCameraKeyMappings.FIRE_AT_WILL,
                    fireAtWill ? "Orders weapon-bearing groups to hold fire"
                            : "Allows weapon-bearing groups to fire at will");
        } else if (rangedMajority && layout.skirmishButton().contains(mx, my)) {
            renderCommandTooltip(graphics, skirmishing ? "Skirmish: ON" : "Skirmish",
                    skirmishing ? "Order ranged-majority groups to stand and fight."
                            : "Order ranged-majority groups to keep firing distance.");
        } else if (layout.bodyguardButton().contains(mx, my)) {
            renderCommandTooltip(graphics, bodyguard ? "General / Bodyguard" : "Designate Bodyguard",
                    canBodyguard ? "Toggle the selected group as the General's Bodyguard."
                            : "Select exactly one owned group.");
        } else if (layout.bodyguardFollowButton().contains(mx, my)) {
            renderCommandTooltip(graphics, followBodyguard ? "Bodyguard Follows: ON" : "Bodyguard Follows",
                    canFollowBodyguard ? "Toggle whether the Bodyguard follows your real player."
                            : "Assign a General's Bodyguard first.");
        } else if (cavalryMajority && layout.cavalryWedgeButton().contains(mx, my)) {
            renderCommandTooltip(graphics, "Cavalry Wedge",
                    "A cavalry-only wedge that builds speed while it keeps moving.");
        }
    }

    private static void renderFormationCommandButton(
            GuiGraphics graphics, Bounds bounds, FormationKind kind, boolean active
    ) {
        graphics.fill(bounds.x1(), bounds.y1(), bounds.x2(), bounds.y2(), active ? 0xCC554817 : 0xB824231F);
        drawBorder(graphics, bounds, active ? GOLD : 0xFF6F6544);
        drawFormationIcon(graphics, kind, bounds.x1() + 1, bounds.y1() + 1);
    }

    private static void renderTextCommandButton(
            GuiGraphics graphics, Bounds bounds, String text, boolean enabled, boolean selected
    ) {
        boolean hovered = enabled && bounds.contains(guiMouseX(), guiMouseY());
        int fill = !enabled ? 0xAA181816 : selected ? 0xCC554817 : hovered ? 0xCC3A3934 : 0xB824231F;
        int border = !enabled ? 0xFF4D4A43 : selected ? GOLD : hovered ? 0xFF9B927F : 0xFF6F6544;
        graphics.fill(bounds.x1(), bounds.y1(), bounds.x2(), bounds.y2(), fill);
        drawBorder(graphics, bounds, border);
        graphics.drawCenteredString(MC.font, text, (bounds.x1() + bounds.x2()) / 2, bounds.y1() + 5,
                enabled ? TEXT : MUTED);
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

    private static void renderHotkeyTooltip(
            GuiGraphics graphics, net.minecraft.client.KeyMapping mapping, String description
    ) {
        String line = "<" + hotkey(mapping) + "> " + description;
        int width = MC.font.width(line) + 10;
        int height = 16;
        int x = Math.max(4, Math.min(guiMouseX() + 8, virtualGuiWidth() - width - 4));
        int y = Math.max(4, guiMouseY() - height - 8);
        graphics.fill(x, y, x + width, y + height, 0xEE11110F);
        drawBorder(graphics, new Bounds(x, y, x + width, y + height), 0xFF8F825C);
        graphics.drawString(MC.font, line, x + 5, y + 4, TEXT, false);
    }

    private static String hotkey(net.minecraft.client.KeyMapping mapping) {
        return mapping == null ? "?" : mapping.getTranslatedKeyMessage().getString();
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

        boolean showMount = RTSCommandFacade.selectedHasCavalryMajority();
        boolean showFire = RTSCommandFacade.selectedHasRangedWeapons();
        boolean showSkirmish = RTSCommandFacade.selectedHasRangedMajority();
        int commandCount = 8 + (showMount ? 2 : 0) + (showFire ? 1 : 0) + (showSkirmish ? 1 : 0);
        int totalButtons = COMMAND_BUTTON_SIZE * commandCount + COMMAND_BUTTON_GAP * (commandCount - 1);
        int buttonX = panelX + Math.max(8, (panelWidth - totalButtons) / 2);
        int buttonY1 = panelBottom - COMMAND_BAR_HEIGHT + 2;
        int buttonY2 = buttonY1 + COMMAND_BUTTON_SIZE;

        Bounds pace = new Bounds(buttonX, buttonY1, buttonX + COMMAND_BUTTON_SIZE, buttonY2);
        buttonX += COMMAND_BUTTON_SIZE + COMMAND_BUTTON_GAP;
        Bounds formation = new Bounds(buttonX, buttonY1, buttonX + COMMAND_BUTTON_SIZE, buttonY2);
        buttonX += COMMAND_BUTTON_SIZE + COMMAND_BUTTON_GAP;
        Bounds selectionGroup = new Bounds(buttonX, buttonY1, buttonX + COMMAND_BUTTON_SIZE, buttonY2);
        buttonX += COMMAND_BUTTON_SIZE + COMMAND_BUTTON_GAP;
        Bounds halt = new Bounds(buttonX, buttonY1, buttonX + COMMAND_BUTTON_SIZE, buttonY2);
        buttonX += COMMAND_BUTTON_SIZE + COMMAND_BUTTON_GAP;
        Bounds aggression = new Bounds(buttonX, buttonY1, buttonX + COMMAND_BUTTON_SIZE, buttonY2);
        buttonX += COMMAND_BUTTON_SIZE + COMMAND_BUTTON_GAP;
        Bounds mount = new Bounds(0, 0, 0, 0);
        if (showMount) {
            mount = new Bounds(buttonX, buttonY1, buttonX + COMMAND_BUTTON_SIZE, buttonY2);
            buttonX += COMMAND_BUTTON_SIZE + COMMAND_BUTTON_GAP;
        }
        Bounds looseSpacing = new Bounds(buttonX, buttonY1, buttonX + COMMAND_BUTTON_SIZE, buttonY2);
        buttonX += COMMAND_BUTTON_SIZE + COMMAND_BUTTON_GAP;
        Bounds fireControl = new Bounds(0, 0, 0, 0);
        if (showFire) {
            fireControl = new Bounds(buttonX, buttonY1, buttonX + COMMAND_BUTTON_SIZE, buttonY2);
            buttonX += COMMAND_BUTTON_SIZE + COMMAND_BUTTON_GAP;
        }
        Bounds skirmish = new Bounds(0, 0, 0, 0);
        if (showSkirmish) {
            skirmish = new Bounds(buttonX, buttonY1, buttonX + COMMAND_BUTTON_SIZE, buttonY2);
            buttonX += COMMAND_BUTTON_SIZE + COMMAND_BUTTON_GAP;
        }
        Bounds bodyguard = new Bounds(buttonX, buttonY1, buttonX + COMMAND_BUTTON_SIZE, buttonY2);
        buttonX += COMMAND_BUTTON_SIZE + COMMAND_BUTTON_GAP;
        Bounds bodyguardFollow = new Bounds(buttonX, buttonY1, buttonX + COMMAND_BUTTON_SIZE, buttonY2);
        buttonX += COMMAND_BUTTON_SIZE + COMMAND_BUTTON_GAP;
        Bounds cavalryWedge = showMount
                ? new Bounds(buttonX, buttonY1, buttonX + COMMAND_BUTTON_SIZE, buttonY2)
                : new Bounds(0, 0, 0, 0);

        return new Layout(
                panelX, panelY, panelRight, panelBottom,
                0, 0,
                cardsX, cardsRight - navSpace,
                cardsPerPage, pageCount,
                prev, next, pace, formation, selectionGroup, halt, aggression, mount,
                looseSpacing, fireControl, skirmish, bodyguard, bodyguardFollow, cavalryWedge
        );
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

    private static void recordUi(String id, Bounds bounds) {
        CommanderUiRegistry.record(id, bounds.x1(), bounds.y1(), bounds.width(), bounds.height());
    }

    private record CardHitbox(Bounds bounds, RTSCommandFacade.UnitCardView view) {
    }

    private record ArmyPresetHitbox(Bounds bounds, ArmyPreset preset) {
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
            Bounds paceButton,
            Bounds formationButton,
            Bounds selectionGroupButton,
            Bounds haltButton,
            Bounds aggressionButton,
            Bounds mountButton,
            Bounds looseSpacingButton,
            Bounds fireControlButton,
            Bounds skirmishButton,
            Bounds bodyguardButton,
            Bounds bodyguardFollowButton,
            Bounds cavalryWedgeButton
    ) {
        private static final Layout EMPTY = new Layout(0, 0, 0, 0, 0, 0, 0, 0, 1, 1,
                new Bounds(0, 0, 0, 0), new Bounds(0, 0, 0, 0),
                new Bounds(0, 0, 0, 0), new Bounds(0, 0, 0, 0),
                new Bounds(0, 0, 0, 0), new Bounds(0, 0, 0, 0),
                new Bounds(0, 0, 0, 0), new Bounds(0, 0, 0, 0),
                new Bounds(0, 0, 0, 0), new Bounds(0, 0, 0, 0),
                new Bounds(0, 0, 0, 0),
                new Bounds(0, 0, 0, 0), new Bounds(0, 0, 0, 0),
                new Bounds(0, 0, 0, 0));

        boolean contains(int x, int y) {
            return x >= panelX && x < panelRight && y >= panelY && y < panelBottom;
        }
    }
}
