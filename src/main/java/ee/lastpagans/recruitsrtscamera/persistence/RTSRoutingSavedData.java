package ee.lastpagans.recruitsrtscamera.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pending tactical escapes, including members whose chunks are currently unloaded. */
public final class RTSRoutingSavedData extends SavedData {
    private static final String NAME = "recruits_rts_camera_routing";
    private final Map<UUID, Rout> groups = new HashMap<>();
    private final Map<UUID, Rout> members = new HashMap<>();

    public static RTSRoutingSavedData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                RTSRoutingSavedData::load, RTSRoutingSavedData::new, NAME);
    }

    public Rout member(UUID id) { return members.get(id); }
    public boolean containsGroup(UUID id) { return groups.containsKey(id); }

    public Rout begin(UUID groupId, UUID ownerId, Collection<UUID> ids, Vec3 direction) {
        Rout rout = groups.computeIfAbsent(groupId, id -> new Rout(id, ownerId, direction));
        for (UUID id : ids) {
            if (id == null) continue;
            rout.members.add(id);
            members.put(id, rout);
        }
        setDirty();
        return rout;
    }

    public void removeMember(UUID id) {
        Rout rout = members.remove(id);
        if (rout == null) return;
        rout.members.remove(id);
        if (rout.members.isEmpty()) groups.remove(rout.groupId);
        setDirty();
    }

    public static RTSRoutingSavedData load(CompoundTag root) {
        RTSRoutingSavedData data = new RTSRoutingSavedData();
        for (Tag tag : root.getList("groups", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) tag;
            if (!row.hasUUID("group") || !row.hasUUID("owner")) continue;
            Set<UUID> ids = new LinkedHashSet<>();
            for (Tag entry : row.getList("members", Tag.TAG_COMPOUND)) {
                CompoundTag member = (CompoundTag) entry;
                if (member.hasUUID("id")) ids.add(member.getUUID("id"));
            }
            if (!ids.isEmpty()) data.begin(row.getUUID("group"), row.getUUID("owner"), ids,
                    new Vec3(row.getDouble("dx"), 0.0D, row.getDouble("dz")));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        ListTag list = new ListTag();
        for (Rout rout : groups.values()) {
            CompoundTag row = new CompoundTag();
            row.putUUID("group", rout.groupId);
            row.putUUID("owner", rout.ownerId);
            row.putDouble("dx", rout.direction.x);
            row.putDouble("dz", rout.direction.z);
            ListTag ids = new ListTag();
            for (UUID id : rout.members) {
                CompoundTag member = new CompoundTag();
                member.putUUID("id", id);
                ids.add(member);
            }
            row.put("members", ids);
            list.add(row);
        }
        root.put("groups", list);
        return root;
    }

    public static final class Rout {
        public final UUID groupId;
        public final UUID ownerId;
        public final Vec3 direction;
        private final Set<UUID> members = new LinkedHashSet<>();

        private Rout(UUID groupId, UUID ownerId, Vec3 direction) {
            this.groupId = groupId;
            this.ownerId = ownerId;
            this.direction = direction;
        }
    }
}
