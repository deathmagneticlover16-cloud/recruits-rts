package ee.lastpagans.recruitsrtscamera.registry;

import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

public final class RTSTags {
    public static final TagKey<Item> THROWABLE_WEAPONS = TagKey.create(
            Registries.ITEM,
            new ResourceLocation(RecruitsRTSCameraMod.MOD_ID, "throwable_weapons")
    );

    /** Datapack-facing standards/eagles/banners that steady their recruit group. */
    public static final TagKey<Item> MORALE_ITEMS = TagKey.create(
            Registries.ITEM,
            new ResourceLocation("rts", "morale_items")
    );

    /** Optional datapack overrides for the inventory encumbrance classifier. */
    public static final TagKey<Item> ENCUMBRANCE_WEIGHTLESS = weightTag("weightless");
    public static final TagKey<Item> ENCUMBRANCE_LIGHT = weightTag("light");
    public static final TagKey<Item> ENCUMBRANCE_HEAVY = weightTag("heavy");
    public static final TagKey<Item> ENCUMBRANCE_VERY_HEAVY = weightTag("very_heavy");

    private static TagKey<Item> weightTag(String name) {
        return TagKey.create(Registries.ITEM,
                new ResourceLocation(RecruitsRTSCameraMod.MOD_ID, "encumbrance/" + name));
    }

    private RTSTags() {
    }
}
