package ee.lastpagans.recruitsrtscamera.formation;

import java.util.Locale;

/**
 * Loader-agnostic formation vocabulary used by RTS Camera.
 *
 * Keep this enum independent from Better Formations so future formation sources
 * (dragged frontage, custom-drawn slots, other formation engines) can produce the
 * same internal plan without becoming coupled to a specific addon API.
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
    BUBBLE;

    private static final FormationKind[] VALUES = values();

    public FormationKind next() {
        return VALUES[(ordinal() + 1) % VALUES.length];
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
