package ee.lastpagans.recruitsrtscamera.server;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.CaptainEntity;
import ee.lastpagans.recruitsrtscamera.compat.SmallShipsCompat;
import ee.lastpagans.recruitsrtscamera.config.RTSServerConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Server-authoritative tactical visibility used by Fog of War. */
public final class RTSVision {
    private RTSVision() {
    }

    public static boolean fogEnabled() {
        return RTSServerConfig.FOG_OF_WAR.get();
    }

    public static boolean hiddenTargetChecksEnabled() {
        return fogEnabled() && RTSServerConfig.FOG_BLOCK_HIDDEN_TARGETING.get();
    }

    public static boolean canSee(ServerPlayer player, Vec3 target) {
        if (player == null || target == null || !fogEnabled()) {
            return true;
        }
        double playerRadius = RTSServerConfig.FOG_PLAYER_VISION_RADIUS.get();
        if (horizontalDistanceSqr(player.position(), target) <= playerRadius * playerRadius) {
            return true;
        }

        int maxRadius = Math.max(
                RTSServerConfig.FOG_RECRUIT_VISION_RADIUS.get(),
                Math.max(RTSServerConfig.FOG_CAVALRY_VISION_RADIUS.get(), RTSServerConfig.FOG_SHIP_VISION_RADIUS.get())
        );
        AABB box = new AABB(target, target).inflate(maxRadius, 96.0D, maxRadius);
        for (AbstractRecruitEntity recruit : player.serverLevel().getEntitiesOfClass(AbstractRecruitEntity.class, box)) {
            if (!recruit.isAlive() || !player.getUUID().equals(recruit.getOwnerUUID())) {
                continue;
            }
            Vec3 source = recruit.position();
            double radius;
            if (recruit instanceof CaptainEntity captain && SmallShipsCompat.isCaptainAboard(captain)) {
                source = SmallShipsCompat.shipPosition(captain);
                radius = RTSServerConfig.FOG_SHIP_VISION_RADIUS.get();
            } else if (recruit.isPassenger()) {
                radius = RTSServerConfig.FOG_CAVALRY_VISION_RADIUS.get();
            } else {
                radius = RTSServerConfig.FOG_RECRUIT_VISION_RADIUS.get();
            }
            if (horizontalDistanceSqr(source, target) <= radius * radius) {
                return true;
            }
        }
        return false;
    }

    private static double horizontalDistanceSqr(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }
}
