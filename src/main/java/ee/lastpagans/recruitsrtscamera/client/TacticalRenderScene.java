package ee.lastpagans.recruitsrtscamera.client;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Immutable per-frame description consumed by the world-space tactical renderer. */
public record TacticalRenderScene(
        List<RouteRibbon> routes,
        List<SlotDecal> slots,
        List<FormationFootprint> footprints,
        List<FacingArrow> arrows
) {
    public static final TacticalRenderScene EMPTY = new TacticalRenderScene(List.of(), List.of(), List.of(), List.of());

    public record RouteRibbon(List<Vec3> points, int color, float width, boolean flowing) { }
    public record SlotDecal(Vec3 position, float yaw, int color, boolean heavy) { }
    public record FormationFootprint(List<Vec3> points, int color) { }
    public record FacingArrow(Vec3 origin, float yaw, int color) { }

    public static final class Builder {
        private final List<RouteRibbon> routes = new ArrayList<>();
        private final List<SlotDecal> slots = new ArrayList<>();
        private final List<FormationFootprint> footprints = new ArrayList<>();
        private final List<FacingArrow> arrows = new ArrayList<>();

        public Builder route(Collection<Vec3> points, int color, float width, boolean flowing) {
            if (points != null) {
                List<Vec3> safe = points.stream().filter(java.util.Objects::nonNull).toList();
                if (safe.size() >= 2) routes.add(new RouteRibbon(safe, color, width, flowing));
            }
            return this;
        }

        public Builder slot(Vec3 position, float yaw, int color, boolean heavy) {
            if (position != null) slots.add(new SlotDecal(position, yaw, color, heavy));
            return this;
        }

        public Builder footprint(Collection<Vec3> points, int color) {
            if (points != null) {
                List<Vec3> safe = points.stream().filter(java.util.Objects::nonNull).toList();
                if (safe.size() >= 2) footprints.add(new FormationFootprint(safe, color));
            }
            return this;
        }

        public Builder arrow(Vec3 origin, float yaw, int color) {
            if (origin != null && Float.isFinite(yaw)) arrows.add(new FacingArrow(origin, yaw, color));
            return this;
        }

        public TacticalRenderScene build() {
            return new TacticalRenderScene(List.copyOf(routes), List.copyOf(slots),
                    List.copyOf(footprints), List.copyOf(arrows));
        }
    }
}
