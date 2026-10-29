package ee.lastpagans.recruitsrtscamera.movement;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.config.RTSServerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Formation traffic gradually wears supported natural ground into ordinary dirt paths. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TroopRoadErosionManager {
    private static final int MAX_TRACKED_BLOCKS_PER_LEVEL = 32_768;
    private static final Map<ServerLevel, LevelTraffic> LEVELS = new WeakHashMap<>();

    private TroopRoadErosionManager() { }

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof AbstractRecruitEntity recruit)
                || !(recruit.level() instanceof ServerLevel level)
                || recruit.tickCount % 5 != Math.floorMod(recruit.getId(), 5)
                || !RTSServerConfig.TROOP_ROAD_EROSION.get()
                || !level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) return;

        Entity mover = recruit.getVehicle() != null ? recruit.getVehicle() : recruit;
        LevelTraffic traffic = LEVELS.computeIfAbsent(level, ignored -> new LevelTraffic());
        Sample previous = traffic.members.put(recruit.getUUID(), new Sample(mover.getX(), mover.getZ(), level.getGameTime()));
        if (previous == null || !mover.onGround()) return;
        double dx = mover.getX() - previous.x;
        double dz = mover.getZ() - previous.z;
        if (dx * dx + dz * dz < 0.04D * 0.04D) return;

        BlockPos ground = BlockPos.containing(mover.getX(), mover.getBoundingBox().minY - 0.08D, mover.getZ());
        BlockState state = level.getBlockState(ground);
        if (!erodible(state) || !level.getBlockState(ground.above()).isAir()) return;
        int weight = mover == recruit ? 1 : 3;
        int accumulated = traffic.blocks.merge(ground.asLong(), weight, Integer::sum);
        if (accumulated >= RTSServerConfig.TROOP_FOOTFALLS_TO_PATH.get()) {
            level.setBlock(ground, Blocks.DIRT_PATH.defaultBlockState(), Block.UPDATE_ALL);
            traffic.blocks.remove(ground.asLong());
        }
        traffic.trim(level.getGameTime());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LEVELS.clear();
    }

    private static boolean erodible(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM) || state.is(Blocks.ROOTED_DIRT);
    }

    private record Sample(double x, double z, long tick) { }

    private static final class LevelTraffic {
        final Map<UUID, Sample> members = new LinkedHashMap<>();
        final LinkedHashMap<Long, Integer> blocks = new LinkedHashMap<>();
        long lastCleanup;

        void trim(long now) {
            if (blocks.size() > MAX_TRACKED_BLOCKS_PER_LEVEL) {
                Iterator<Long> oldest = blocks.keySet().iterator();
                while (blocks.size() > MAX_TRACKED_BLOCKS_PER_LEVEL && oldest.hasNext()) {
                    oldest.next();
                    oldest.remove();
                }
            }
            if (now - lastCleanup < 1200L) return;
            lastCleanup = now;
            members.entrySet().removeIf(entry -> now - entry.getValue().tick > 1200L);
        }
    }
}
