package ee.lastpagans.recruitsrtscamera.client;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Immutable per-frame description consumed by the world-space tactical renderer. */
public record TacticalRenderScene(
        List<RouteRibbon> routes,
        List<SlotDecal> slots,
        List<FacingArrow> arrows
) {
    public static final TacticalRenderScene EMPTY = new TacticalRenderScene(List.of(), List.of(), List.of());

    public record RouteRibbon(List<Vec3> points, int color, float width, boolean flowing, float flowSpeed) { }
    public record SlotDecal(Vec3 position, float yaw, int color, boolean heavy) { }
    public record FacingArrow(Vec3 origin, float yaw, int color) { }

    public static final class Builder {
        private final List<RouteRibbon> routes = new ArrayList<>();
        private final List<SlotDecal> slots = new ArrayList<>();
        private final List<FacingArrow> arrows = new ArrayList<>();

        public Builder route(Collection<Vec3> points, int color, float width, boolean flowing) {
            return route(points, color, width, flowing, flowing ? 1.0F : 0.0F);
        }

        public Builder route(Collection<Vec3> points, int color, float width, boolean flowing, float flowSpeed) {
            if (points != null) {
                List<Vec3> safe = points.stream().filter(java.util.Objects::nonNull).toList();
                if (safe.size() >= 2) routes.add(new RouteRibbon(
                        safe, color, width, flowing, Math.max(0.0F, flowSpeed)));
            }
            return this;
        }

        public Builder slot(Vec3 position, float yaw, int color, boolean heavy) {
            if (position != null) slots.add(new SlotDecal(position, yaw, color, heavy));
            return this;
        }

        public Builder arrow(Vec3 origin, float yaw, int color) {
            if (origin != null && Float.isFinite(yaw)) arrows.add(new FacingArrow(origin, yaw, color));
            return this;
        }

        public TacticalRenderScene build() {
            return new TacticalRenderScene(List.copyOf(routes), List.copyOf(slots), List.copyOf(arrows));
        }
    }
}
