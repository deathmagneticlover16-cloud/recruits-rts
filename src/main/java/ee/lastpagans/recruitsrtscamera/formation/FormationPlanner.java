package ee.lastpagans.recruitsrtscamera.formation;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.util.FormationUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/** Pure planning helpers. No Better Formations calls belong here. */
public final class FormationPlanner {
    private FormationPlanner() {
    }

    public static FormationPlan move(UUID groupId, Vec3 destination, FormationKind kind) {
        return new FormationPlan(groupId, destination, FormationIntent.preset(kind));
    }

    public static FormationPlan movePreserved(
            UUID groupId,
            Vec3 destination,
            FormationKind kind,
            float facingYaw
    ) {
        return movePreserved(groupId, destination, kind, facingYaw, 1.0D, 0);
    }

    public static FormationPlan movePreserved(
            UUID groupId,
            Vec3 destination,
            FormationKind kind,
            float facingYaw,
            double spacing,
            int ranks
    ) {
        return new FormationPlan(
                groupId,
                destination,
                FormationIntent.presetPreserved(kind, facingYaw, spacing, ranks)
        );
    }

    public static FormationPlan frontage(
            UUID groupId,
            Vec3 destination,
            double width,
            float facingYaw,
            int ranks,
            double spacing
    ) {
        return new FormationPlan(
                groupId,
                destination,
                FormationIntent.frontage(width, facingYaw, ranks, spacing)
        );
    }

    /**
     * Build a re-form-in-place plan around the group's current average position.
     * This lets the player change shape without issuing an unwanted movement order.
     */
    public static FormationPlan reformHere(
            ServerLevel level,
            UUID groupId,
            List<AbstractRecruitEntity> recruits,
            FormationKind kind
    ) {
        if (level == null || groupId == null || recruits == null || recruits.isEmpty()) {
            return null;
        }

        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        for (AbstractRecruitEntity recruit : recruits) {
            x += recruit.getX();
            y += recruit.getY();
            z += recruit.getZ();
        }
        int count = recruits.size();
        BlockPos average = BlockPos.containing(x / count, y / count, z / count);
        BlockPos surface = FormationUtils.getPositionOrSurface(level, average);
        return move(groupId, Vec3.atBottomCenterOf(surface), kind);
    }
}
