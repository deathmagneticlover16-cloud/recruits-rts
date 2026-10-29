package ee.lastpagans.recruitsrtscamera.effects;

import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Native black-powder discharge and impact smoke. This replaces the former standalone
 * addon: projectile events create merged, expanding server-side clouds with no hard
 * dependency on Musket Mod or Colonial Cannons.
 */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BlackPowderSmokeManager {
    private static final String MUSKET_BULLET = "ewewukek.musketmod.BulletEntity";
    private static final String CANNONBALL = "com.camdenscottc.colonialcannons.projectile.CannonballEntity";
    private static final double SCALE = 0.85D;
    private static final int MAX_CLOUDS = 512;
    private static final Map<String, ShotStamp> RECENT_SHOTS = new HashMap<>();
    private static final Map<java.util.UUID, Long> RECENT_IMPACTS = new HashMap<>();
    private static final List<Cloud> CLOUDS = new ArrayList<>();
    private static long lastCleanupTick = Long.MIN_VALUE;

    private BlackPowderSmokeManager() { }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity projectile = event.getEntity();
        String type = projectile.getClass().getName();
        if (MUSKET_BULLET.equals(type)) musket(level, projectile);
        else if (CANNONBALL.equals(type)) cannon(level, projectile);
    }

    @SubscribeEvent
    public static void onImpact(ProjectileImpactEvent event) {
        Entity projectile = event.getProjectile();
        if (!(projectile.level() instanceof ServerLevel level)
                || !CANNONBALL.equals(projectile.getClass().getName())) return;
        long now = level.getGameTime();
        Long previous = RECENT_IMPACTS.put(projectile.getUUID(), now);
        if (previous != null && now - previous < 5L) return;
        cleanup(now);
        Vec3 position = projectile.position();
        Vec3 direction = horizontalDirection(projectile.getDeltaMovement());
        boolean explosive = invokeBoolean(projectile, "isExplosive");
        if (explosive) {
            initial(level, position.add(0.0D, 0.35D, 0.0D), 58,
                    1.70D * 1.15D, 1.70D * 0.65D, 1.70D * 1.15D, 0.020D);
            for (int i = 0; i < 4; i++) {
                double angle = Math.PI * 2.0D * i / 4.0D;
                Vec3 velocity = new Vec3(Math.cos(angle) * 0.01D,
                        0.005D * (0.8D + i * 0.18D), Math.sin(angle) * 0.01D)
                        .add(direction.scale(0.006D));
                residual(level, position.add(Math.cos(angle) * 0.32D,
                                0.35D + (i % 2) * 0.16D, Math.sin(angle) * 0.32D),
                        velocity, 20 * 95, 7, 2,
                        1.70D * 0.62D, 1.70D * 0.34D, 1.70D * 0.62D,
                        0.088D, 1.6D, false, Kind.CANNON);
            }
        } else {
            initial(level, position.add(0.0D, 0.18D, 0.0D), 22,
                    0.95D, 0.42D, 0.95D, 0.016D);
            residual(level, position.add(0.0D, 0.25D, 0.0D),
                    direction.scale(0.006D).add(0.0D, 0.004D, 0.0D),
                    20 * 38, 9, 1, 0.75D, 0.32D, 0.75D,
                    0.060D, 1.6D, true, Kind.CANNON);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || CLOUDS.isEmpty()) return;
        for (int i = CLOUDS.size() - 1; i >= 0; i--) {
            Cloud cloud = CLOUDS.get(i);
            if (++cloud.age >= cloud.life || cloud.level.getServer() == null) {
                CLOUDS.remove(i);
                continue;
            }
            cloud.position = cloud.position.add(cloud.velocity);
            cloud.velocity = new Vec3(cloud.velocity.x * 0.992D,
                    cloud.velocity.y * 0.998D + (cloud.kind == Kind.CANNON ? 0.000004D : 0.000003D),
                    cloud.velocity.z * 0.992D);
            double progress = cloud.age / (double) cloud.life;
            double expansion = cloud.expansionPerTick * cloud.age;
            if (--cloud.untilPulse > 0) continue;
            int minimumInterval = 2;
            double densityRate = 1.0D + Math.min(4.0D, cloud.density * 0.32D);
            cloud.untilPulse = Math.max(minimumInterval, (int) Math.round(
                    cloud.interval * (1.0D + progress * progress * 2.25D) / densityRate));
            int count = Math.min(24, cloud.particles + cloud.density / 2);
            if (progress > 0.58D) {
                double remaining = Math.max(0.0D, 1.0D - (progress - 0.58D) / 0.42D);
                count = Math.max(1, (int) Math.round(count * (0.45D + 0.55D * remaining)));
            }
            lingering(cloud.level, cloud.position, count,
                    cloud.spreadX + expansion, cloud.spreadY + expansion * 0.46D,
                    cloud.spreadZ + expansion, 0.0035D + (1.0D - progress) * 0.0035D);
        }
    }

    @SubscribeEvent
    public static void onStop(ServerStoppedEvent event) {
        CLOUDS.clear();
        RECENT_SHOTS.clear();
        RECENT_IMPACTS.clear();
        lastCleanupTick = Long.MIN_VALUE;
    }

    private static void musket(ServerLevel level, Entity projectile) {
        Vec3 direction = direction(projectile.getDeltaMovement());
        Vec3 muzzle = projectile.position().add(direction.scale(1.0D));
        long now = level.getGameTime();
        String key = level.dimension().location() + ":" + Math.round(projectile.getX() * 10.0D)
                + ":" + Math.round(projectile.getY() * 10.0D) + ":" + Math.round(projectile.getZ() * 10.0D);
        ShotStamp stamp = RECENT_SHOTS.get(key);
        if (stamp != null && now - stamp.tick <= 2L) {
            stamp.tick = now;
            if (++stamp.pellets == 2) {
                Vec3 bonus = muzzle.add(direction.scale(0.34D));
                initial(level, bonus, 12, 0.754D, 0.360D, 0.754D, 0.018D);
                residual(level, bonus.add(0.0D, 0.08D, 0.0D),
                        direction.scale(0.0207D).add(0.0D, 0.0045D, 0.0D),
                        20 * 30, 9, 2, 0.418D, 0.197D, 0.418D,
                        0.075D, 0.9D, true, Kind.MUSKET);
            }
            return;
        }
        RECENT_SHOTS.put(key, new ShotStamp(now));
        initial(level, muzzle, 18, 0.58D, 0.302D, 0.58D, 0.020D);
        residual(level, muzzle.add(0.0D, 0.06D, 0.0D),
                direction.scale(0.018D).add(0.0D, 0.0045D, 0.0D),
                20 * 24, 9, 1, 0.360D, 0.174D, 0.360D,
                0.055D, 0.9D, true, Kind.MUSKET);
        cleanup(now);
    }

    private static void cannon(ServerLevel level, Entity projectile) {
        Vec3 direction = direction(projectile.getDeltaMovement());
        Vec3 origin = projectile.position().add(direction.scale(0.9D));
        initial(level, origin, 52, 1.35D, 0.81D, 1.35D, 0.025D);
        Vec3 side = new Vec3(-direction.z, 0.0D, direction.x).normalize();
        for (int i = 0; i < 3; i++) {
            double lane = i - 1.0D;
            residual(level, origin.add(side.scale(lane * 0.30D)).add(0.0D, Math.abs(lane) * 0.08D, 0.0D),
                    direction.scale(0.03D * (1.0D - Math.min(0.3D, Math.abs(lane) * 0.1D)))
                            .add(side.scale(lane * 0.0025D)).add(0.0D, 0.005D * (1.0D + Math.abs(lane) * 0.12D), 0.0D),
                    20 * 75, 7, 2, 0.81D, 0.405D, 0.81D,
                    0.080D, 1.6D, false, Kind.CANNON);
        }
    }

    private static void residual(ServerLevel level, Vec3 position, Vec3 velocity, int life,
                                 int interval, int particles, double spreadX, double spreadY,
                                 double spreadZ, double expansionPerSecond, double mergeRadius,
                                 boolean merge, Kind kind) {
        if (life <= 0 || particles <= 0) return;
        Cloud closest = null;
        double best = mergeRadius * mergeRadius;
        if (merge) for (Cloud cloud : CLOUDS) {
            double distance = cloud.position.distanceToSqr(position);
            if (cloud.level == level && cloud.kind == kind && cloud.age <= 30 && distance <= best) {
                closest = cloud;
                best = distance;
            }
        }
        if (closest != null) {
            closest.density = Math.min(32, closest.density + 1);
            int maximumLife = kind == Kind.MUSKET ? 20 * 60 : 20 * 120;
            closest.life = Math.min(maximumLife,
                    Math.max(closest.life, closest.age + life / 2 + closest.density * 8));
            closest.particles = Math.min(kind == Kind.MUSKET ? 18 : 24,
                    closest.particles + Math.max(1, particles));
            closest.spreadX = Math.max(closest.spreadX, spreadX * SCALE);
            closest.spreadY = Math.max(closest.spreadY, spreadY * SCALE);
            closest.spreadZ = Math.max(closest.spreadZ, spreadZ * SCALE);
            closest.position = closest.position.add(position).scale(0.5D);
            closest.velocity = closest.velocity.add(velocity).scale(0.5D);
            closest.untilPulse = Math.min(closest.untilPulse, 2);
            return;
        }
        if (CLOUDS.size() >= MAX_CLOUDS) CLOUDS.remove(0);
        CLOUDS.add(new Cloud(level, position, velocity, life, interval, particles,
                spreadX * SCALE, spreadY * SCALE, spreadZ * SCALE,
                expansionPerSecond * SCALE / 20.0D, kind));
    }

    private static void initial(ServerLevel level, Vec3 position, int count,
                                double spreadX, double spreadY, double spreadZ, double speed) {
        send(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, position, count,
                spreadX * SCALE, spreadY * SCALE, spreadZ * SCALE, speed);
    }

    private static void lingering(ServerLevel level, Vec3 position, int count,
                                  double spreadX, double spreadY, double spreadZ, double speed) {
        send(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, position, count,
                spreadX, spreadY, spreadZ, speed);
    }

    private static void send(ServerLevel level, ParticleOptions particle, Vec3 position, int count,
                             double spreadX, double spreadY, double spreadZ, double speed) {
        if (particle != null && count > 0) level.sendParticles(particle, position.x, position.y, position.z,
                count, spreadX, spreadY, spreadZ, speed);
    }

    private static boolean invokeBoolean(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            return Boolean.TRUE.equals(method.invoke(target));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static void cleanup(long now) {
        if (lastCleanupTick != Long.MIN_VALUE && now - lastCleanupTick < 60L) return;
        lastCleanupTick = now;
        RECENT_SHOTS.entrySet().removeIf(entry -> now - entry.getValue().tick > 20L);
        RECENT_IMPACTS.entrySet().removeIf(entry -> now - entry.getValue() > 40L);
    }

    private static Vec3 direction(Vec3 velocity) {
        return velocity.lengthSqr() < 0.001D ? new Vec3(0.0D, 0.0D, 1.0D) : velocity.normalize();
    }

    private static Vec3 horizontalDirection(Vec3 velocity) {
        Vec3 horizontal = new Vec3(velocity.x, 0.0D, velocity.z);
        return horizontal.lengthSqr() < 0.001D ? Vec3.ZERO : horizontal.normalize();
    }

    private enum Kind { MUSKET, CANNON }

    private static final class ShotStamp {
        long tick;
        int pellets = 1;
        ShotStamp(long tick) { this.tick = tick; }
    }

    private static final class Cloud {
        final ServerLevel level;
        final int interval;
        final Kind kind;
        Vec3 position;
        Vec3 velocity;
        int age;
        int life;
        int untilPulse = 2;
        int particles;
        double spreadX;
        double spreadY;
        double spreadZ;
        double expansionPerTick;
        int density = 1;

        Cloud(ServerLevel level, Vec3 position, Vec3 velocity, int life, int interval, int particles,
              double spreadX, double spreadY, double spreadZ, double expansionPerTick, Kind kind) {
            this.level = level;
            this.position = position;
            this.velocity = velocity;
            this.life = life;
            this.interval = Math.max(1, interval);
            this.particles = particles;
            this.spreadX = spreadX;
            this.spreadY = spreadY;
            this.spreadZ = spreadZ;
            this.expansionPerTick = expansionPerTick;
            this.kind = kind;
        }
    }
}
