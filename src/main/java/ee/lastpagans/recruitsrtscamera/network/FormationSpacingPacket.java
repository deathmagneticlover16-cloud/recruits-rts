package ee.lastpagans.recruitsrtscamera.network;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.formation.FormationService;
import ee.lastpagans.recruitsrtscamera.compat.SiegeWeaponsCompat;
import ee.lastpagans.recruitsrtscamera.compat.SmallShipsCompat;
import ee.lastpagans.recruitsrtscamera.movement.RTSMovementBoostManager;
import ee.lastpagans.recruitsrtscamera.server.ServerPacketGuard;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Commander specialty toggle for ordinary/tight-ish spacing versus loose spacing. */
public record FormationSpacingPacket(List<UUID> groupIds, boolean loose) {
    private static final double NORMAL_SPACING = 1.0D;
    private static final double LOOSE_SPACING = 2.20D;

    public FormationSpacingPacket {
        int size = Math.min(groupIds.size(), ServerGroupResolver.MAX_GROUPS_PER_ORDER);
        groupIds = List.copyOf(groupIds.subList(0, size));
    }

    public static void encode(FormationSpacingPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.groupIds.size());
        for (UUID id : packet.groupIds) buffer.writeUUID(id);
        buffer.writeBoolean(packet.loose);
    }

    public static FormationSpacingPacket decode(FriendlyByteBuf buffer) {
        int declared = Math.max(0, Math.min(128, buffer.readVarInt()));
        List<UUID> ids = new ArrayList<>(Math.min(declared, ServerGroupResolver.MAX_GROUPS_PER_ORDER));
        for (int i = 0; i < declared; i++) {
            UUID id = buffer.readUUID();
            if (ids.size() < ServerGroupResolver.MAX_GROUPS_PER_ORDER) ids.add(id);
        }
        return new FormationSpacingPacket(ids, buffer.readBoolean());
    }

    public static void handle(FormationSpacingPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> execute(packet, context.getSender()));
        context.setPacketHandled(true);
    }

    private static void execute(FormationSpacingPacket packet, ServerPlayer sender) {
        if (!ServerPacketGuard.allow(sender) || packet.groupIds.isEmpty()) return;
        Map<UUID, List<AbstractRecruitEntity>> groups = ServerGroupResolver.resolve(sender, packet.groupIds);
        int changed = 0;
        int customSkipped = 0;
        double spacing = packet.loose ? LOOSE_SPACING : NORMAL_SPACING;
        for (Map.Entry<UUID, List<AbstractRecruitEntity>> entry : groups.entrySet()) {
            if (FormationService.hasCustom(sender.serverLevel(), entry.getKey())) {
                customSkipped++;
                continue;
            }
            List<AbstractRecruitEntity> formationRecruits =
                    SiegeWeaponsCompat.withoutAttachedArtillery(entry.getValue());
            formationRecruits = SmallShipsCompat.withoutAttachedShips(formationRecruits);
            if (formationRecruits.isEmpty()) {
                continue;
            }
            if (FormationService.setSpacingAndReform(sender, entry.getKey(), formationRecruits, spacing)) {
                RTSMovementBoostManager.registerFormationMove(sender, entry.getKey(), formationRecruits);
                changed++;
            }
        }
        String label = packet.loose ? "Loose spacing" : "Normal spacing";
        sender.displayClientMessage(Component.literal(label + ": " + changed + " group(s)"
                + (customSkipped > 0 ? " · " + customSkipped + " custom group(s) unchanged" : "")), true);
    }
}
