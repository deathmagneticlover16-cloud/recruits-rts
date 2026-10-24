package ee.lastpagans.recruitsrtscamera.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Persistent Immersive Paintings selection for each Recruits group marker. */
public final class RTSGroupFlagSavedData extends SavedData {
    private static final String DATA_NAME = "recruits_rts_camera_group_flags";
    private final Map<UUID, ResourceLocation> flags = new LinkedHashMap<>();

    public static RTSGroupFlagSavedData get(ServerLevel level) {
        if (level == null) throw new IllegalArgumentException("ServerLevel cannot be null");
        ServerLevel storage = level.getServer() != null && level.getServer().overworld() != null
                ? level.getServer().overworld() : level;
        return storage.getDataStorage().computeIfAbsent(
                RTSGroupFlagSavedData::load, RTSGroupFlagSavedData::new, DATA_NAME);
    }

    public static RTSGroupFlagSavedData load(CompoundTag root) {
        RTSGroupFlagSavedData data = new RTSGroupFlagSavedData();
        for (Tag raw : root.getList("flags", Tag.TAG_COMPOUND)) {
            if (!(raw instanceof CompoundTag entry) || !entry.hasUUID("group")) continue;
            ResourceLocation flag = ResourceLocation.tryParse(entry.getString("painting"));
            if (flag != null) data.flags.put(entry.getUUID("group"), flag);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, ResourceLocation> selected : flags.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("group", selected.getKey());
            entry.putString("painting", selected.getValue().toString());
            list.add(entry);
        }
        root.put("flags", list);
        return root;
    }

    public Map<UUID, ResourceLocation> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(flags));
    }

    public void set(UUID groupId, ResourceLocation flag) {
        if (groupId == null) return;
        if (flag == null) flags.remove(groupId);
        else flags.put(groupId, flag);
        setDirty();
    }
}
