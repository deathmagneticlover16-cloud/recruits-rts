package ee.lastpagans.recruitsrtscamera.network;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.formation.FormationRuntime;
import ee.lastpagans.recruitsrtscamera.formation.FormationService;
import ee.lastpagans.recruitsrtscamera.movement.RTSMovementBoostManager;
import ee.lastpagans.recruitsrtscamera.server.ServerPacketGuard;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Moves one or more complete formations by the same delta without rebuilding their layout. */
public record TranslateGroupsPacket(List<UUID> groupIds, Vec3 delta) {
    public TranslateGroupsPacket {
        int size = Math.min(groupIds.size(), ServerGroupResolver.MAX_GROUPS_PER_ORDER);
        groupIds = List.copyOf(groupIds.subList(0, size));
    }

    public static void encode(TranslateGroupsPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.groupIds.size());
        for (UUID id : packet.groupIds) buffer.writeUUID(id);
        buffer.writeDouble(packet.delta.x);
        buffer.writeDouble(packet.delta.y);
        buffer.writeDouble(packet.delta.z);
    }

    public static TranslateGroupsPacket decode(FriendlyByteBuf buffer) {
        int declared = Math.max(0, Math.min(128, buffer.readVarInt()));
        List<UUID> ids = new ArrayList<>(Math.min(declared, ServerGroupResolver.MAX_GROUPS_PER_ORDER));
        for (int i = 0; i < declared; i++) {
            UUID id = buffer.readUUID();
            if (ids.size() < ServerGroupResolver.MAX_GROUPS_PER_ORDER) ids.add(id);
        }
        return new TranslateGroupsPacket(ids, new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));
    }

    public static void handle(TranslateGroupsPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> execute(packet, context.getSender()));
        context.setPacketHandled(true);
    }

    private static void execute(TranslateGroupsPacket packet, ServerPlayer sender) {
        Vec3 delta = packet.delta;
        if (!ServerPacketGuard.allow(sender) || packet.groupIds.isEmpty() || delta == null
                || !Double.isFinite(delta.x) || !Double.isFinite(delta.y) || !Double.isFinite(delta.z)
                || delta.lengthSqr() > 576.0D * 576.0D) return;
        Map<UUID, List<AbstractRecruitEntity>> groups = ServerGroupResolver.resolve(sender, packet.groupIds);
        for (Map.Entry<UUID, List<AbstractRecruitEntity>> entry : groups.entrySet()) {
            boolean moved = FormationRuntime.translateFormation(entry.getKey(), delta);
            if (!moved) {
                Vec3 center = average(entry.getValue());
                moved = center != null && FormationService.movePreserved(
                        sender, entry.getKey(), entry.getValue(), center.add(delta));
            }
            if (moved) {
                RotateFormationPacket.markTranslated(entry.getKey(), sender.level().getGameTime());
                RTSMovementBoostManager.registerHastyMove(sender, entry.getKey(), entry.getValue());
            }
        }
    }

    private static Vec3 average(List<AbstractRecruitEntity> recruits) {
        double x = 0.0D, y = 0.0D, z = 0.0D;
        int count = 0;
        for (AbstractRecruitEntity recruit : recruits) {
            if (recruit == null || !recruit.isAlive() || recruit.isRemoved()) continue;
            Entity entity = recruit.getVehicle() == null ? recruit : recruit.getVehicle();
            x += entity.getX(); y += entity.getY(); z += entity.getZ(); count++;
        }
        return count == 0 ? null : new Vec3(x / count, y / count, z / count);
    }
}
