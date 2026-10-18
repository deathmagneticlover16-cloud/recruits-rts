package ee.lastpagans.recruitsrtscamera.formation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dependency-free planner for high-level army presets.
 *
 * It only moves group centres. Each group's internal formation remains owned by
 * FormationService, the native formation runtime, or the custom-formation registry.
 */
public final class ArmyDeploymentMath {
    private static final double ROW_GAP = 3.0D;
    private static final double WING_MARGIN = 5.5D;

    private ArmyDeploymentMath() {
    }

    public enum Role {
        INFANTRY,
        RANGED,
        CAVALRY,
        SUPPORT
    }

    public record GroupSpec(String id, Role role, int troopCount, double currentX, double currentZ) {
        public GroupSpec {
            role = role == null ? Role.INFANTRY : role;
            troopCount = Math.max(1, troopCount);
        }
    }

    public record Target(double x, double z) {
    }

    private record Offset(double right, double forward) {
    }

    /**
     * Plan group-centre destinations around one army centre and one shared facing.
     * Minecraft yaw convention matches the native formation runtime: forward=(-sin(yaw), cos(yaw)).
     */
    public static Map<String, Target> plan(
            ArmyPreset preset,
            List<GroupSpec> groups,
            double centerX,
            double centerZ,
            float facingYaw
    ) {
        if (preset == null || groups == null || groups.isEmpty()) {
            return Map.of();
        }

        double radians = Math.toRadians(facingYaw);
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        double rightX = forwardZ;
        double rightZ = -forwardX;

        List<GroupSpec> infantry = role(groups, Role.INFANTRY, Role.SUPPORT);
        List<GroupSpec> ranged = role(groups, Role.RANGED);
        List<GroupSpec> cavalry = role(groups, Role.CAVALRY);
        Comparator<GroupSpec> leftToRight = Comparator
                .comparingDouble((GroupSpec group) -> group.currentX() * rightX + group.currentZ() * rightZ)
                .thenComparing(GroupSpec::id);
        infantry.sort(leftToRight);
        ranged.sort(leftToRight);
        cavalry.sort(leftToRight);
        List<GroupSpec> all = new ArrayList<>(groups);
        all.sort(leftToRight);

        Map<String, Offset> offsets = new LinkedHashMap<>();
        switch (preset) {
            case BALANCED -> {
                addRow(offsets, infantry, +4.5D);
                addRow(offsets, ranged, -5.0D);
                double coreHalf = Math.max(rowHalfWidth(infantry), rowHalfWidth(ranged));
                addWings(offsets, cavalry, 0.5D, coreHalf);
            }
            case DOUBLE_LINE -> {
                int split = (all.size() + 1) / 2;
                addRow(offsets, all.subList(0, split), +3.5D);
                addRow(offsets, all.subList(split, all.size()), -3.5D);
            }
            case CAVALRY_VANGUARD -> {
                addRow(offsets, cavalry, +6.0D);
                addRow(offsets, infantry, 0.0D);
                addRow(offsets, ranged, -6.0D);
            }
            case SKIRMISH_SCREEN -> {
                addRow(offsets, ranged, +5.0D);
                addRow(offsets, infantry, -5.0D);
                double coreHalf = Math.max(rowHalfWidth(ranged), rowHalfWidth(infantry));
                addWings(offsets, cavalry, 0.0D, coreHalf);
            }
            case RESERVE -> {
                addRow(offsets, infantry, +4.0D);
                addRow(offsets, ranged, -1.0D);
                addRow(offsets, cavalry, -7.0D);
            }
        }

        // A preset still has to work for a selection containing only unknown/support
        // groups. Any group not assigned above is placed in the centre row.
        for (GroupSpec group : groups) {
            offsets.putIfAbsent(group.id(), new Offset(0.0D, 0.0D));
        }

        Map<String, Target> result = new LinkedHashMap<>();
        for (GroupSpec group : groups) {
            Offset offset = offsets.get(group.id());
            double x = centerX + rightX * offset.right() + forwardX * offset.forward();
            double z = centerZ + rightZ * offset.right() + forwardZ * offset.forward();
            result.put(group.id(), new Target(x, z));
        }
        return Map.copyOf(result);
    }

    private static List<GroupSpec> role(List<GroupSpec> groups, Role... roles) {
        List<GroupSpec> result = new ArrayList<>();
        outer:
        for (GroupSpec group : groups) {
            for (Role role : roles) {
                if (group.role() == role) {
                    result.add(group);
                    continue outer;
                }
            }
        }
        return result;
    }

    private static void addRow(Map<String, Offset> offsets, List<GroupSpec> groups, double forward) {
        if (groups.isEmpty()) {
            return;
        }
        double total = rowWidth(groups);
        double cursor = -total * 0.5D;
        for (GroupSpec group : groups) {
            double width = estimatedGroupWidth(group.troopCount());
            cursor += width * 0.5D;
            offsets.put(group.id(), new Offset(cursor, forward));
            cursor += width * 0.5D + ROW_GAP;
        }
    }

    private static void addWings(
            Map<String, Offset> offsets,
            List<GroupSpec> cavalry,
            double forward,
            double coreHalfWidth
    ) {
        if (cavalry.isEmpty()) {
            return;
        }
        int leftCount = (cavalry.size() + 1) / 2;
        List<GroupSpec> left = cavalry.subList(0, leftCount);
        List<GroupSpec> right = cavalry.subList(leftCount, cavalry.size());

        double leftCursor = -(coreHalfWidth + WING_MARGIN);
        for (int i = left.size() - 1; i >= 0; i--) {
            GroupSpec group = left.get(i);
            double width = estimatedGroupWidth(group.troopCount());
            leftCursor -= width * 0.5D;
            offsets.put(group.id(), new Offset(leftCursor, forward));
            leftCursor -= width * 0.5D + ROW_GAP;
        }

        double rightCursor = coreHalfWidth + WING_MARGIN;
        for (GroupSpec group : right) {
            double width = estimatedGroupWidth(group.troopCount());
            rightCursor += width * 0.5D;
            offsets.put(group.id(), new Offset(rightCursor, forward));
            rightCursor += width * 0.5D + ROW_GAP;
        }

        // A single cavalry group should still be a clear wing rather than sitting on
        // top of the infantry centre. Put it on the left by convention.
        if (cavalry.size() == 1) {
            GroupSpec group = cavalry.get(0);
            double width = estimatedGroupWidth(group.troopCount());
            offsets.put(group.id(), new Offset(-(coreHalfWidth + WING_MARGIN + width * 0.5D), forward));
        }
    }

    private static double rowHalfWidth(List<GroupSpec> groups) {
        return rowWidth(groups) * 0.5D;
    }

    private static double rowWidth(List<GroupSpec> groups) {
        if (groups.isEmpty()) {
            return 0.0D;
        }
        double width = 0.0D;
        for (GroupSpec group : groups) {
            width += estimatedGroupWidth(group.troopCount());
        }
        width += ROW_GAP * Math.max(0, groups.size() - 1);
        return width;
    }

    /** Rough group footprint used only for spacing group centres in a high-level preset. */
    public static double estimatedGroupWidth(int troopCount) {
        return clamp(3.5D + Math.sqrt(Math.max(1, troopCount)) * 1.35D, 5.0D, 18.0D);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
