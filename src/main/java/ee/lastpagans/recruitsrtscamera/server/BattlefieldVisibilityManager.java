package ee.lastpagans.recruitsrtscamera.server;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.network.EnemyVisibilitySyncPacket;
import ee.lastpagans.recruitsrtscamera.network.RTSCameraNetwork;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitRuntimeRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Samples enemy formations at 4 Hz and sends only the authoritative visibility decision. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BattlefieldVisibilityManager {
    private static int ticks;
    private static final Map<UUID, Map<UUID, Boolean>> LAST_SENT = new HashMap<>();
    private BattlefieldVisibilityManager() { }

    public static void reset(ServerPlayer player) {
        if (player != null) LAST_SENT.remove(player.getUUID());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++ticks % 5 != 0) return;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!BodyguardManager.isRtsActive(player)) continue;
            Map<UUID, Boolean> groups = new HashMap<>();
            for (AbstractRecruitEntity enemy : RecruitRuntimeRegistry.loaded(player.serverLevel())) {
                UUID groupId = enemy.getGroup();
                if (groupId == null || player.getUUID().equals(enemy.getOwnerUUID()) || player.isAlliedTo(enemy)) continue;
                if (groups.getOrDefault(groupId, false)) continue;
                groups.put(groupId, RTSVision.canSee(player, enemy));
            }
            Map<UUID, Boolean> previous = LAST_SENT.put(player.getUUID(), Map.copyOf(groups));
            if (!groups.equals(previous)) {
                RTSCameraNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new EnemyVisibilitySyncPacket(groups));
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SENT.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onStop(ServerStoppedEvent event) {
        LAST_SENT.clear();
        ticks = 0;
    }
}
