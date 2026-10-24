package ee.lastpagans.recruitsrtscamera.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Client cache and optional Immersive Paintings entry point for group flags. */
public final class GroupFlagClientState {
    private static final Map<UUID, ResourceLocation> FLAGS = new LinkedHashMap<>();

    private GroupFlagClientState() { }

    public static void clear() {
        FLAGS.clear();
    }

    public static void apply(Map<UUID, String> flags, boolean replace) {
        if (replace) FLAGS.clear();
        for (Map.Entry<UUID, String> entry : flags.entrySet()) {
            ResourceLocation parsed = ResourceLocation.tryParse(entry.getValue());
            if (parsed == null) FLAGS.remove(entry.getKey());
            else FLAGS.put(entry.getKey(), parsed);
        }
    }

    public static FlagTexture texture(UUID groupId) {
        ResourceLocation selected = FLAGS.get(groupId);
        if (selected == null || !ModList.get().isLoaded("immersive_paintings")) return null;
        try {
            return ImmersivePaintingsFlagScreen.texture(selected);
        } catch (LinkageError | RuntimeException ignored) {
            return null;
        }
    }

    public static void open(UUID groupId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ModList.get().isLoaded("immersive_paintings")) {
            if (minecraft.player != null) minecraft.player.displayClientMessage(
                    Component.literal("Immersive Paintings is required to choose a group flag."), false);
            return;
        }
        try {
            minecraft.setScreen(new ImmersivePaintingsFlagScreen(groupId));
        } catch (LinkageError | RuntimeException error) {
            if (minecraft.player != null) minecraft.player.displayClientMessage(
                    Component.literal("This Immersive Paintings version is not compatible with the flag browser."), false);
        }
    }

    public record FlagTexture(ResourceLocation location, double aspectRatio) { }
}
