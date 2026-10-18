package ee.lastpagans.recruitsrtscamera.formation;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Player-specific native line depth, using Better Formations' old key for migration. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class NativeFormationPreferences {
    private static final String PERSISTED = "PlayerPersisted";
    private static final String LINE_RANKS = "BetterFormationsLineRanks";

    private NativeFormationPreferences() {
    }

    public static int lineRanks(Player player) {
        CompoundTag persisted = player.getPersistentData().getCompound(PERSISTED);
        return persisted.contains(LINE_RANKS) ? Mth.clamp(persisted.getInt(LINE_RANKS), 1, 20) : 1;
    }

    public static void setLineRanks(Player player, int ranks) {
        CompoundTag root = player.getPersistentData();
        CompoundTag persisted = root.getCompound(PERSISTED);
        persisted.putInt(LINE_RANKS, Mth.clamp(ranks, 1, 20));
        root.put(PERSISTED, persisted);
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("recruitsformation")
                .then(Commands.literal("ranks")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 20))
                                .executes(context -> {
                                    int ranks = IntegerArgumentType.getInteger(context, "count");
                                    setLineRanks(context.getSource().getPlayerOrException(), ranks);
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("Line formations now use " + ranks + " rank(s)."),
                                            false);
                                    return ranks;
                                }))));
    }
}
