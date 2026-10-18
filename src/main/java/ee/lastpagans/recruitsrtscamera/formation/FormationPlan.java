package ee.lastpagans.recruitsrtscamera.formation;

import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Engine-neutral description of one group's destination formation.
 *
 * Preset and frontage plans pass through this compact engine-neutral form. Freehand
 * custom geometry uses its own deterministic slot executor while sharing the same
 * FormationService boundary, so camera/selection/network input stays decoupled.
 */
public record FormationPlan(
        UUID groupId,
        Vec3 destination,
        FormationIntent intent
) {
}
