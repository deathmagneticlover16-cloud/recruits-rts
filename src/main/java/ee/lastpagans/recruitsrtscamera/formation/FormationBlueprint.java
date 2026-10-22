package ee.lastpagans.recruitsrtscamera.formation;

import java.util.List;

/** Data-only geometry contract for future pixel/grid-authored formations. */
public record FormationBlueprint(String id, String displayName, List<PixelSlot> slots, RepeatSegment repeat) {
    public FormationBlueprint {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Formation id is required");
        displayName = displayName == null || displayName.isBlank() ? id : displayName;
        slots = List.copyOf(slots == null ? List.of() : slots);
    }

    public record PixelSlot(int x, int forward, String role) {
        public PixelSlot { role = role == null ? "unit" : role; }
    }

    /** Inclusive column range that can be repeated to widen a painted formation. */
    public record RepeatSegment(int fromX, int toX) {
        public RepeatSegment {
            if (toX < fromX) throw new IllegalArgumentException("Repeat segment is reversed");
        }
    }
}
