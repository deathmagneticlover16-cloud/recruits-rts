package ee.lastpagans.recruitsrtscamera.client;

import ee.lastpagans.recruitsrtscamera.config.RTSClientConfig;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stable client-side ordering for Commander cards, including groups outside the current page. */
public final class UnitCardOrder {
    private static final List<UUID> ORDER = new ArrayList<>();
    private static boolean loaded;

    private UnitCardOrder() { }

    public static <T> void apply(List<T> cards, java.util.function.Function<T, UUID> id) {
        if (cards == null || cards.isEmpty()) return;
        load();
        Collection<UUID> live = cards.stream().map(id).toList();
        ORDER.removeIf(existing -> !live.contains(existing));
        for (UUID group : live) if (!ORDER.contains(group)) ORDER.add(group);
        Map<UUID, Integer> position = new HashMap<>();
        for (int i = 0; i < ORDER.size(); i++) position.put(ORDER.get(i), i);
        cards.sort(java.util.Comparator.comparingInt(card -> position.getOrDefault(id.apply(card), Integer.MAX_VALUE)));
    }

    public static void move(UUID dragged, UUID target, boolean after) {
        if (dragged == null || target == null || dragged.equals(target)) return;
        load();
        ORDER.remove(dragged);
        int targetIndex = ORDER.indexOf(target);
        if (targetIndex < 0) ORDER.add(dragged);
        else ORDER.add(Math.min(ORDER.size(), targetIndex + (after ? 1 : 0)), dragged);
        RTSClientConfig.UNIT_CARD_ORDER.set(ORDER.stream().map(UUID::toString).toList());
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        for (String raw : RTSClientConfig.UNIT_CARD_ORDER.get()) {
            try {
                UUID id = UUID.fromString(raw);
                if (!ORDER.contains(id)) ORDER.add(id);
            } catch (IllegalArgumentException ignored) { }
        }
    }
}
