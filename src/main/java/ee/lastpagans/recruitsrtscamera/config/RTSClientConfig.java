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
    public static final ForgeConfigSpec.BooleanValue CAVALRY_CAMERA_SHAKE;
    public static final ForgeConfigSpec.DoubleValue CAVALRY_CAMERA_SHAKE_INTENSITY;
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
        CAVALRY_CAMERA_SHAKE = BUILDER
                .comment("Shake the camera when a moving cavalry formation passes nearby.")
                .define("cavalryCameraShake", true);
        CAVALRY_CAMERA_SHAKE_INTENSITY = BUILDER
                .comment("Strength of cavalry camera shake. 1.0 makes a nearby 17-rider formation clearly visible.")
                .defineInRange("cavalryCameraShakeIntensity", 1.0D, 0.0D, 3.0D);
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
