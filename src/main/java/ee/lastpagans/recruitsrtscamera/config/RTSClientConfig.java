package ee.lastpagans.recruitsrtscamera.config;

import net.minecraftforge.common.ForgeConfigSpec;

/** Client-only visual preferences. These never change server-side Fog of War authority. */
public final class RTSClientConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue DEBUG_COMMAND_MENU;
    public static final ForgeConfigSpec.BooleanValue ADVISOR_VOICES_ENABLED;
    public static final ForgeConfigSpec.BooleanValue ADVISOR_SUBTITLES;
    public static final ForgeConfigSpec.BooleanValue ENEMY_BANNERS_DEFAULT;
    public static final ForgeConfigSpec.EnumValue<SelectionHighlightMode> SELECTION_HIGHLIGHT_MODE;
    public static final ForgeConfigSpec.EnumValue<GroundMarkerShape> GROUND_MARKER_SHAPE;
    public static final ForgeConfigSpec.BooleanValue FOG_RENDER_ENABLED;
    public static final ForgeConfigSpec.DoubleValue FOG_DENSITY;
    public static final ForgeConfigSpec.DoubleValue FOG_EDGE_FADE_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue FOG_ANIMATION_SPEED;
    public static final ForgeConfigSpec.DoubleValue FOG_HEIGHT;
    public static final ForgeConfigSpec.IntValue FOG_RENDER_DISTANCE;
    public static final ForgeConfigSpec CLIENT;

    static {
        BUILDER.push("interface");
        DEBUG_COMMAND_MENU = BUILDER
                .comment(
                        "Show the verbose RTS command/debug panel with the complete control reference and extra diagnostics.",
                        "Default false for normal play. Ask players to enable this when collecting bug reports or troubleshooting controls."
                )
                .define("debugCommandMenu", false);
        ADVISOR_VOICES_ENABLED = BUILDER
                .comment("Play the bundled battle-advisor voice pack for important Commander events.")
                .define("advisorVoices", true);
        ADVISOR_SUBTITLES = BUILDER
                .comment("Show a short text subtitle while a battle-advisor line is playing.")
                .define("advisorSubtitles", true);
        ENEMY_BANNERS_DEFAULT = BUILDER
                .comment("Default visibility of hostile floating unit banners when RTS mode opens. Can be toggled in-game.")
                .define("enemyBanners", true);
        SELECTION_HIGHLIGHT_MODE = BUILDER
                .comment(
                        "How owned RTS selections are shown.",
                        "GROUND_MARKERS is the new Rome-II-style default. ENTITY_GLOW preserves the old full-body yellow glow.",
                        "BOTH draws both systems; OFF disables selection highlighting entirely."
                )
                .defineEnum("selectionHighlightMode", SelectionHighlightMode.GROUND_MARKERS);
        GROUND_MARKER_SHAPE = BUILDER
                .comment("Shape used by the ground marker selection renderer.")
                .defineEnum("groundMarkerShape", GroundMarkerShape.CIRCLE);
        BUILDER.pop();

        BUILDER.push("fogOfWarVisuals");
        FOG_RENDER_ENABLED = BUILDER
                .comment(
                        "Render the world-space Fog of War clouds when the server has Fog of War enabled.",
                        "Turning this off is visual only: hidden-target rules and all server-side Fog of War logic remain active."
                )
                .define("renderEnabled", false);
        FOG_DENSITY = BUILDER
                .comment("Visual opacity/density multiplier for hidden-region fog banks.")
                .defineInRange("fogDensity", 1.0D, 0.20D, 2.50D);
        FOG_EDGE_FADE_DISTANCE = BUILDER
                .comment("How many blocks outside friendly vision the visual fog takes to reach full density.")
                .defineInRange("edgeFadeDistance", 18.0D, 4.0D, 48.0D);
        FOG_ANIMATION_SPEED = BUILDER
                .comment("Speed multiplier for the slow rolling/bobbing fog animation. 0 freezes the animation.")
                .defineInRange("animationSpeed", 1.0D, 0.0D, 3.0D);
        FOG_HEIGHT = BUILDER
                .comment("Approximate vertical height in blocks of the hidden-region fog bank above terrain.")
                .defineInRange("fogHeight", 68.0D, 24.0D, 128.0D);
        FOG_RENDER_DISTANCE = BUILDER
                .comment("Maximum horizontal distance in blocks at which world-space Fog of War clouds are sampled/rendered.")
                .defineInRange("renderDistance", 108, 48, 192);
        BUILDER.pop();

        CLIENT = BUILDER.build();
    }

    public static boolean usesGroundMarkers() {
        SelectionHighlightMode mode = SELECTION_HIGHLIGHT_MODE.get();
        return mode == SelectionHighlightMode.GROUND_MARKERS || mode == SelectionHighlightMode.BOTH;
    }

    public static boolean usesEntityGlow() {
        SelectionHighlightMode mode = SELECTION_HIGHLIGHT_MODE.get();
        return mode == SelectionHighlightMode.ENTITY_GLOW || mode == SelectionHighlightMode.BOTH;
    }

    public enum SelectionHighlightMode {
        GROUND_MARKERS,
        ENTITY_GLOW,
        BOTH,
        OFF
    }

    public enum GroundMarkerShape {
        CIRCLE,
        SQUARE
    }

    private RTSClientConfig() {
    }
}
