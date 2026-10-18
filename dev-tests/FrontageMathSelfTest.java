import ee.lastpagans.recruitsrtscamera.formation.FrontageMath;

import java.util.List;

public final class FrontageMathSelfTest {
    private static int checks;

    public static void main(String[] args) {
        yawChecks();
        oneGroupChecks();
        multiGroupChecks();
        lineFitChecks();
        System.out.println("FrontageMathSelfTest passed " + checks + " checks.");
    }

    private static void yawChecks() {
        near(Math.abs(FrontageMath.facingYaw(1, 0)), 180.0, 0.001, "eastward drag uses reversed facing convention");
        near(FrontageMath.facingYaw(0, 1), -90.0, 0.001, "southward drag uses reversed facing convention");
        near(FrontageMath.facingYaw(-1, 0), 0.0, 0.001, "westward drag flips facing");
        near(FrontageMath.facingYaw(0, -1), 90.0, 0.001, "northward drag flips facing");
    }

    private static void oneGroupChecks() {
        List<FrontageMath.GroupSegment> segments = FrontageMath.split(List.of(40), 20.0);
        eq(segments.size(), 1, "one group count");
        near(segments.get(0).centerOffset(), 0.0, 0.0001, "one group centered");
        near(segments.get(0).allocatedWidth(), 20.0, 0.0001, "one group owns full width");
        truth(segments.get(0).ranks() >= 1 && segments.get(0).ranks() <= 20, "ranks bounded");
        truth(segments.get(0).spacing() >= 0.65 && segments.get(0).spacing() <= 1.5, "spacing bounded");
    }

    private static void multiGroupChecks() {
        List<FrontageMath.GroupSegment> segments = FrontageMath.split(List.of(20, 40, 20), 42.0);
        eq(segments.size(), 3, "three group count");
        truth(segments.get(0).centerOffset() < segments.get(1).centerOffset(), "first before second");
        truth(segments.get(1).centerOffset() < segments.get(2).centerOffset(), "second before third");
        truth(segments.get(1).allocatedWidth() > segments.get(0).allocatedWidth(), "larger group gets larger frontage");
        near(segments.get(0).allocatedWidth(), segments.get(2).allocatedWidth(), 0.0001, "equal groups get equal frontage");
    }

    private static void lineFitChecks() {
        FrontageMath.LineFit broad = FrontageMath.bestLineFit(40, 30.0);
        FrontageMath.LineFit deep = FrontageMath.bestLineFit(40, 6.0);
        truth(broad.ranks() < deep.ranks(), "long drag produces fewer ranks");
        truth(broad.actualWidth() > deep.actualWidth(), "long drag produces wider line");
        truth(broad.columns() >= 1 && deep.columns() >= 1, "columns valid");
    }

    private static void near(double actual, double expected, double epsilon, String label) {
        checks++;
        if (Math.abs(actual - expected) > epsilon) {
            throw new AssertionError(label + ": expected " + expected + " but got " + actual);
        }
    }

    private static void eq(int actual, int expected, String label) {
        checks++;
        if (actual != expected) {
            throw new AssertionError(label + ": expected " + expected + " but got " + actual);
        }
    }

    private static void truth(boolean value, String label) {
        checks++;
        if (!value) {
            throw new AssertionError(label);
        }
    }
}
