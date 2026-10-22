package ee.lastpagans.recruitsrtscamera.network;

import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class RTSCameraNetwork {
    private static final String PROTOCOL = "rts-camera-157";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RecruitsRTSCameraMod.MOD_ID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private RTSCameraNetwork() {
    }

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, MoveGroupsPacket.class, MoveGroupsPacket::encode, MoveGroupsPacket::decode, MoveGroupsPacket::handle);
        CHANNEL.registerMessage(id++, HastyMoveGroupsPacket.class, HastyMoveGroupsPacket::encode, HastyMoveGroupsPacket::decode, HastyMoveGroupsPacket::handle);
        CHANNEL.registerMessage(id++, SlowMoveGroupsPacket.class, SlowMoveGroupsPacket::encode, SlowMoveGroupsPacket::decode, SlowMoveGroupsPacket::handle);
        CHANNEL.registerMessage(id++, MovementPacePacket.class, MovementPacePacket::encode, MovementPacePacket::decode, MovementPacePacket::handle);
        CHANNEL.registerMessage(id++, TranslateGroupsPacket.class, TranslateGroupsPacket::encode, TranslateGroupsPacket::decode, TranslateGroupsPacket::handle);
        CHANNEL.registerMessage(id++, AttackGroupsPacket.class, AttackGroupsPacket::encode, AttackGroupsPacket::decode, AttackGroupsPacket::handle);
        CHANNEL.registerMessage(id++, SetFormationPacket.class, SetFormationPacket::encode, SetFormationPacket::decode, SetFormationPacket::handle);
        CHANNEL.registerMessage(id++, FrontageGroupsPacket.class, FrontageGroupsPacket::encode, FrontageGroupsPacket::decode, FrontageGroupsPacket::handle);
        CHANNEL.registerMessage(id++, CustomFormationPacket.class, CustomFormationPacket::encode, CustomFormationPacket::decode, CustomFormationPacket::handle);
        CHANNEL.registerMessage(id++, RotateFormationPacket.class, RotateFormationPacket::encode, RotateFormationPacket::decode, RotateFormationPacket::handle);
        CHANNEL.registerMessage(id++, ArmyPresetPacket.class, ArmyPresetPacket::encode, ArmyPresetPacket::decode, ArmyPresetPacket::handle);
        CHANNEL.registerMessage(id++, StrategicFirePacket.class, StrategicFirePacket::encode, StrategicFirePacket::decode, StrategicFirePacket::handle);
        CHANNEL.registerMessage(id++, FireControlPacket.class, FireControlPacket::encode, FireControlPacket::decode, FireControlPacket::handle);
        CHANNEL.registerMessage(id++, RouteGroupsPacket.class, RouteGroupsPacket::encode, RouteGroupsPacket::decode, RouteGroupsPacket::handle);
        CHANNEL.registerMessage(id++, HaltGroupsPacket.class, HaltGroupsPacket::encode, HaltGroupsPacket::decode, HaltGroupsPacket::handle);
        CHANNEL.registerMessage(id++, FormationSpacingPacket.class, FormationSpacingPacket::encode, FormationSpacingPacket::decode, FormationSpacingPacket::handle);
        CHANNEL.registerMessage(id++, PermissionSyncPacket.class, PermissionSyncPacket::encode, PermissionSyncPacket::decode, PermissionSyncPacket::handle);
        CHANNEL.registerMessage(id++, FormationStateSyncPacket.class, FormationStateSyncPacket::encode, FormationStateSyncPacket::decode, FormationStateSyncPacket::handle);
        CHANNEL.registerMessage(id++, FormationRouteSyncPacket.class, FormationRouteSyncPacket::encode, FormationRouteSyncPacket::decode, FormationRouteSyncPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, BodyguardCommandPacket.class, BodyguardCommandPacket::encode, BodyguardCommandPacket::decode, BodyguardCommandPacket::handle);
        CHANNEL.registerMessage(id++, BodyguardSyncPacket.class, BodyguardSyncPacket::encode, BodyguardSyncPacket::decode, BodyguardSyncPacket::handle);
        CHANNEL.registerMessage(id++, BodyguardFollowCommandPacket.class, BodyguardFollowCommandPacket::encode, BodyguardFollowCommandPacket::decode, BodyguardFollowCommandPacket::handle);
        CHANNEL.registerMessage(id++, BodyguardCarryPacket.class, BodyguardCarryPacket::encode, BodyguardCarryPacket::decode, BodyguardCarryPacket::handle);
        CHANNEL.registerMessage(id++, RTSModeStatePacket.class, RTSModeStatePacket::encode, RTSModeStatePacket::decode, RTSModeStatePacket::handle);
        CHANNEL.registerMessage(id++, AdvisorEventPacket.class, AdvisorEventPacket::encode, AdvisorEventPacket::decode, AdvisorEventPacket::handle);
        CHANNEL.registerMessage(id++, BattleStateSyncPacket.class, BattleStateSyncPacket::encode, BattleStateSyncPacket::decode, BattleStateSyncPacket::handle);
        CHANNEL.registerMessage(id++, TacticsControlPacket.class, TacticsControlPacket::encode, TacticsControlPacket::decode, TacticsControlPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, TacticsStateSyncPacket.class, TacticsStateSyncPacket::encode, TacticsStateSyncPacket::decode, TacticsStateSyncPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id, CavalryRumblePacket.class, CavalryRumblePacket::encode,
                CavalryRumblePacket::decode, CavalryRumblePacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
    }
}
