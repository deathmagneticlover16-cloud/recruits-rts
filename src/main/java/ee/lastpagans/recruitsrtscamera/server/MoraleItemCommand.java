package ee.lastpagans.recruitsrtscamera.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.persistence.RTSMoraleItemSavedData;
import ee.lastpagans.recruitsrtscamera.network.MoraleItemsSyncPacket;
import ee.lastpagans.recruitsrtscamera.network.RTSCameraNetwork;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.stream.Collectors;

/** Operator-only live management for persistent morale items. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MoraleItemCommand {
    private MoraleItemCommand() { }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("recruitsmoraleitem")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("add").then(itemArgument()
                        .executes(context -> change(context.getSource(),
                                StringArgumentType.getString(context, "item"), true))))
                .then(Commands.literal("remove").then(itemArgument()
                        .executes(context -> change(context.getSource(),
                                StringArgumentType.getString(context, "item"), false))))
                .then(Commands.literal("list").executes(context -> list(context.getSource()))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> itemArgument() {
        return Commands.argument("item", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                        BuiltInRegistries.ITEM.keySet(), builder));
    }

    private static int change(CommandSourceStack source, String raw, boolean add) {
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)
                || BuiltInRegistries.ITEM.get(id) == Items.AIR) {
            source.sendFailure(Component.literal("Unknown item: " + raw));
            return 0;
        }
        RTSMoraleItemSavedData data = RTSMoraleItemSavedData.get(source.getLevel());
        boolean changed = add ? data.add(id) : data.remove(id);
        if (changed) RTSCameraNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), MoraleItemsSyncPacket.of(
                data.snapshot().stream().map(ResourceLocation::toString).toList()));
        source.sendSuccess(() -> Component.literal((add ? "Added " : "Removed ") + id
                + (add ? " as a morale item." : " from runtime morale items.")
                + (changed ? "" : " No change was needed.")), true);
        return changed ? 1 : 0;
    }

    private static int list(CommandSourceStack source) {
        String items = RTSMoraleItemSavedData.get(source.getLevel()).snapshot().stream()
                .map(ResourceLocation::toString).collect(Collectors.joining(", "));
        source.sendSuccess(() -> Component.literal(items.isEmpty()
                ? "No runtime morale items. Datapack tag #rts:morale_items still applies."
                : "Runtime morale items: " + items), false);
        return 1;
    }
}
