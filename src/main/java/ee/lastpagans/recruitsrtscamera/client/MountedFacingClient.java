package ee.lastpagans.recruitsrtscamera.client;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.world.entity.Mob;

/** Keeps the client render interpolation of an RTS rider exactly aligned with its mount. */
public final class MountedFacingClient {
    private MountedFacingClient() { }

    public static void tick() {
        for (var entity : RTSCameraController.commanderLoadedLivingEntities()) {
            if (!(entity instanceof AbstractRecruitEntity recruit)
                    || !(recruit.getVehicle() instanceof Mob mount)
                    || recruit.getFollowState() != 3
                    || !recruit.getShouldHoldPos()) continue;
            align(recruit, mount);
        }
    }

    private static void align(AbstractRecruitEntity recruit, Mob mount) {
        float yaw = mount.getYRot();
        float previousYaw = mount.yRotO;
        mount.setYBodyRot(yaw);
        mount.setYHeadRot(yaw);
        mount.yBodyRotO = previousYaw;
        mount.yHeadRotO = previousYaw;

        recruit.ownerRot = yaw;
        recruit.setYRot(yaw);
        recruit.setYBodyRot(yaw);
        recruit.setYHeadRot(yaw);
        recruit.yRotO = previousYaw;
        recruit.yBodyRotO = previousYaw;
        recruit.yHeadRotO = previousYaw;
    }
}
