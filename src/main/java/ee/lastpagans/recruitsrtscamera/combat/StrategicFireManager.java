package ee.lastpagans.recruitsrtscamera.combat;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Authoritative bombardment disk and exact ballistic aim points for explicit strategic fire. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID)
public final class StrategicFireManager {
    public static final double BOMBARDMENT_RADIUS = 6.0D;
    private static final double GOLDEN_ANGLE = Math.PI * (3.0D - Math.sqrt(5.0D));
    private static final double ARROW_GRAVITY = 0.05D;
    private static final Map<UUID, AimPoint> ORDERS = new ConcurrentHashMap<>();

    private StrategicFireManager() { }

    /**
     * Spread a formation over one stable disk. Unlike random per-shot inaccuracy, every
     * archer receives a deterministic surface point that remains inside the displayed area.
     */
    public static Map<UUID, BlockPos> assign(ServerLevel level, List<AbstractRecruitEntity> recruits, BlockPos center) {
        List<AbstractRecruitEntity> ordered = recruits.stream()
                .filter(recruit -> recruit != null && recruit.isAlive())
                .sorted(Comparator.comparing(AbstractRecruitEntity::getUUID))
                .toList();
        Map<UUID, BlockPos> result = new java.util.LinkedHashMap<>();
        int count = ordered.size();
        for (int index = 0; index < count; index++) {
            AbstractRecruitEntity recruit = ordered.get(index);
            double radius = BOMBARDMENT_RADIUS * Math.sqrt((index + 0.5D) / Math.max(1, count));
            double angle = index * GOLDEN_ANGLE + Long.hashCode(center.asLong()) * 0.0001D;
            int x = center.getX() + (int) Math.round(Math.cos(angle) * radius);
            int z = center.getZ() + (int) Math.round(Math.sin(angle) * radius);
            BlockPos column = new BlockPos(x, center.getY(), z);
            int y = level.hasChunkAt(column)
                    ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
                    : center.getY() + 1;
            BlockPos target = new BlockPos(x, y, z);
            ORDERS.put(recruit.getUUID(), new AimPoint(target, level.getGameTime() + 20L * 60L * 10L));
            result.put(recruit.getUUID(), target);
        }
        return result;
    }

    public static void assignExact(AbstractRecruitEntity recruit, BlockPos target) {
        if (recruit != null && target != null) {
            ORDERS.put(recruit.getUUID(), new AimPoint(
                    target.immutable(), recruit.level().getGameTime() + 20L * 60L * 10L));
        }
    }

    public static boolean hasOrder(AbstractRecruitEntity recruit) {
        if (recruit == null) return false;
        AimPoint point = ORDERS.get(recruit.getUUID());
        if (point == null) return false;
        if (point.expiresAt < recruit.level().getGameTime()) {
            ORDERS.remove(recruit.getUUID(), point);
            return false;
        }
        return true;
    }

    public static void clear(AbstractRecruitEntity recruit) {
        if (recruit != null) ORDERS.remove(recruit.getUUID());
    }

    public static boolean correctArrow(AbstractArrow arrow, AbstractRecruitEntity shooter) {
        AimPoint point = shooter == null ? null : ORDERS.get(shooter.getUUID());
        if (arrow == null || point == null || point.expiresAt < shooter.level().getGameTime()) return false;
        Vec3 current = arrow.getDeltaMovement();
        double horizontalSpeed = Math.hypot(current.x, current.z);
        if (horizontalSpeed < 0.10D) return false;
        Vec3 target = Vec3.atBottomCenterOf(point.position).add(0.0D, 0.08D, 0.0D);
        Vec3 delta = target.subtract(arrow.position());
        BallisticArc.Velocity solution = BallisticArc.solve(
                delta.x, delta.y, delta.z, horizontalSpeed, ARROW_GRAVITY);
        arrow.setDeltaMovement(solution.x(), solution.y(), solution.z());
        return true;
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof AbstractRecruitEntity recruit) clear(recruit);
    }

    @SubscribeEvent
    public static void onStop(ServerStoppedEvent event) {
        ORDERS.clear();
    }

    private record AimPoint(BlockPos position, long expiresAt) { }
}
