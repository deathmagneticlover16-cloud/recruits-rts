package ee.lastpagans.recruitsrtscamera.network;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server-authoritative staged route execution. Multiple disjoint selections may route at once. */
public final class RouteManager {
    private static final Map<UUID, List<ActiveRoute>> ACTIVE = new LinkedHashMap<>();
    private static final double ARRIVAL_TOLERANCE = 7.0D;
    private static int tickDivider;

    private RouteManager() {
    }

    public static void start(ServerPlayer player, List<UUID> groupIds, List<BlockPos> waypoints) {
        if (player == null || groupIds == null || groupIds.isEmpty() || waypoints == null || waypoints.isEmpty()) {
            return;
        }
        List<UUID> safeGroups = List.copyOf(groupIds);
        List<BlockPos> safeWaypoints = List.copyOf(waypoints);
        List<ActiveRoute> routes = ACTIVE.computeIfAbsent(player.getUUID(), ignored -> new ArrayList<>());

        // Re-routing any selected group replaces only routes which actually contain one
        // of those groups. Other armies owned by the same commander continue normally.
        routes.removeIf(route -> intersects(route.groupIds, safeGroups));
        ActiveRoute route = new ActiveRoute(safeGroups, safeWaypoints);
        routes.add(route);
        MoveGroupsPacket.executeRouteStep(new MoveGroupsPacket(safeGroups, safeWaypoints.get(0)), player);
    }

    public static void cancel(ServerPlayer player, List<UUID> selectedGroups) {
        if (player == null) {
            return;
        }
        List<ActiveRoute> routes = ACTIVE.get(player.getUUID());
        if (routes == null || routes.isEmpty()) {
            return;
        }
        if (selectedGroups == null || selectedGroups.isEmpty()) {
            ACTIVE.remove(player.getUUID());
            return;
        }
        routes.removeIf(route -> intersects(route.groupIds, selectedGroups));
        if (routes.isEmpty()) {
            ACTIVE.remove(player.getUUID());
        }
    }

    public static void clear(UUID playerId) {
        if (playerId != null) {
            ACTIVE.remove(playerId);
        }
    }

    public static void tick(MinecraftServer server) {
        if (server == null || ACTIVE.isEmpty()) {
            return;
        }
        if (++tickDivider < 5) {
            return;
        }
        tickDivider = 0;

        for (Map.Entry<UUID, List<ActiveRoute>> playerEntry : new ArrayList<>(ACTIVE.entrySet())) {
            UUID playerId = playerEntry.getKey();
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null || !player.isAlive()) {
                ACTIVE.remove(playerId);
                continue;
            }

            List<ActiveRoute> routes = ACTIVE.get(playerId);
            if (routes == null) {
                continue;
            }
            for (ActiveRoute route : new ArrayList<>(routes)) {
                if (route.index >= route.waypoints.size()) {
                    routes.remove(route);
                    continue;
                }
                Map<UUID, List<AbstractRecruitEntity>> groups = ServerGroupResolver.resolve(player, route.groupIds);
                if (groups.isEmpty()) {
                    routes.remove(route);
                    continue;
                }

                Vec3 center = armyCenter(groups);
                BlockPos target = route.waypoints.get(route.index);
                double dx = center.x - (target.getX() + 0.5D);
                double dz = center.z - (target.getZ() + 0.5D);
                if (Math.hypot(dx, dz) > ARRIVAL_TOLERANCE) {
                    continue;
                }

                route.index++;
                if (route.index >= route.waypoints.size()) {
                    routes.remove(route);
                    player.displayClientMessage(net.minecraft.network.chat.Component.literal("RTS route complete."), true);
                } else {
                    MoveGroupsPacket.executeRouteStep(
                            new MoveGroupsPacket(route.groupIds, route.waypoints.get(route.index)),
                            player
                    );
                }
            }
            if (routes.isEmpty()) {
                ACTIVE.remove(playerId);
            }
        }
    }

    private static boolean intersects(List<UUID> a, List<UUID> b) {
        for (UUID id : a) {
            if (b.contains(id)) {
                return true;
            }
        }
        return false;
    }

    private static Vec3 armyCenter(Map<UUID, List<AbstractRecruitEntity>> groups) {
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        int groupCount = 0;
        for (List<AbstractRecruitEntity> recruits : groups.values()) {
            if (recruits == null || recruits.isEmpty()) {
                continue;
            }
            double gx = 0.0D;
            double gy = 0.0D;
            double gz = 0.0D;
            for (AbstractRecruitEntity recruit : recruits) {
                gx += recruit.getX();
                gy += recruit.getY();
                gz += recruit.getZ();
            }
            double inv = 1.0D / recruits.size();
            x += gx * inv;
            y += gy * inv;
            z += gz * inv;
            groupCount++;
        }
        if (groupCount == 0) {
            return Vec3.ZERO;
        }
        double inv = 1.0D / groupCount;
        return new Vec3(x * inv, y * inv, z * inv);
    }

    private static final class ActiveRoute {
        final List<UUID> groupIds;
        final List<BlockPos> waypoints;
        int index;

        ActiveRoute(List<UUID> groupIds, List<BlockPos> waypoints) {
            this.groupIds = groupIds;
            this.waypoints = waypoints;
        }
    }
}
