import ee.lastpagans.recruitsrtscamera.formation.ArmyDeploymentMath;
import ee.lastpagans.recruitsrtscamera.formation.ArmyPreset;

import java.util.List;
import java.util.Map;

public final class ArmyDeploymentMathSelfTest {
    private static int checks;

    public static void main(String[] args) {
        List<ArmyDeploymentMath.GroupSpec> groups = List.of(
                new ArmyDeploymentMath.GroupSpec("inf1", ArmyDeploymentMath.Role.INFANTRY, 40, -4, 0),
                new ArmyDeploymentMath.GroupSpec("inf2", ArmyDeploymentMath.Role.INFANTRY, 40, 4, 0),
                new ArmyDeploymentMath.GroupSpec("rng", ArmyDeploymentMath.Role.RANGED, 30, 0, -5),
                new ArmyDeploymentMath.GroupSpec("cavL", ArmyDeploymentMath.Role.CAVALRY, 20, -12, 0),
                new ArmyDeploymentMath.GroupSpec("cavR", ArmyDeploymentMath.Role.CAVALRY, 20, 12, 0)
        );

        Map<String, ArmyDeploymentMath.Target> balanced = ArmyDeploymentMath.plan(
                ArmyPreset.BALANCED, groups, 0, 0, 0
        );
        check(balanced.size() == 5, "balanced includes every group");
        check(balanced.get("inf1").z() > balanced.get("rng").z(), "infantry is ahead of ranged");
        check(balanced.get("cavL").x() < balanced.get("inf1").x(), "left cavalry is on left wing");
        check(balanced.get("cavR").x() > balanced.get("inf2").x(), "right cavalry is on right wing");

        Map<String, ArmyDeploymentMath.Target> cavalry = ArmyDeploymentMath.plan(
                ArmyPreset.CAVALRY_VANGUARD, groups, 0, 0, 0
        );
        check(cavalry.get("cavL").z() > cavalry.get("inf1").z(), "cavalry vanguard is in front");
        check(cavalry.get("inf1").z() > cavalry.get("rng").z(), "ranged stays rear");

        Map<String, ArmyDeploymentMath.Target> doubleLine = ArmyDeploymentMath.plan(
                ArmyPreset.DOUBLE_LINE, groups, 0, 0, 0
        );
        long frontRank = doubleLine.values().stream().filter(target -> target.z() > 0).count();
        long rearRank = doubleLine.values().stream().filter(target -> target.z() < 0).count();
        check(frontRank == 3 && rearRank == 2, "double line splits groups into supporting ranks");

        Map<String, ArmyDeploymentMath.Target> skirmish = ArmyDeploymentMath.plan(
                ArmyPreset.SKIRMISH_SCREEN, groups, 10, 20, 90
        );
        // yaw 90 faces -X, so the front row should have smaller world X than the rear row.
        check(skirmish.get("rng").x() < skirmish.get("inf1").x(), "preset rotates with army facing");
        check(Double.isFinite(skirmish.get("cavL").x()), "rotated wing is finite");

        check(ArmyDeploymentMath.estimatedGroupWidth(100) > ArmyDeploymentMath.estimatedGroupWidth(10),
                "larger groups receive wider footprint");
        check(ArmyDeploymentMath.estimatedGroupWidth(10000) <= 18.0D, "group footprint is capped");

        System.out.println("ArmyDeploymentMathSelfTest: passed " + checks + " checks");
    }

    private static void check(boolean value, String name) {
        checks++;
        if (!value) throw new AssertionError(name);
    }
}
