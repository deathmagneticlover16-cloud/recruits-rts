package ee.lastpagans.recruitsrtscamera.formation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Ordered registry used by future resource loaders and formation editors. */
public final class FormationBlueprintRegistry {
    private static final Map<String, FormationBlueprint> BLUEPRINTS = new LinkedHashMap<>();

    private FormationBlueprintRegistry() { }

    public static synchronized void register(FormationBlueprint blueprint) {
        if (blueprint == null) throw new IllegalArgumentException("Blueprint is required");
        BLUEPRINTS.put(blueprint.id(), blueprint);
    }

    public static synchronized Optional<FormationBlueprint> find(String id) {
        return Optional.ofNullable(BLUEPRINTS.get(id));
    }

    public static synchronized Map<String, FormationBlueprint> snapshot() {
        return Map.copyOf(BLUEPRINTS);
    }
}
