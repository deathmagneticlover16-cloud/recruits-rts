package ee.lastpagans.recruitsrtscamera.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.Event;

import java.util.List;
import java.util.UUID;

/**
 * Fired on the Forge event bus immediately before living routed survivors are removed
 * from the active battlefield after completing their escape. Native command-group
 * bookkeeping has already been removed at the initial rout.
 *
 * <p>Settlements or another campaign layer can subscribe to this event and materialize
 * deserters, return survivors to a manpower pool, create bandits, etc. Commander itself
 * deliberately owns only the tactical escape/despawn.</p>
 *
 * <p>Stragglers or members returning from unloaded chunks can escape in later batches
 * under the same group UUID. Each survivor UUID is delivered once; consumers should
 * process the supplied recruitIds rather than assume that a group UUID has one batch.
 * The listed living entities and their equipment remain available during the event.</p>
 */
public final class RoutedDesertersEvent extends Event {
    private final ServerLevel level;
    private final UUID groupId;
    private final UUID formerOwnerId;
    private final List<UUID> recruitIds;
    private final Vec3 escapePosition;

    public RoutedDesertersEvent(
            ServerLevel level,
            UUID groupId,
            UUID formerOwnerId,
            List<UUID> recruitIds,
            Vec3 escapePosition
    ) {
        this.level = level;
        this.groupId = groupId;
        this.formerOwnerId = formerOwnerId;
        this.recruitIds = recruitIds == null ? List.of() : List.copyOf(recruitIds);
        this.escapePosition = escapePosition == null ? Vec3.ZERO : escapePosition;
    }

    public ServerLevel level() {
        return level;
    }

    public UUID groupId() {
        return groupId;
    }

    public UUID formerOwnerId() {
        return formerOwnerId;
    }

    public List<UUID> recruitIds() {
        return recruitIds;
    }

    public int survivorCount() {
        return recruitIds.size();
    }

    public Vec3 escapePosition() {
        return escapePosition;
    }
}
