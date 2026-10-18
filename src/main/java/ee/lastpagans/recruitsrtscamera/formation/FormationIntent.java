package ee.lastpagans.recruitsrtscamera.formation;

/**
 * What the player wants, before any formation engine is chosen.
 *
 * Facing can now be supplied for PRESET as well as FRONTAGE plans. This lets a normal
 * move translate an existing formation without silently rotating it toward the player.
 */
public record FormationIntent(
        FormationMode mode,
        FormationKind kind,
        double spacing,
        boolean hold,
        double frontageWidth,
        float facingYaw,
        int ranks
) {
    public FormationIntent {
        if (mode == null) {
            mode = FormationMode.PRESET;
        }
        if (kind == null) {
            kind = FormationKind.MARCH;
        }
        spacing = Math.max(0.65D, Math.min(2.25D, spacing));
        frontageWidth = Math.max(0.0D, frontageWidth);
        ranks = Math.max(0, Math.min(20, ranks));
    }

    public static FormationIntent preset(FormationKind kind) {
        return new FormationIntent(FormationMode.PRESET, kind, 1.0D, false, 0.0D, Float.NaN, 0);
    }

    public static FormationIntent presetPreserved(FormationKind kind, float facingYaw) {
        return presetPreserved(kind, facingYaw, 1.0D, 0);
    }

    /**
     * A preserved move keeps the active shape/facing and, when known, the exact line
     * spacing/rank depth that produced the current frontage.
     */
    public static FormationIntent presetPreserved(
            FormationKind kind,
            float facingYaw,
            double spacing,
            int ranks
    ) {
        return new FormationIntent(FormationMode.PRESET, kind, spacing, false, 0.0D, facingYaw, ranks);
    }

    public static FormationIntent frontage(double width, float facingYaw, int ranks, double spacing) {
        return new FormationIntent(
                FormationMode.FRONTAGE,
                FormationKind.LINE,
                spacing,
                false,
                width,
                facingYaw,
                ranks
        );
    }
}
