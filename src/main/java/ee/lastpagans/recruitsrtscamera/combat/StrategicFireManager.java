package ee.lastpagans.recruitsrtscamera.combat;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Authoritative cursor aim and pitch correction for explicit strategic fire. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID)
public final class StrategicFireManager {
    public static final double BOMBARDMENT_RADIUS = 6.0D;
    private static final double ARROW_GRAVITY = 0.05D;
    private static final double ARROW_DRAG = 0.99D;
    private static final Map<UUID, AimPoint> ORDERS = new ConcurrentHashMap<>();

    private StrategicFireManager() { }

    public static void assignExact(AbstractRecruitEntity recruit, Vec3 target) {
        if (recruit != null && target != null) {
            ORDERS.put(recruit.getUUID(), new AimPoint(
                    target, recruit.level().getGameTime() + 20L * 60L * 10L));
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
        Vec3 delta = point.position.subtract(arrow.position());
        double vertical = BallisticArc.verticalWithDrag(
                Math.hypot(delta.x, delta.z), delta.y + 0.08D,
                horizontalSpeed, ARROW_GRAVITY, ARROW_DRAG);
        // Preserve Recruits' native lateral dispersion. Only replace the faulty pitch,
        // recentering the existing distribution on the exact cursor intersection.
        arrow.setDeltaMovement(current.x, vertical, current.z);
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

    private record AimPoint(Vec3 position, long expiresAt) { }
}
