package ee.lastpagans.recruitsrtscamera.formation;

/** High-level multi-group deployment templates for RTS Camera. */
public enum ArmyPreset {
    BALANCED("Balanced", "infantry front · ranged rear · cavalry wings"),
    CAVALRY_VANGUARD("Cavalry Vanguard", "cavalry front · infantry middle · ranged rear"),
    SKIRMISH_SCREEN("Skirmish Screen", "ranged front · cavalry wings · infantry rear"),
    RESERVE("Reserve", "infantry front · ranged middle · cavalry reserve");

    private final String displayName;
    private final String description;

    ArmyPreset(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public ArmyPreset next() {
        ArmyPreset[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
