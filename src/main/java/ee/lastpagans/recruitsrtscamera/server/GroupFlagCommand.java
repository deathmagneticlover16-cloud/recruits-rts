package ee.lastpagans.recruitsrtscamera.server;

import net.minecraft.commands.arguments.UuidArgument;
import com.talhanation.recruits.world.RecruitsGroup;
import com.talhanation.recruits.world.RecruitsGroupsSaveData;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.network.OpenGroupFlagEditorPacket;
import ee.lastpagans.recruitsrtscamera.network.RTSCameraNetwork;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;

/** Opens the client painting library for one owned Recruits group. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GroupFlagCommand {
    private GroupFlagCommand() { }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("setflag")
                .then(Commands.argument("group", UuidArgument.uuid())
                        .suggests((context, builder) -> {
                            ServerPlayer player = context.getSource().getPlayer();
                            if (player == null) return builder.buildFuture();
                            return SharedSuggestionProvider.suggest(
                                    RecruitsGroupsSaveData.get(player.serverLevel()).getAllGroups().stream()
                                            .filter(group -> group != null && !group.removed
                                                    && player.getUUID().equals(group.getPlayerUUID()))
                                            .map(group -> group.getUUID().toString()), builder);
                        })
                        .executes(context -> open(context.getSource().getPlayerOrException(),
                                UuidArgument.getUuid(context, "group")))));
    }

    private static int open(ServerPlayer player, UUID groupId) {
        for (RecruitsGroup group : RecruitsGroupsSaveData.get(player.serverLevel()).getAllGroups()) {
            if (group != null && !group.removed && groupId.equals(group.getUUID())
                    && player.getUUID().equals(group.getPlayerUUID())) {
                RTSCameraNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new OpenGroupFlagEditorPacket(groupId));
                return 1;
            }
        }
        player.displayClientMessage(Component.literal("That Recruits group is not owned by you."), false);
        return 0;
    }
}
