package ee.lastpagans.recruitsrtscamera.formation;

import java.util.Locale;

/**
 * Loader-agnostic formation vocabulary used by RTS Camera.
 *
 * Shared vocabulary for preset, dragged-frontage, and custom formation planners.
 */
public enum FormationKind {
    MARCH,
    LINE,
    SQUARE,
    TRIANGLE,
    RING,
    CIRCLE,
    HOLLOW_SQUARE,
    VEE,
    BUBBLE,
    /** Mounted shock formation exposed as its own Commander command. */
    CAVALRY_WEDGE;

    private static final FormationKind[] VALUES = values();

    public FormationKind next() {
        // Cavalry wedge has its own eligibility-gated command. Keep the ordinary cycle
        // on the original general-purpose formations so infantry cannot become stuck on it.
        int generalPurposeCount = CAVALRY_WEDGE.ordinal();
        return this == CAVALRY_WEDGE ? MARCH : VALUES[(ordinal() + 1) % generalPurposeCount];
    }

    public String displayName() {
        String[] parts = name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return result.toString();
    }

    public static FormationKind fromExternalName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
