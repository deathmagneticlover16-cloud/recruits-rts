package ee.lastpagans.recruitsrtscamera.troops;

import java.util.Locale;

/**
 * Stable, troop-wide statistic schema shared by combat, Commander UI, future editors
 * and data packs. TroopStatService calculates and applies the live values.
 */
public enum TroopStatistic {
    DAMAGE(
            "stat.damage", "Damage",
            "Current melee damage dealt by the soldier's equipped weapon. Ranged projectile damage is tracked separately by its weapon.",
            6.0D, 40.0D, true),
    MELEE_SKILL(
            "stat.melee_skill", "Melee Skill",
            "Determines how effectively a soldier lands melee attacks. Higher skill increases the chance of successfully hitting an opponent.",
            50.0D, 100.0D, true),
    MELEE_DEFENCE(
            "stat.melee_defence", "Melee Defence",
            "Determines how effectively a soldier avoids, blocks or parries incoming melee attacks.",
            50.0D, 100.0D, true),
    CHARGE_BONUS(
            "stat.charge_bonus", "Charge Bonus",
            "Determines the additional damage and morale shock inflicted when charging into an enemy.",
            30.0D, 100.0D, true),
    SPEED(
            "stat.speed", "Speed",
            "Maximum formation movement capability from mount, armor and carried inventory. Current walking, running or standing state does not change it.",
            60.0D, 160.0D, true),
    MORALE(
            "stat.morale", "Morale",
            "Determines a soldier's willingness to continue fighting. Ranges from 0 to 100, potentially reaching 110 with exceptional leadership; at 0, the soldier routes.",
            50.0D, 110.0D, true),
    FATIGUE(
            "stat.fatigue", "Fatigue",
            "Represents physical exhaustion accumulated through movement and combat. Higher fatigue reduces combat effectiveness.",
            0.0D, 100.0D, false),
    ACCURACY(
            "stat.accuracy", "Accuracy",
            "Determines how precisely a soldier fires ranged weapons at their intended target.",
            50.0D, 100.0D, true),
    AMMUNITION(
            "stat.ammunition", "Ammunition",
            "Determines how much ammunition a soldier carries and has available for ranged combat.",
            0.0D, 192.0D, true),
    RATE_OF_FIRE(
            "stat.rate_of_fire", "Rate of Fire",
            "Determines how quickly a soldier can fire ranged weapons, affected by weapon type and potentially fatigue.",
            50.0D, 100.0D, true);

    private final String id;
    private final String displayName;
    private final String description;
    private final double defaultValue;
    private final double maximum;
    private final boolean higherIsBetter;

    TroopStatistic(
            String id,
            String displayName,
            String description,
            double defaultValue,
            double maximum,
            boolean higherIsBetter
    ) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.defaultValue = defaultValue;
        this.maximum = maximum;
        this.higherIsBetter = higherIsBetter;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public String description() { return description; }
    public double minimum() { return 0.0D; }
    public double maximum() { return maximum; }
    public double defaultValue() { return defaultValue; }
    public boolean higherIsBetter() { return higherIsBetter; }
    public String translationKey() { return "recruits_rts_camera." + id; }
    public String descriptionTranslationKey() { return translationKey() + ".description"; }

    public double clamp(double value) {
        return Double.isFinite(value) ? Math.max(minimum(), Math.min(maximum(), value)) : defaultValue;
    }

    public static TroopStatistic fromId(String id) {
        if (id == null || id.isBlank()) return null;
        String normalized = id.toLowerCase(Locale.ROOT);
        for (TroopStatistic statistic : values()) {
            if (statistic.id.equals(normalized)) return statistic;
        }
        return null;
    }
}
