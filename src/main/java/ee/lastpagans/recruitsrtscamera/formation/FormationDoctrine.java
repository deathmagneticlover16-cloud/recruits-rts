package ee.lastpagans.recruitsrtscamera.formation;

import java.util.ArrayList;
import java.util.List;

/**
 * Experimental battlefield effects for preset formations in the private server fork.
 * Values are intentionally modest and can be tuned later without changing formation geometry.
 */
public record FormationDoctrine(
        double movementMultiplier,
        double attackMultiplier,
        double knockbackResistanceAdd,
        String role
) {
    public static FormationDoctrine forKind(FormationKind kind) {
        if (kind == null) {
            return neutral("No doctrine");
        }
        return switch (kind) {
            case MARCH -> new FormationDoctrine(0.12D, -0.10D, 0.00D,
                    "Fast movement, poor fighting posture");
            case LINE -> new FormationDoctrine(-0.05D, 0.08D, 0.00D,
                    "Strong fighting frontage, slower maneuver");
            case SQUARE -> new FormationDoctrine(-0.18D, -0.05D, 0.20D,
                    "All-round defense, very slow movement");
            case TRIANGLE -> new FormationDoctrine(0.05D, 0.12D, 0.00D,
                    "Offensive wedge with stronger impact");
            case RING -> new FormationDoctrine(-0.10D, 0.00D, 0.12D,
                    "Defensive ring with improved staying power");
            case CIRCLE -> new FormationDoctrine(-0.08D, 0.02D, 0.10D,
                    "Flexible all-round defense");
            case HOLLOW_SQUARE -> new FormationDoctrine(-0.20D, -0.05D, 0.25D,
                    "Maximum all-round defense, poor mobility");
            case VEE -> new FormationDoctrine(0.04D, 0.10D, 0.00D,
                    "Aggressive V formation with good forward pressure");
            case BUBBLE -> new FormationDoctrine(-0.12D, 0.00D, 0.15D,
                    "Compact protective formation");
        };
    }

    private static FormationDoctrine neutral(String role) {
        return new FormationDoctrine(0.0D, 0.0D, 0.0D, role);
    }

    public List<String> tooltipLines() {
        List<String> lines = new ArrayList<>();
        lines.add(role);
        addPercent(lines, "Movement", movementMultiplier);
        addPercent(lines, "Attack", attackMultiplier);
        if (Math.abs(knockbackResistanceAdd) > 1.0E-5D) {
            int value = (int) Math.round(knockbackResistanceAdd * 100.0D);
            lines.add((value >= 0 ? "+" : "") + value + "% knockback resistance");
        }
        return List.copyOf(lines);
    }

    private static void addPercent(List<String> lines, String label, double value) {
        if (Math.abs(value) <= 1.0E-5D) {
            return;
        }
        int percent = (int) Math.round(value * 100.0D);
        lines.add((percent >= 0 ? "+" : "") + percent + "% " + label.toLowerCase());
    }
}
