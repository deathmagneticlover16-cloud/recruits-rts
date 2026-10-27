package ee.lastpagans.recruitsrtscamera.combat;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.config.RTSServerConfig;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitRuntimeRegistry;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitRuntimeMetrics;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitSpatialIndex;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Event-driven formation threat memory. Projectile impacts wake the entire group immediately. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ProjectileAwarenessManager {
    private static final long ALERT_TICKS = 20L * 4L;
    private static final Map<UUID, Threat> THREATS = new HashMap<>();

    private ProjectileAwarenessManager() {
    }

    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        if (!RTSServerConfig.PROJECTILE_AWARENESS.get() || event.getEntity().level().isClientSide
                || !(event.getEntity() instanceof AbstractRecruitEntity recruit)
                || recruit.getGroup() == null || !(event.getSource().getDirectEntity() instanceof Projectile projectile)) {
            return;
        }
        remember(recruit, projectile);
    }

    @SubscribeEvent
    public static void onProjectileSpawn(EntityJoinLevelEvent event) {
        if (!RTSServerConfig.PROJECTILE_AWARENESS.get() || event.getLevel().isClientSide
                || !(event.getEntity() instanceof Projectile projectile)) return;
        Entity owner = projectile.getOwner();
        Vec3 velocity = projectile.getDeltaMovement();
        if (velocity.lengthSqr() < 1.0E-4D) return;
        Vec3 start = projectile.position();
        Vec3 end = start.add(velocity.scale(24.0D));
        AABB corridor = new AABB(start, end).inflate(3.0D);
        Map<UUID, AbstractRecruitEntity> threatenedGroups = new HashMap<>();
        if (!(projectile.level() instanceof ServerLevel level)) return;
        for (AbstractRecruitEntity recruit : RecruitSpatialIndex.within(level, corridor)) {
            if (!recruit.isAlive() || recruit.getGroup() == null || recruit == owner
                    || (owner != null && recruit.isAlliedTo(owner))) continue;
            Vec3 delta = recruit.getEyePosition().subtract(start);
            double time = Math.max(0.0D, Math.min(24.0D, delta.dot(velocity) / velocity.lengthSqr()));
            if (delta.subtract(velocity.scale(time)).lengthSqr() <= 9.0D) {
                threatenedGroups.putIfAbsent(recruit.getGroup(), recruit);
            }
        }
        for (AbstractRecruitEntity recruit : threatenedGroups.values()) remember(recruit, projectile);
    }

    public static boolean isUnderFire(AbstractRecruitEntity recruit) {
        return threat(recruit) != null;
    }

    public static Threat threat(AbstractRecruitEntity recruit) {
        if (!RTSServerConfig.PROJECTILE_AWARENESS.get() || recruit == null || recruit.getGroup() == null) return null;
        Threat threat = THREATS.get(recruit.getGroup());
        long now = recruit.level().getGameTime();
        if (threat != null && threat.expires >= now) return threat;
        if (threat != null) THREATS.remove(recruit.getGroup());
        return null;
    }

    private static void remember(AbstractRecruitEntity recruit, Projectile projectile) {
        Vec3 velocity = projectile.getDeltaMovement();
        Vec3 source = projectile.getOwner() == null ? projectile.position().subtract(velocity.scale(8.0D))
                : projectile.getOwner().position();
        boolean overhead = velocity.y < -0.08D
                && -velocity.y > Math.hypot(velocity.x, velocity.z) * 0.35D;
        long now = recruit.level().getGameTime();
        THREATS.put(recruit.getGroup(), new Threat(source, velocity, overhead, now + ALERT_TICKS));
        RecruitRuntimeMetrics.PROJECTILE_ALERTS.increment();
        RecruitRuntimeRegistry.wakeUnderFire(recruit, projectile.getOwner());
        prune(now);
    }

    private static void prune(long now) {
        if (THREATS.size() > 256) THREATS.entrySet().removeIf(entry -> entry.getValue().expires < now);
    }

    @SubscribeEvent
    public static void onStop(ServerStoppedEvent event) {
        THREATS.clear();
    }

    public record Threat(Vec3 source, Vec3 velocity, boolean overhead, long expires) {}
}
