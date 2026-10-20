package ee.lastpagans.recruitsrtscamera.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Read-only snapshot of the semantic Commander elements rendered in the latest frame. */
public final class CommanderUiRegistry {
    private static final List<Element> ELEMENTS = new ArrayList<>();

    private CommanderUiRegistry() {
    }

    static void beginFrame() {
        ELEMENTS.clear();
    }

    static void record(String id, int x, int y, int width, int height) {
        record(id, null, x, y, width, height, Float.NaN);
    }

    static void record(String id, UUID contextId, int x, int y, int width, int height, float value) {
        if (id == null || id.isBlank() || width <= 0 || height <= 0) return;
        ELEMENTS.add(new Element(id, contextId, x, y, width, height, value));
    }

    public static List<Element> snapshot() {
        return List.copyOf(ELEMENTS);
    }

    public record Element(String id, UUID contextId, int x, int y, int width, int height, float value) {
    }
}
