package ee.lastpagans.recruitsrtscamera.network;

import ee.lastpagans.recruitsrtscamera.combat.RTSGroupAttackManager;
import ee.lastpagans.recruitsrtscamera.compat.OrderRelayCompat;
import ee.lastpagans.recruitsrtscamera.server.ServerPacketGuard;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.util.FormationUtils;
import com.talhanation.recruits.util.RecruitCommanderUtil;
import ee.lastpagans.recruitsrtscamera.compat.SiegeWeaponsCompat;
import ee.lastpagans.recruitsrtscamera.compat.SmallShipsCompat;
import ee.lastpagans.recruitsrtscamera.formation.FormationService;
import ee.lastpagans.recruitsrtscamera.formation.FrontageMath;
import ee.lastpagans.recruitsrtscamera.movement.RTSMovementBoostManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A right-drag RTS order. The two world points define the complete front line.
 * The server validates it, divides it among groups, and gives each group to the native
 * formation runtime for collision-aware line slots.
 */
public record FrontageGroupsPacket(List<UUID> groupIds, Vec3 start, Vec3 end) {
    private static final double MAX_TARGET_DISTANCE = 336.0D;

    public FrontageGroupsPacket {
        int size = Math.min(groupIds.size(), ServerGroupResolver.MAX_GROUPS_PER_ORDER);
        groupIds = List.copyOf(groupIds.subList(0, size));
        start = start == null ? Vec3.ZERO : start;
        end = end == null ? Vec3.ZERO : end;
    }

    public static void encode(FrontageGroupsPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.groupIds.size());
        for (UUID groupId : packet.groupIds) {
            buffer.writeUUID(groupId);
        }
        writeVec3(buffer, packet.start);
        writeVec3(buffer, packet.end);
    }

    public static FrontageGroupsPacket decode(FriendlyByteBuf buffer) {
        int declaredSize = Math.max(0, Math.min(128, buffer.readVarInt()));
        List<UUID> groups = new ArrayList<>(Math.min(declaredSize, ServerGroupResolver.MAX_GROUPS_PER_ORDER));
        for (int i = 0; i < declaredSize; i++) {
            UUID id = buffer.readUUID();
            if (groups.size() < ServerGroupResolver.MAX_GROUPS_PER_ORDER) {
                groups.add(id);
            }
        }
        return new FrontageGroupsPacket(groups, readVec3(buffer), readVec3(buffer));
    }

    public static void handle(FrontageGroupsPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> OrderRelayCompat.executeOrRelay(
                context, packet.groupIds(), "order_forward",
                () -> execute(packet, context.getSender())
        ));
        context.setPacketHandled(true);
    }

    private static void execute(FrontageGroupsPacket packet, ServerPlayer sender) {
        if (!ServerPacketGuard.allow(sender) || packet.groupIds.isEmpty()) {
            return;
        }
        RouteManager.cancel(sender, packet.groupIds);
        RTSGroupAttackManager.cancelOwned(sender, packet.groupIds);
        if (!finite(packet.start) || !finite(packet.end)) {
            return;
        }

        double dx = packet.end.x - packet.start.x;
        double dz = packet.end.z - packet.start.z;
        double width = Math.sqrt(dx * dx + dz * dz);
        if (width < FrontageMath.MIN_FRONTAGE) {
            sender.displayClientMessage(Component.literal("Frontage is too short; use a normal right-click move instead."), true);
            return;
        }
        if (width > FrontageMath.MAX_FRONTAGE + 0.01D) {
            sender.displayClientMessage(Component.literal("Frontage is too wide (maximum 80 blocks)."), true);
            return;
        }

        BlockPos startPos = BlockPos.containing(packet.start);
        BlockPos endPos = BlockPos.containing(packet.end);
        if (!sender.serverLevel().hasChunkAt(startPos) || !sender.serverLevel().hasChunkAt(endPos)) {
            sender.displayClientMessage(Component.literal("Part of that frontage is not loaded."), true);
            return;
        }
        if (sender.distanceToSqr(packet.start.x, packet.start.y, packet.start.z) > MAX_TARGET_DISTANCE * MAX_TARGET_DISTANCE
                || sender.distanceToSqr(packet.end.x, packet.end.y, packet.end.z) > MAX_TARGET_DISTANCE * MAX_TARGET_DISTANCE) {
            sender.displayClientMessage(Component.literal("That frontage is outside tactical range."), true);
            return;
        }

        Map<UUID, List<AbstractRecruitEntity>> resolved = ServerGroupResolver.resolve(sender, packet.groupIds);
        if (resolved.isEmpty()) {
            sender.displayClientMessage(Component.literal("No loaded owned recruits were found."), true);
            return;
        }

        double invWidth = 1.0D / width;
        double lineX = dx * invWidth;
        double lineZ = dz * invWidth;

        // Keep groups in their existing left-to-right battlefield order. This greatly
        // reduces groups crossing through each other on the way to a shared frontage.
        List<UUID> orderedIds = new ArrayList<>(resolved.keySet());
        orderedIds.sort(java.util.Comparator.comparingDouble(
                (UUID groupId) -> groupProjection(resolved.get(groupId), lineX, lineZ)
        ));
        List<Integer> counts = new ArrayList<>(orderedIds.size());
        for (UUID groupId : orderedIds) {
            counts.add(resolved.get(groupId).size());
        }

        List<FrontageMath.GroupSegment> segments = FrontageMath.split(counts, width);
        if (segments.size() != orderedIds.size()) {
            sender.displayClientMessage(Component.literal("Could not plan that frontage."), true);
            return;
        }

        double midX = (packet.start.x + packet.end.x) * 0.5D;
        double midY = (packet.start.y + packet.end.y) * 0.5D;
        double midZ = (packet.start.z + packet.end.z) * 0.5D;
        float facingYaw = FrontageMath.facingYaw(dx, dz);

        int totalRecruits = 0;
        int formedGroups = 0;
        int artilleryGuns = 0;
        int ships = 0;
        for (int i = 0; i < orderedIds.size(); i++) {
            UUID groupId = orderedIds.get(i);
            List<AbstractRecruitEntity> recruits = resolved.get(groupId);
            FrontageMath.GroupSegment segment = segments.get(i);

            double targetX = midX + lineX * segment.centerOffset();
            double targetZ = midZ + lineZ * segment.centerOffset();
            BlockPos surface = FormationUtils.getPositionOrSurface(
                    sender.serverLevel(),
                    BlockPos.containing(targetX, midY, targetZ)
            );
            Vec3 groupTarget = new Vec3(targetX, surface.getY(), targetZ);

            RecruitCommanderUtil.setRecruitsClearTargets(recruits);

            SiegeWeaponsCompat.BatteryOrder battery = SiegeWeaponsCompat.commandBatteryLine(
                    sender, recruits, groupTarget, facingYaw, segment.allocatedWidth()
            );
            SmallShipsCompat.FleetOrder fleet = SmallShipsCompat.commandFleetLine(
                    sender, recruits, groupTarget, facingYaw, segment.allocatedWidth()
            );
            List<AbstractRecruitEntity> formationRecruits = SiegeWeaponsCompat.withoutAttachedArtillery(recruits);
            formationRecruits = SmallShipsCompat.withoutAttachedShips(formationRecruits);

            boolean formed = false;
            if (!formationRecruits.isEmpty()) {
                formed = FormationService.frontage(
                        sender,
                        groupId,
                        formationRecruits,
                        groupTarget,
                        segment.allocatedWidth(),
                        facingYaw,
                        segment.ranks(),
                        segment.spacing()
                );
                if (!formed) {
                    // Keep a native Recruits fallback if formation planning rejects the order.
                    RecruitCommanderUtil.setRecruitsMove(formationRecruits, surface);
                }
                RTSMovementBoostManager.registerFormationMove(sender, groupId, formationRecruits);
            }
            if (battery.movedAny()) {
                artilleryGuns += battery.guns();
                FormationService.rememberFacing(sender.serverLevel(), groupId, facingYaw);
                formed = true;
            }
            if (fleet.movedAny()) {
                ships += fleet.ships();
                FormationService.rememberFacing(sender.serverLevel(), groupId, facingYaw);
                formed = true;
            }
            if (formed) {
                formedGroups++;
            }
            totalRecruits += recruits.size();
        }

        String engine;
        if ((artilleryGuns > 0 || ships > 0) && formedGroups > 0) {
            engine = " · formation line + native heavy-unit line";
        } else if (formedGroups > 0) {
            engine = " · tactical formation line";
        } else {
            engine = " · native group positions";
        }
        sender.displayClientMessage(Component.literal(
                "Frontage order: " + orderedIds.size() + " group(s), " + totalRecruits
                        + " recruits · " + String.format(java.util.Locale.ROOT, "%.1f", width)
                        + " blocks" + engine
                        + (artilleryGuns > 0 ? " · " + artilleryGuns + " gun(s)" : "")
                        + (ships > 0 ? " · " + ships + " ship(s)" : "")
        ), true);
    }

    private static double groupProjection(List<AbstractRecruitEntity> recruits, double lineX, double lineZ) {
        if (recruits == null || recruits.isEmpty()) {
            return 0.0D;
        }
        double x = 0.0D;
        double z = 0.0D;
        for (AbstractRecruitEntity recruit : recruits) {
            x += recruit.getX();
            z += recruit.getZ();
        }
        double inv = 1.0D / recruits.size();
        return (x * inv) * lineX + (z * inv) * lineZ;
    }

    private static void writeVec3(FriendlyByteBuf buffer, Vec3 value) {
        buffer.writeDouble(value.x);
        buffer.writeDouble(value.y);
        buffer.writeDouble(value.z);
    }

    private static Vec3 readVec3(FriendlyByteBuf buffer) {
        return new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
