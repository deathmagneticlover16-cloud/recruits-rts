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
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Authoritative cursor aim and pitch correction for explicit strategic fire. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID)
public final class StrategicFireManager {
    public static final double BOMBARDMENT_RADIUS = 3.25D;
    private static final double ARROW_GRAVITY = 0.05D;
    private static final double ARROW_DRAG = 0.99D;
    private static final Map<UUID, AimPoint> ORDERS = new ConcurrentHashMap<>();

    private StrategicFireManager() { }

    /** Stable, center-weighted disk for one bombardment order; never collapses into a line or rim. */
    public static Map<UUID, Vec3> assignArea(
            List<AbstractRecruitEntity> recruits, Vec3 center, long orderSalt
    ) {
        List<AbstractRecruitEntity> ordered = recruits.stream()
                .filter(recruit -> recruit != null && recruit.isAlive() && !recruit.isRemoved())
                .sorted(Comparator.comparing(AbstractRecruitEntity::getUUID))
                .toList();
        Map<UUID, Vec3> result = new LinkedHashMap<>();
        for (int index = 0; index < ordered.size(); index++) {
            AbstractRecruitEntity recruit = ordered.get(index);
            Vec3 point = index == 0 ? center : diskPoint(center, recruit.getUUID(), orderSalt);
            assignExact(recruit, point);
            result.put(recruit.getUUID(), point);
        }
        return result;
    }

    private static Vec3 diskPoint(Vec3 center, UUID recruitId, long orderSalt) {
        long first = mix(recruitId.getMostSignificantBits() ^ orderSalt);
        long second = mix(recruitId.getLeastSignificantBits() + orderSalt * 31L);
        double radial = unit(first) * BOMBARDMENT_RADIUS;
        double angle = unit(second) * Math.PI * 2.0D;
        return center.add(Math.cos(angle) * radial, 0.0D, Math.sin(angle) * radial);
    }

    private static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }

    private static double unit(long value) {
        return (value >>> 11) * 0x1.0p-53;
    }

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
