package ee.lastpagans.recruitsrtscamera.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** World-persistent morale items added live by server operators. */
public final class RTSMoraleItemSavedData extends SavedData {
    private static final String DATA_NAME = "recruits_rts_camera_morale_items";
    private final Set<ResourceLocation> items = new LinkedHashSet<>();

    public static RTSMoraleItemSavedData get(ServerLevel level) {
        if (level == null) throw new IllegalArgumentException("ServerLevel cannot be null");
        ServerLevel storage = level.getServer() != null && level.getServer().overworld() != null
                ? level.getServer().overworld() : level;
        return storage.getDataStorage().computeIfAbsent(
                RTSMoraleItemSavedData::load, RTSMoraleItemSavedData::new, DATA_NAME);
    }

    public static RTSMoraleItemSavedData load(CompoundTag root) {
        RTSMoraleItemSavedData data = new RTSMoraleItemSavedData();
        ListTag list = root.getList("items", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            ResourceLocation id = ResourceLocation.tryParse(list.getString(i));
            if (id != null) data.items.add(id);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        ListTag list = new ListTag();
        for (ResourceLocation item : items) list.add(StringTag.valueOf(item.toString()));
        root.put("items", list);
        return root;
    }

    public boolean contains(ResourceLocation item) {
        return item != null && items.contains(item);
    }

    public boolean add(ResourceLocation item) {
        boolean changed = item != null && items.add(item);
        if (changed) setDirty();
        return changed;
    }

    public boolean remove(ResourceLocation item) {
        boolean changed = item != null && items.remove(item);
        if (changed) setDirty();
        return changed;
    }

    public Set<ResourceLocation> snapshot() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(items));
    }
}
