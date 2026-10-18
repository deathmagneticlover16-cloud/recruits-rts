package ee.lastpagans.recruitsrtscamera.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import ee.lastpagans.recruitsrtscamera.combat.TacticalSettings;

/**
 * Small persistent bucket for Commander toggles that belong to a Recruits group rather
 * than to one client session. This intentionally starts with Use Missiles and can absorb
 * future per-group tactical toggles without bloating formation geometry persistence.
 */
public final class RTSGroupTacticsSavedData extends SavedData {
    private static final String DATA_NAME = "recruits_rts_camera_group_tactics";
    private final Set<UUID> useMissiles = new LinkedHashSet<>();
    private final Map<UUID, TacticalSettings> tactics = new HashMap<>();
    private final Map<UUID, CompoundTag> timers = new HashMap<>();

    public RTSGroupTacticsSavedData() {
    }

    public static RTSGroupTacticsSavedData get(ServerLevel level) {
        if (level == null) throw new IllegalArgumentException("ServerLevel cannot be null");
        ServerLevel storage = level.getServer() != null && level.getServer().overworld() != null
                ? level.getServer().overworld() : level;
        return storage.getDataStorage().computeIfAbsent(
                RTSGroupTacticsSavedData::load,
                RTSGroupTacticsSavedData::new,
                DATA_NAME
        );
    }

    public static RTSGroupTacticsSavedData load(CompoundTag root) {
        RTSGroupTacticsSavedData data = new RTSGroupTacticsSavedData();
        for (Tag entry : root.getList("tactics", Tag.TAG_COMPOUND)) {
            CompoundTag tag = (CompoundTag) entry;
            try {
                UUID id = tag.getUUID("group");
                data.tactics.put(id, new TacticalSettings(
                        TacticalSettings.CombatMode.valueOf(tag.getString("combat")),
                        tag.getBoolean("skirmish"),
                        TacticalSettings.ShieldMode.valueOf(tag.getString("shield")), tag.getBoolean("holdFire")));
                data.timers.put(id, tag.getCompound("timers").copy());
            } catch (RuntimeException ignored) { /* Isolate damaged entries. */ }
        }
        ListTag list = root.getList("useMissiles", Tag.TAG_STRING);
        for (Tag tag : list) {
            try {
                data.useMissiles.add(UUID.fromString(tag.getAsString()));
            } catch (RuntimeException ignored) {
                // One malformed UUID should not invalidate the world's tactics state.
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        ListTag list = new ListTag();
        for (UUID id : useMissiles) list.add(StringTag.valueOf(id.toString()));
        root.put("useMissiles", list);
        ListTag states = new ListTag();
        Set<UUID> ids = new LinkedHashSet<>(tactics.keySet());
        ids.addAll(timers.keySet());
        for (UUID id : ids) {
            TacticalSettings s = settings(id);
            CompoundTag tag = new CompoundTag();
            tag.putUUID("group", id); tag.putString("combat", s.combat().name());
            tag.putBoolean("skirmish", s.skirmish()); tag.putString("shield", s.shield().name());
            tag.putBoolean("holdFire", s.holdFire());
            if (timers.containsKey(id)) tag.put("timers", timers.get(id).copy());
            states.add(tag);
        }
        root.put("tactics", states);
        return root;
    }

    public boolean useMissiles(UUID groupId) {
        return groupId != null && useMissiles.contains(groupId);
    }

    public TacticalSettings settings(UUID groupId) {
        return tactics.getOrDefault(groupId, TacticalSettings.DEFAULT);
    }

    public void setSettings(UUID groupId, TacticalSettings value) {
        if (groupId == null) return;
        if (value.equals(TacticalSettings.DEFAULT)) tactics.remove(groupId);
        else tactics.put(groupId, value);
        setDirty();
    }

    public long timer(UUID groupId, String name) {
        CompoundTag tag = timers.get(groupId);
        return tag == null ? 0 : tag.getLong(name);
    }

    public void setTimer(UUID groupId, String name, long until) {
        timers.computeIfAbsent(groupId, ignored -> new CompoundTag()).putLong(name, until);
        setDirty();
    }

    public void setUseMissiles(UUID groupId, boolean enabled) {
        if (groupId == null) return;
        boolean changed = enabled ? useMissiles.add(groupId) : useMissiles.remove(groupId);
        if (changed) setDirty();
    }

    public Set<UUID> missileGroupsSnapshot() {
        return Set.copyOf(useMissiles);
    }
}
