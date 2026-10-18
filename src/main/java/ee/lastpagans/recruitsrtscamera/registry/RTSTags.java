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

    private RTSTags() {
    }
}
