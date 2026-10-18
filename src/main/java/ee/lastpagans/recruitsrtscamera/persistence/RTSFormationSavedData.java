package ee.lastpagans.recruitsrtscamera.persistence;

import ee.lastpagans.recruitsrtscamera.formation.CustomFormationMath;
import ee.lastpagans.recruitsrtscamera.formation.FormationKind;
import ee.lastpagans.recruitsrtscamera.formation.FormationMode;
import ee.lastpagans.recruitsrtscamera.formation.FormationRotationMath;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent server authority for RTS formation memory.
 *
 * Version 1 stores preset/frontage state plus per-recruit relative slots for custom
 * formations. Keeping the data keyed by the Recruits group UUID lets a custom army
 * survive disconnects and full server/world restarts.
 */
public final class RTSFormationSavedData extends SavedData {
    private static final String DATA_NAME = "recruits_rts_camera_formations";
    private static final int DATA_VERSION = 1;

    private final Map<UUID, GroupState> groups = new LinkedHashMap<>();

    public RTSFormationSavedData() {
    }

    public static RTSFormationSavedData get(ServerLevel level) {
        if (level == null) {
            throw new IllegalArgumentException("ServerLevel cannot be null");
        }
        // Formation memory belongs to the server/world, not to one dimension. Storing
        // it in the overworld SavedData prevents a dimension change from making a
        // perfectly valid custom formation appear to vanish.
        ServerLevel storageLevel = level.getServer() != null && level.getServer().overworld() != null
                ? level.getServer().overworld()
                : level;
        return storageLevel.getDataStorage().computeIfAbsent(
                RTSFormationSavedData::load,
                RTSFormationSavedData::new,
                DATA_NAME
        );
    }

    public static RTSFormationSavedData load(CompoundTag root) {
        RTSFormationSavedData data = new RTSFormationSavedData();
        if (!root.contains("groups", Tag.TAG_LIST)) {
            return data;
        }
        ListTag list = root.getList("groups", Tag.TAG_COMPOUND);
        for (Tag tag : list) {
            if (!(tag instanceof CompoundTag entry) || !entry.hasUUID("group")) {
                continue;
            }
            try {
                UUID groupId = entry.getUUID("group");
                FormationMode mode = parseMode(entry.getString("mode"));
                FormationKind preferred = parseKind(entry.getString("preferred"));
                FormationKind active = parseKind(entry.getString("active"));
                double spacing = clamp(entry.getDouble("spacing"), 0.65D, 2.25D, 1.0D);
                int ranks = Math.max(0, Math.min(20, entry.getInt("ranks")));
                float facing = entry.contains("facing", Tag.TAG_FLOAT)
                        ? FormationRotationMath.normalizeYaw(entry.getFloat("facing"))
                        : Float.NaN;
                Map<UUID, RelativeSlot> slots = new LinkedHashMap<>();
                if (entry.contains("slots", Tag.TAG_LIST)) {
                    for (Tag slotTag : entry.getList("slots", Tag.TAG_COMPOUND)) {
                        if (!(slotTag instanceof CompoundTag slot) || !slot.hasUUID("recruit")) {
                            continue;
                        }
                        UUID recruitId = slot.getUUID("recruit");
                        double dx = finiteOrZero(slot.getDouble("dx"));
                        double dz = finiteOrZero(slot.getDouble("dz"));
                        float yaw = slot.contains("yaw", Tag.TAG_FLOAT)
                                ? FormationRotationMath.normalizeYaw(slot.getFloat("yaw"))
                                : 0.0F;
                        slots.put(recruitId, new RelativeSlot(dx, dz, yaw));
                    }
                }
                int customRanks = Math.max(1, Math.min(CustomFormationMath.MAX_RANKS,
                        entry.contains("customRanks", Tag.TAG_INT) ? entry.getInt("customRanks") : 1));
                data.groups.put(groupId, new GroupState(
                        mode, preferred, active, spacing, ranks, facing, slots, customRanks
                ));
            } catch (RuntimeException ignored) {
                // Ignore one malformed legacy/hand-edited entry instead of losing the file.
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putInt("version", DATA_VERSION);
        ListTag list = new ListTag();
        for (Map.Entry<UUID, GroupState> mapEntry : groups.entrySet()) {
            GroupState state = mapEntry.getValue();
            CompoundTag entry = new CompoundTag();
            entry.putUUID("group", mapEntry.getKey());
            entry.putString("mode", state.mode().name());
            entry.putString("preferred", state.preferred().name());
            entry.putString("active", state.active().name());
            entry.putDouble("spacing", state.spacing());
            entry.putInt("ranks", state.ranks());
            if (Float.isFinite(state.facingYaw())) {
                entry.putFloat("facing", state.facingYaw());
            }
            entry.putInt("customRanks", state.customRanks());
            if (!state.slots().isEmpty()) {
                ListTag slots = new ListTag();
                for (Map.Entry<UUID, RelativeSlot> slotEntry : state.slots().entrySet()) {
                    CompoundTag slot = new CompoundTag();
                    slot.putUUID("recruit", slotEntry.getKey());
                    slot.putDouble("dx", slotEntry.getValue().dx());
                    slot.putDouble("dz", slotEntry.getValue().dz());
                    slot.putFloat("yaw", slotEntry.getValue().facingYaw());
                    slots.add(slot);
                }
                entry.put("slots", slots);
            }
            list.add(entry);
        }
        root.put("groups", list);
        return root;
    }

    public GroupState get(UUID groupId) {
        return groupId == null ? null : groups.get(groupId);
    }

    public Map<UUID, GroupState> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(groups));
    }

    public void put(UUID groupId, GroupState state) {
        if (groupId == null || state == null) {
            return;
        }
        groups.put(groupId, state.normalized());
        setDirty();
    }

    public void remove(UUID groupId) {
        if (groupId != null && groups.remove(groupId) != null) {
            setDirty();
        }
    }

    private static FormationMode parseMode(String raw) {
        try {
            return FormationMode.valueOf(raw);
        } catch (Exception ignored) {
            return FormationMode.PRESET;
        }
    }

    private static FormationKind parseKind(String raw) {
        try {
            return FormationKind.valueOf(raw);
        } catch (Exception ignored) {
            return FormationKind.MARCH;
        }
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0D;
    }

    private static double clamp(double value, double min, double max, double fallback) {
        if (!Double.isFinite(value)) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    public record RelativeSlot(double dx, double dz, float facingYaw) {
        public RelativeSlot {
            dx = finiteOrZero(dx);
            dz = finiteOrZero(dz);
            facingYaw = Float.isFinite(facingYaw)
                    ? FormationRotationMath.normalizeYaw(facingYaw)
                    : 0.0F;
        }
    }

    public record GroupState(
            FormationMode mode,
            FormationKind preferred,
            FormationKind active,
            double spacing,
            int ranks,
            float facingYaw,
            Map<UUID, RelativeSlot> slots,
            int customRanks
    ) {
        public GroupState {
            mode = mode == null ? FormationMode.PRESET : mode;
            preferred = preferred == null ? FormationKind.MARCH : preferred;
            active = active == null ? preferred : active;
            spacing = clamp(spacing, 0.65D, 2.25D, 1.0D);
            ranks = Math.max(0, Math.min(20, ranks));
            facingYaw = Float.isFinite(facingYaw)
                    ? FormationRotationMath.normalizeYaw(facingYaw)
                    : Float.NaN;
            slots = Map.copyOf(new LinkedHashMap<>(slots == null ? Map.of() : slots));
            customRanks = Math.max(1, Math.min(CustomFormationMath.MAX_RANKS, customRanks));
        }

        public static GroupState preset(FormationKind kind, float facingYaw) {
            FormationKind safe = kind == null ? FormationKind.MARCH : kind;
            return new GroupState(FormationMode.PRESET, safe, safe, 1.0D, 0, facingYaw, Map.of(), 1);
        }

        public GroupState normalized() {
            return new GroupState(mode, preferred, active, spacing, ranks, facingYaw, slots, customRanks);
        }

        public GroupState withPreferred(FormationKind kind) {
            FormationKind safe = kind == null ? FormationKind.MARCH : kind;
            return new GroupState(mode, safe, active, spacing, ranks, facingYaw, slots, customRanks);
        }

        public GroupState withFacing(float yaw) {
            return new GroupState(mode, preferred, active, spacing, ranks, yaw, slots, customRanks);
        }
    }
}
