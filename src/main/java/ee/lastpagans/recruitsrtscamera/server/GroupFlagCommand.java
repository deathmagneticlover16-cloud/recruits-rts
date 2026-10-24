package ee.lastpagans.recruitsrtscamera.server;

import com.mojang.brigadier.arguments.StringArgumentType;
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

import java.util.List;
import java.util.UUID;

/** Opens the client painting library for one owned Recruits group. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GroupFlagCommand {
    private GroupFlagCommand() { }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("setflag")
                .executes(context -> openOnlyOwnedGroup(context.getSource().getPlayerOrException()))
                .then(Commands.argument("group", StringArgumentType.greedyString())
                        .suggests((context, builder) -> {
                            ServerPlayer player = context.getSource().getPlayer();
                            if (player == null) return builder.buildFuture();
                            return SharedSuggestionProvider.suggest(
                                    ownedGroups(player).stream().map(RecruitsGroup::getName).distinct(), builder);
                        })
                        .executes(context -> open(context.getSource().getPlayerOrException(),
                                StringArgumentType.getString(context, "group")))));
    }

    private static int openOnlyOwnedGroup(ServerPlayer player) {
        List<RecruitsGroup> groups = ownedGroups(player);
        if (groups.size() == 1) return open(player, groups.get(0));
        player.displayClientMessage(Component.literal(groups.isEmpty()
                ? "You do not own a Recruits group."
                : "Choose a group by name: /setflag <group name>"), false);
        return 0;
    }

    private static int open(ServerPlayer player, String groupNameOrId) {
        String query = groupNameOrId == null ? "" : groupNameOrId.trim();
        UUID requestedId = null;
        try {
            requestedId = UUID.fromString(query);
        } catch (IllegalArgumentException ignored) {
            // Readable group names are the normal path; UUID remains a precise fallback.
        }
        final UUID exactId = requestedId;
        List<RecruitsGroup> matches = ownedGroups(player).stream()
                .filter(group -> exactId != null ? exactId.equals(group.getUUID())
                        : group.getName().equalsIgnoreCase(query))
                .toList();
        if (matches.size() == 1) return open(player, matches.get(0));
        player.displayClientMessage(Component.literal(matches.size() > 1
                ? "More than one owned group is named '" + query + "'. Rename one or use its UUID."
                : "No owned Recruits group is named '" + query + "'. Type /setflag and press Tab."), false);
        return 0;
    }

    private static int open(ServerPlayer player, RecruitsGroup group) {
        RTSCameraNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new OpenGroupFlagEditorPacket(group.getUUID()));
        return 1;
    }

    private static List<RecruitsGroup> ownedGroups(ServerPlayer player) {
        return RecruitsGroupsSaveData.get(player.serverLevel()).getAllGroups().stream()
                .filter(group -> group != null && !group.removed
                        && player.getUUID().equals(group.getPlayerUUID()))
                .toList();
    }
}
