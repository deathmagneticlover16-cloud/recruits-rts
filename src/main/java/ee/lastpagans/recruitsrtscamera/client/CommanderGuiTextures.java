package ee.lastpagans.recruitsrtscamera.client;

import net.minecraft.resources.ResourceLocation;

/**
 * Central texture registry for the private-server Commander UI skin.
 *
 * <p>Every current visual surface is intentionally routed through a named texture so the
 * server UI can be reskinned without rewriting CommanderOverlay. Runtime resources remain individually replaceable under assets/recruits_rts_camera/textures/gui/commander.</p>
 */
public final class CommanderGuiTextures {
    private static final String MOD_ID = "recruits_rts_camera";
    private static final String ROOT = "textures/gui/commander/skin/";

    public static final ResourceLocation MAIN_COMMAND_BAR = skin("main_command_bar.png");
    public static final ResourceLocation INFO_PANEL = skin("info_panel.png");
    public static final ResourceLocation ORDERS_PANEL = skin("orders_panel.png");
    public static final ResourceLocation DIVIDER_VERTICAL = skin("divider_vertical.png");

    public static final ResourceLocation CARD_BACKGROUND = skin("card_background.png");
    public static final ResourceLocation CARD_SELECTED = skin("card_selected.png");
    public static final ResourceLocation CARD_HOVERED = skin("card_hovered.png");
    public static final ResourceLocation CARD_DISABLED = skin("card_disabled.png");

    public static final ResourceLocation BUTTON_IDLE = skin("button_idle.png");
    public static final ResourceLocation BUTTON_HOVER = skin("button_hover.png");
    public static final ResourceLocation BUTTON_PRESSED = skin("button_pressed.png");
    public static final ResourceLocation BUTTON_DISABLED = skin("button_disabled.png");
    public static final ResourceLocation BUTTON_SELECTED = skin("button_selected.png");

    public static final ResourceLocation NAV_BUTTON_IDLE = skin("nav_button_idle.png");
    public static final ResourceLocation NAV_BUTTON_HOVER = skin("nav_button_hover.png");

    public static final ResourceLocation MORALE_BAR_BACKGROUND = skin("morale_bar_background.png");
    public static final ResourceLocation MORALE_BAR_FILL = skin("morale_bar_fill.png");
    public static final ResourceLocation FATIGUE_BAR_BACKGROUND = skin("fatigue_bar_background.png");
    public static final ResourceLocation FATIGUE_BAR_FILL = skin("fatigue_bar_fill.png");

    // Supplied server tactical sprites. Kept separate from the skin so symbols can be replaced independently.
    public static final ResourceLocation UNIT_ICONS = commander("unit_icons.png");
    public static final ResourceLocation CHEVRONS = commander("chevrons.png");
    public static final ResourceLocation BODYGUARD_CROWN = commander("bodyguard_crown.png");
    public static final ResourceLocation MORALE_STATUS = commander("routing_wavering.png");
    public static final ResourceLocation FORMATION_ICONS = commander("formation_icons.png");
    public static final ResourceLocation BANNER_POINTED_COLORS = commander("banners/pointed_colors.png");
    public static final ResourceLocation BANNER_POINTED_TRIMS = commander("banners/pointed_trims.png");
    public static final ResourceLocation BANNER_ROUNDED_COLORS = commander("banners/rounded_colors.png");
    public static final ResourceLocation BANNER_ROUNDED_TRIMS = commander("banners/rounded_trims.png");
    public static final ResourceLocation BANNER_SQUARE_COLORS = commander("banners/square_colors.png");
    public static final ResourceLocation BANNER_SQUARE_TRIMS = commander("banners/square_trims.png");
    public static final ResourceLocation BANNER_ICONS_SILVER = commander("banners/unit_icons_silver.png");
    public static final ResourceLocation BANNER_ICONS_GRAY = commander("banners/unit_icons_gray.png");
    public static final ResourceLocation BANNER_ICONS_GOLD = commander("banners/unit_icons_gold.png");
    public static final ResourceLocation ADVISOR_LATIN = commander("advisor/latin.png");

    // Temporary Commander action icons. These are deliberately individual PNGs so the
    // server art pass can replace them one-by-one without touching layout code.
    public static final ResourceLocation COMMAND_FORMATION = commander("commands/formation.png");
    public static final ResourceLocation COMMAND_HALT = commander("commands/halt.png");
    public static final ResourceLocation COMMAND_STANCE_NEUTRAL = commander("commands/stance_neutral.png");
    public static final ResourceLocation COMMAND_STANCE_AGGRESSIVE = commander("commands/stance_aggressive.png");
    public static final ResourceLocation COMMAND_SLOW_MARCH = commander("commands/slow_march.png");
    public static final ResourceLocation COMMAND_LOOSE_SPACING = commander("commands/loose_spacing.png");
    public static final ResourceLocation COMMAND_FIRE_AT_WILL = commander("commands/fire_at_will.png");
    public static final ResourceLocation COMMAND_HOLD_FIRE = commander("commands/hold_fire.png");
    public static final ResourceLocation COMMAND_USE_MISSILES = commander("commands/use_missiles.png");
    public static final ResourceLocation COMMAND_BODYGUARD = commander("commands/bodyguard.png");
    public static final ResourceLocation COMMAND_BODYGUARD_FOLLOW = commander("commands/bodyguard_follow.png");
    public static final ResourceLocation COMMAND_ENEMY_BANNERS = commander("commands/enemy_banners.png");

    private CommanderGuiTextures() {
    }

    private static ResourceLocation skin(String name) {
        return new ResourceLocation(MOD_ID, ROOT + name);
    }

    private static ResourceLocation commander(String name) {
        return new ResourceLocation(MOD_ID, "textures/gui/commander/" + name);
    }
}
