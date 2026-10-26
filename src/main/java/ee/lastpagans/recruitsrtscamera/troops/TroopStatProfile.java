package ee.lastpagans.recruitsrtscamera.troops;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Complete troop/group-wide stat block. Values are deliberately inert for now: this is the
 * stable interchange model that future balance data, UI editors and combat systems can share.
 */
public final class TroopStatProfile {
    public static final TroopStatProfile NEUTRAL = builder().build();
    private final EnumMap<TroopStatistic, Double> values;

    private TroopStatProfile(EnumMap<TroopStatistic, Double> values) {
        this.values = values;
    }

    public double value(TroopStatistic statistic) {
        return statistic == null ? 0.0D : values.getOrDefault(statistic, statistic.defaultValue());
    }

    public Map<TroopStatistic, Double> values() {
        return Collections.unmodifiableMap(values);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final EnumMap<TroopStatistic, Double> values = new EnumMap<>(TroopStatistic.class);

        private Builder() {
            for (TroopStatistic statistic : TroopStatistic.values()) {
                values.put(statistic, statistic.defaultValue());
            }
        }

        public Builder set(TroopStatistic statistic, double value) {
            if (statistic == null || !Double.isFinite(value)) throw new IllegalArgumentException("Invalid troop statistic");
            values.put(statistic, statistic.clamp(value));
            return this;
        }

        public TroopStatProfile build() { return new TroopStatProfile(new EnumMap<>(values)); }
    }
}
