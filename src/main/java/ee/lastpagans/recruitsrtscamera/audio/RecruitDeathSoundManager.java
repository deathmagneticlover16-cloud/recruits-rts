package ee.lastpagans.recruitsrtscamera.audio;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.registry.RTSSounds;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import java.util.Comparator;
import java.util.PriorityQueue;

/** Server-authoritative Recruit death voices with spatial anti-chorus staggering. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RecruitDeathSoundManager {
    private static final int MAX_DELAY_TICKS = 1;
    private static final double CLUSTER_RADIUS_SQR = 12.0D * 12.0D;
    private static final String DEATH_VOICE_FIRED = "RecruitsRTSDeathVoiceFired";
    private static final PriorityQueue<ScheduledSound> QUEUE = new PriorityQueue<>(
            Comparator.comparingLong(ScheduledSound::dueTick));

    private RecruitDeathSoundManager() { }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = false)
    public static void onRecruitDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof AbstractRecruitEntity recruit)
                || !(recruit.level() instanceof ServerLevel level)
                || recruit.getPersistentData().getBoolean(DEATH_VOICE_FIRED)) return;
        recruit.getPersistentData().putBoolean(DEATH_VOICE_FIRED, true);

        MinecraftServer server = level.getServer();
        long now = server.getTickCount();
        float pitch = 0.92F + level.random.nextFloat() * 0.16F;

        // Corpsebound converts the death into a ragdoll later in the same Forge event.
        // Send the positional sound packet now so it is ordered before the corpse packet.
        // This is deliberately only a mod-id check: RTS never links Corpsebound or Cruor.
        if (ModList.get().isLoaded("corpsebound")) {
            play(level, recruit.getX(), recruit.getY(), recruit.getZ(), pitch);
            return;
        }

        int delay = leastCrowdedDelay(level.dimension(), recruit.getX(), recruit.getY(), recruit.getZ(), now,
                level.random.nextInt(MAX_DELAY_TICKS + 1));
        QUEUE.add(new ScheduledSound(level.dimension(), now + delay,
                recruit.getX(), recruit.getY(), recruit.getZ(), pitch));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || QUEUE.isEmpty()) return;
        long now = event.getServer().getTickCount();
        while (!QUEUE.isEmpty() && QUEUE.peek().dueTick <= now) {
            ScheduledSound sound = QUEUE.poll();
            ServerLevel level = event.getServer().getLevel(sound.dimension);
            if (level == null) continue;
            play(level, sound.x, sound.y, sound.z, sound.pitch);
        }
    }

    private static void play(ServerLevel level, double x, double y, double z, float pitch) {
        level.playSound(null, x, y, z, RTSSounds.RECRUIT_DEATH.get(),
                SoundSource.NEUTRAL, 1.0F, pitch);
    }

    private static int leastCrowdedDelay(ResourceKey<Level> dimension, double x, double y, double z,
                                         long now, int randomStart) {
        int[] occupancy = new int[MAX_DELAY_TICKS + 1];
        for (ScheduledSound queued : QUEUE) {
            long offset = queued.dueTick - now;
            if (!dimension.equals(queued.dimension) || offset < 0 || offset > MAX_DELAY_TICKS) continue;
            double dx = queued.x - x;
            double dy = queued.y - y;
            double dz = queued.z - z;
            if (dx * dx + dy * dy + dz * dz <= CLUSTER_RADIUS_SQR) occupancy[(int) offset]++;
        }
        int best = randomStart;
        for (int step = 1; step <= MAX_DELAY_TICKS; step++) {
            int candidate = (randomStart + step) % (MAX_DELAY_TICKS + 1);
            if (occupancy[candidate] < occupancy[best]) best = candidate;
        }
        return best;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        QUEUE.clear();
    }

    private record ScheduledSound(ResourceKey<Level> dimension, long dueTick,
                                  double x, double y, double z, float pitch) { }
}
