package ee.lastpagans.recruitsrtscamera.formation;

import ee.lastpagans.recruitsrtscamera.persistence.RTSFormationSavedData;
import net.minecraft.server.level.ServerLevel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Persistent custom-formation memory backed by RTSFormationSavedData. */
public final class CustomFormationRegistry {
    private CustomFormationRegistry() {
    }

    public static GroupState get(ServerLevel level, UUID groupId) {
        if (level == null || groupId == null) {
            return null;
        }
        RTSFormationSavedData.GroupState state = RTSFormationSavedData.get(level).get(groupId);
        if (state == null || state.mode() != FormationMode.CUSTOM || state.slots().isEmpty()) {
            return null;
        }
        Map<UUID, RelativeSlot> slots = new LinkedHashMap<>();
        for (Map.Entry<UUID, RTSFormationSavedData.RelativeSlot> entry : state.slots().entrySet()) {
            slots.put(entry.getKey(), new RelativeSlot(
                    entry.getValue().dx(), entry.getValue().dz(), entry.getValue().facingYaw()
            ));
        }
        return new GroupState(slots, state.customRanks());
    }

    public static boolean has(ServerLevel level, UUID groupId) {
        return get(level, groupId) != null;
    }

    public static void put(ServerLevel level, UUID groupId, GroupState custom) {
        if (level == null || groupId == null || custom == null || custom.slots().isEmpty()) {
            return;
        }
        RTSFormationSavedData data = RTSFormationSavedData.get(level);
        RTSFormationSavedData.GroupState old = data.get(groupId);
        FormationKind preferred = old == null ? FormationKind.MARCH : old.preferred();
        Map<UUID, RTSFormationSavedData.RelativeSlot> slots = new LinkedHashMap<>();
        for (Map.Entry<UUID, RelativeSlot> entry : custom.slots().entrySet()) {
            slots.put(entry.getKey(), new RTSFormationSavedData.RelativeSlot(
                    entry.getValue().dx(), entry.getValue().dz(), entry.getValue().facingYaw()
            ));
        }
        float facing = custom.slots().values().stream()
                .findFirst().map(RelativeSlot::facingYaw).orElse(Float.NaN);
        data.put(groupId, new RTSFormationSavedData.GroupState(
                FormationMode.CUSTOM,
                preferred,
                preferred,
                1.0D,
                0,
                facing,
                slots,
                custom.ranks()
        ));
    }

    /** Clear only custom geometry, falling back to the group's remembered preset. */
    public static void clear(ServerLevel level, UUID groupId) {
        if (level == null || groupId == null) {
            return;
        }
        RTSFormationSavedData data = RTSFormationSavedData.get(level);
        RTSFormationSavedData.GroupState old = data.get(groupId);
        if (old == null) {
            return;
        }
        FormationKind preferred = old.preferred();
        data.put(groupId, RTSFormationSavedData.GroupState.preset(preferred, old.facingYaw()));
    }

    public record RelativeSlot(double dx, double dz, float facingYaw) {
    }

    public record GroupState(Map<UUID, RelativeSlot> slots, int ranks) {
        public GroupState {
            slots = Map.copyOf(new LinkedHashMap<>(slots));
            ranks = Math.max(1, Math.min(CustomFormationMath.MAX_RANKS, ranks));
        }
    }
}
