package ee.lastpagans.recruitsrtscamera.client;

import ee.lastpagans.recruitsrtscamera.registry.RTSTags;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/** Client mirror used only to identify live-added standard bearers for aura previews. */
public final class ClientMoraleItemState {
    private static final Set<ResourceLocation> RUNTIME_ITEMS = new LinkedHashSet<>();

    private ClientMoraleItemState() { }

    public static void apply(Collection<String> items) {
        RUNTIME_ITEMS.clear();
        if (items == null) return;
        for (String raw : items) {
            ResourceLocation id = ResourceLocation.tryParse(raw);
            if (id != null) RUNTIME_ITEMS.add(id);
        }
    }

    public static boolean matches(ItemStack stack) {
        return stack != null && !stack.isEmpty() && (stack.is(RTSTags.MORALE_ITEMS)
                || RUNTIME_ITEMS.contains(BuiltInRegistries.ITEM.getKey(stack.getItem())));
    }

    public static void clear() {
        RUNTIME_ITEMS.clear();
    }
}
