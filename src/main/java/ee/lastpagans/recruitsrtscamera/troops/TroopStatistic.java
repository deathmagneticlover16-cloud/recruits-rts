package ee.lastpagans.recruitsrtscamera.troops;

/** Stable, troop-wide statistic keys for UI, data packs and future combat integrations. */
public enum TroopStatistic {
    DAMAGE("stat.damage"),
    MELEE_SKILL("stat.melee_skill"),
    MELEE_DEFENCE("stat.melee_defence"),
    CHARGE_BONUS("stat.charge_bonus"),
    SPEED("stat.speed"),
    MORALE("stat.morale"),
    FATIGUE("stat.fatigue"),
    ACCURACY("stat.accuracy"),
    AMMUNITION("stat.ammunition"),
    RATE_OF_FIRE("stat.rate_of_fire");

    private final String id;

    TroopStatistic(String id) { this.id = id; }

    public String id() { return id; }
}
