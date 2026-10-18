package ee.lastpagans.recruitsrtscamera.server;

import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.compat.SiegeWeaponsCompat;
import ee.lastpagans.recruitsrtscamera.compat.SmallShipsCompat;
import ee.lastpagans.recruitsrtscamera.battle.BattleMechanicsManager;
import ee.lastpagans.recruitsrtscamera.config.RTSServerConfig;
import ee.lastpagans.recruitsrtscamera.network.RouteManager;
import ee.lastpagans.recruitsrtscamera.network.PermissionSyncPacket;
import ee.lastpagans.recruitsrtscamera.network.RTSCameraNetwork;
import ee.lastpagans.recruitsrtscamera.network.FormationStateSyncPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RTSServerEvents {
    private RTSServerEvents() {
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sync(player);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sync(player);
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        ee.lastpagans.recruitsrtscamera.combat.RTSGroupAttackManager.cancelPlayer(event.getEntity().getUUID());
        if (event.getEntity() instanceof ServerPlayer player) {
            sync(player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ee.lastpagans.recruitsrtscamera.combat.RTSGroupAttackManager.cancelPlayer(event.getEntity().getUUID());
        ServerPacketGuard.clear(event.getEntity().getUUID());
        RouteManager.clear(event.getEntity().getUUID());
        BattleMechanicsManager.clearPlayer(event.getEntity().getUUID());
        if (event.getEntity() instanceof ServerPlayer player) BodyguardManager.logout(player);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            RouteManager.tick(ServerLifecycleHooks.getCurrentServer());
        }
    }

    public static void sync(ServerPlayer player) {
        RTSCameraNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new PermissionSyncPacket(
                        RTSPermissions.isAllowed(player),
                        RTSPermissions.whitelistMode(),
                        SiegeWeaponsCompat.isAvailable(),
                        SmallShipsCompat.isAvailable(),
                        RTSServerConfig.FOG_OF_WAR.get(),
                        RTSServerConfig.FOG_PLAYER_VISION_RADIUS.get(),
                        RTSServerConfig.FOG_RECRUIT_VISION_RADIUS.get(),
                        RTSServerConfig.FOG_CAVALRY_VISION_RADIUS.get(),
                        RTSServerConfig.FOG_SHIP_VISION_RADIUS.get()
                )
        );
        RTSCameraNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                FormationStateSyncPacket.forPlayer(player)
        );
        BodyguardManager.sync(player);
        RTSCameraNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                ee.lastpagans.recruitsrtscamera.network.TacticsStateSyncPacket.forPlayer(player));
    }
}
