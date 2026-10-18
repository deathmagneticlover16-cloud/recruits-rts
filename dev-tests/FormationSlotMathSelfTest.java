import ee.lastpagans.recruitsrtscamera.formation.FormationKind;
import ee.lastpagans.recruitsrtscamera.formation.FormationSlotMath;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class FormationSlotMathSelfTest {
    private static int checks;

    public static void main(String[] args) {
        geometry();
        assignment();
        largeAssignment();
        System.out.println("FormationSlotMathSelfTest passed " + checks + " checks.");
    }

    private static void geometry() {
        for (FormationKind kind : FormationKind.values()) {
            var slots = FormationSlotMath.create(kind, 100, 1.0D, 4, 24.0D, false);
            eq(slots.size(), 100, kind + " has exact slot count");
            near(slots.stream().mapToDouble(FormationSlotMath.Offset::right).average().orElseThrow(), 0.0D,
                    kind + " is centered on the lateral axis");
            near(slots.stream().mapToDouble(FormationSlotMath.Offset::back).average().orElseThrow(), 0.0D,
                    kind + " is centered on the depth axis");
        }
        var foot = FormationSlotMath.create(FormationKind.MARCH, 16, 1.0D, 0, 0.0D, false);
        var mounted = FormationSlotMath.create(FormationKind.MARCH, 16, 1.0D, 0, 0.0D, true);
        truth(extent(mounted) > extent(foot) * 1.5D, "mounted formations have wider clearance");
    }

    private static void assignment() {
        List<FormationSlotMath.Point> units = List.of(
                new FormationSlotMath.Point(9.0D, 0.0D),
                new FormationSlotMath.Point(1.0D, 0.0D),
                new FormationSlotMath.Point(5.0D, 0.0D)
        );
        List<FormationSlotMath.Point> slots = List.of(
                new FormationSlotMath.Point(0.0D, 0.0D),
                new FormationSlotMath.Point(5.0D, 0.0D),
                new FormationSlotMath.Point(10.0D, 0.0D)
        );
        int[] assignment = FormationSlotMath.assign(units, slots);
        eq(assignment.length, 3, "all units assigned");
        eq(assignment[0], 2, "right unit keeps right slot");
        eq(assignment[1], 0, "left unit keeps left slot");
        eq(assignment[2], 1, "middle unit keeps middle slot");
        Set<Integer> unique = new HashSet<>();
        for (int slot : assignment) unique.add(slot);
        eq(unique.size(), 3, "assignment is one-to-one");
    }

    private static void largeAssignment() {
        int count = 1_200;
        var units = new java.util.ArrayList<FormationSlotMath.Point>(count);
        var slots = new java.util.ArrayList<FormationSlotMath.Point>(count);
        for (int i = 0; i < count; i++) {
            units.add(new FormationSlotMath.Point(i % 60 + 0.2D, i / 60 + 0.1D));
            slots.add(new FormationSlotMath.Point(i % 60, i / 60));
        }
        int[] assignment = FormationSlotMath.assign(units, slots);
        eq(assignment.length, count, "1,200-unit fallback assigns every recruit");
        Set<Integer> unique = new HashSet<>();
        for (int slot : assignment) unique.add(slot);
        eq(unique.size(), count, "1,200-unit fallback remains one-to-one");
    }

    private static double extent(List<FormationSlotMath.Offset> slots) {
        return slots.stream().mapToDouble(slot -> Math.hypot(slot.right(), slot.back())).max().orElse(0.0D);
    }

    private static void near(double actual, double expected, String label) {
        checks++;
        if (Math.abs(actual - expected) > 1.0E-8D) throw new AssertionError(label + ": " + actual);
    }

    private static void eq(int actual, int expected, String label) {
        checks++;
        if (actual != expected) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }

    private static void truth(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
}
