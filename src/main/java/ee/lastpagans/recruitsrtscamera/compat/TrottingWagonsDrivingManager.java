package ee.lastpagans.recruitsrtscamera.compat;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.BowmanEntity;
import com.talhanation.recruits.entities.CrossBowmanEntity;
import com.talhanation.recruits.entities.HorsemanEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.formation.FormationKind;
import ee.lastpagans.recruitsrtscamera.navigation.TacticalPathfinder;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitAIScheduler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Recruit crews and WALK/RUN formation orders for the optional scythed chariot. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TrottingWagonsDrivingManager {
    private static final ResourceLocation WHIP_ID = new ResourceLocation("trotting_wagons", "horse_whip");
    private static final String CREW_WAGON = "RecruitsRTSChariot";
    private static final String VIRTUAL_WHIP = "RecruitsRTSVirtualWhip";
    private static final double BOARD_RANGE = 24.0D;
    private static final double ARRIVAL_DISTANCE = 1.35D;
    private static final Map<UUID, DriveOrder> ORDERS = new HashMap<>();
    private static final Map<UUID, WeakReference<Entity>> CREW_WAGONS = new HashMap<>();

    private TrottingWagonsDrivingManager() { }

    public static BoardingResult boardNearby(ServerPlayer owner, List<AbstractRecruitEntity> members) {
        if (!TrottingWagonsCompat.isAvailable() || owner == null || members == null || members.isEmpty()) {
            return BoardingResult.EMPTY;
        }
        List<HorsemanEntity> drivers = members.stream()
                .filter(HorsemanEntity.class::isInstance).map(HorsemanEntity.class::cast)
                .filter(recruit -> !isChariotCrew(recruit)).sorted(Comparator.comparing(Entity::getUUID)).toList();
        if (drivers.isEmpty()) return BoardingResult.EMPTY;

        Map<UUID, Entity> nearby = new HashMap<>();
        for (HorsemanEntity driver : drivers) {
            owner.serverLevel().getEntities((Entity) null, driver.getBoundingBox().inflate(BOARD_RANGE),
                    wagon -> TrottingWagonsCompat.isScythedWagon(wagon)
                            && wagon.isAlive() && hasDraftTeam(wagon)
                            && wagon.getControllingPassenger() == null)
                    .forEach(wagon -> nearby.put(wagon.getUUID(), wagon));
        }
        List<Entity> wagons = new ArrayList<>(nearby.values());
        wagons.sort(Comparator.comparingDouble(wagon -> nearestDistance(wagon, drivers)));

        Set<UUID> boarded = new HashSet<>();
        Set<UUID> assignedDrivers = new HashSet<>();
        int wagonCount = 0;
        for (Entity wagon : wagons) {
            HorsemanEntity driver = drivers.stream()
                    .filter(candidate -> !assignedDrivers.contains(candidate.getUUID()))
                    .filter(candidate -> candidate.distanceToSqr(wagon) <= BOARD_RANGE * BOARD_RANGE)
                    .min(Comparator.comparingDouble(candidate -> candidate.distanceToSqr(wagon)))
                    .orElse(null);
            if (driver == null) continue;
            driver.stopRiding();
            if (!driver.startRiding(wagon, true)) continue;
            assignedDrivers.add(driver.getUUID());
            wagonCount++;
            markCrew(driver, wagon);
            equipWhip(driver);
            boarded.add(driver.getUUID());

            AbstractRecruitEntity gunner = nearestGunner(members, wagon, boarded);
            if (gunner != null && gunner.startRiding(wagon, true)) {
                markCrew(gunner, wagon);
                boarded.add(gunner.getUUID());
            }
        }
        return new BoardingResult(wagonCount, Set.copyOf(boarded));
    }

    public static void dismount(AbstractRecruitEntity recruit) {
        if (recruit == null || !isChariotCrew(recruit)) return;
        Entity wagon = recruit.getVehicle();
        recruit.stopRiding();
        recruit.getPersistentData().remove(CREW_WAGON);
        if (recruit.getPersistentData().getBoolean(VIRTUAL_WHIP)) {
            if (isWhip(recruit.getMainHandItem())) {
                recruit.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                recruit.tryToReequip(EquipmentSlot.MAINHAND);
            }
            recruit.getPersistentData().remove(VIRTUAL_WHIP);
        }
        if (wagon != null && wagon.getPassengers().stream().noneMatch(AbstractRecruitEntity.class::isInstance)) {
            CREW_WAGONS.remove(wagon.getUUID());
            ORDERS.remove(wagon.getUUID());
        }
    }

    public static boolean isChariotCrew(AbstractRecruitEntity recruit) {
        return recruit != null && TrottingWagonsCompat.isScythedWagon(recruit.getVehicle())
                && recruit.getPersistentData().hasUUID(CREW_WAGON)
                && recruit.getPersistentData().getUUID(CREW_WAGON).equals(recruit.getVehicle().getUUID());
    }

    public static List<AbstractRecruitEntity> withoutCrews(List<AbstractRecruitEntity> recruits) {
        if (recruits == null || recruits.isEmpty()) return List.of();
        return recruits.stream().filter(recruit -> !isChariotCrew(recruit)).toList();
    }

    public static int commandFormation(ServerPlayer sender, UUID groupId,
                                       List<AbstractRecruitEntity> recruits, Vec3 center,
                                       float finalFacing, FormationKind kind, boolean running) {
        if (!TrottingWagonsCompat.isAvailable() || sender == null || groupId == null
                || recruits == null || center == null) return 0;
        List<Entity> wagons = drivenWagons(recruits);
        if (wagons.isEmpty()) return 0;
        wagons.sort(Comparator
                .comparingDouble((Entity wagon) -> ChariotFormationMath.projection(
                        new ChariotFormationMath.Point(wagon.getX(), wagon.getZ()), finalFacing))
                .thenComparing(wagon -> wagon.getUUID().toString()));
        // Wide line-abreast is the safe baseline for bladed vehicles. Other infantry shapes
        // are deliberately not squeezed into overlapping scythe envelopes.
        List<ChariotFormationMath.Point> slots = ChariotFormationMath.lineSlots(wagons.size(),
                new ChariotFormationMath.Point(center.x, center.z), finalFacing);
        for (int i = 0; i < wagons.size(); i++) {
            Entity wagon = wagons.get(i);
            ChariotFormationMath.Point slot = slots.get(i);
            ORDERS.put(wagon.getUUID(), new DriveOrder(new WeakReference<>(wagon), groupId,
                    new Vec3(slot.x(), center.y, slot.z()), finalFacing, running ? 2 : 1));
            planRoute(wagon, ORDERS.get(wagon.getUUID()));
            clearCrewMovement(wagon);
        }
        return wagons.size();
    }

    public static void setPace(UUID groupId, boolean running) {
        if (groupId == null) return;
        for (DriveOrder order : ORDERS.values()) {
            if (groupId.equals(order.groupId)) order.requestedSpeedLevel = running ? 2 : 1;
        }
    }

    public static void halt(UUID groupId) {
        if (groupId == null) return;
        for (DriveOrder order : ORDERS.values()) {
            if (groupId.equals(order.groupId)) {
                order.requestedSpeedLevel = 0;
                order.effectiveSpeedLevel = 0;
                Entity wagon = order.wagon.get();
                if (wagon != null) order.target = wagon.position();
            }
        }
    }

    /** Called immediately before the optional wagon's native steering and acceleration pass. */
    public static boolean controls(Entity wagon) {
        return TrottingWagonsCompat.isScythedWagon(wagon)
                && wagon.getControllingPassenger() instanceof HorsemanEntity driver
                && isChariotCrew(driver) && hasDraftTeam(wagon)
                && (isWhip(driver.getMainHandItem()) || isWhip(driver.getOffhandItem()));
    }

    public static WagonControl nativeControl(Entity wagon, boolean blocked) {
        if (!controls(wagon)) return WagonControl.STOP;
        DriveOrder order = ORDERS.get(wagon.getUUID());
        if (order == null) return WagonControl.STOP;
        float yawDelta = Mth.wrapDegrees(order.travelYaw - wagon.getYRot());
        boolean reversing = blocked && Math.abs(yawDelta) > 60.0F;
        float divisor = reversing || blocked ? 30.0F : 45.0F;
        float steering = -Mth.clamp(yawDelta / divisor, -1.0F, 1.0F);
        int speed = reversing ? 1 : Mth.clamp(order.effectiveSpeedLevel, 0, 2);
        return new WagonControl(speed, steering, reversing);
    }

    /** Protect only the other vehicle crews/draft horses in this convoy, not nearby infantry. */
    public static boolean protectedConvoyMember(Entity sourceWagon, LivingEntity target) {
        UUID group = driverGroup(sourceWagon);
        if (group == null || target == null) return false;
        if (target instanceof AbstractRecruitEntity recruit && isChariotCrew(recruit)) {
            return group.equals(recruit.getGroup());
        }
        for (WeakReference<Entity> reference : List.copyOf(CREW_WAGONS.values())) {
            Entity wagon = reference.get();
            if (wagon == null) continue;
            if (!group.equals(driverGroup(wagon))) continue;
            for (Mob horse : TrottingWagonsCompat.pullingHorses(wagon)) if (horse == target) return true;
        }
        return false;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || ORDERS.isEmpty()) return;
        for (Map.Entry<UUID, DriveOrder> entry : List.copyOf(ORDERS.entrySet())) {
            DriveOrder order = entry.getValue();
            Entity wagon = order.wagon.get();
            if (wagon == null || wagon.isRemoved() || !wagon.isAlive()
                    || !(wagon.level() instanceof ServerLevel)
                    || !(wagon.getControllingPassenger() instanceof HorsemanEntity driver)
                    || !isChariotCrew(driver) || !order.groupId.equals(driver.getGroup())) {
                ORDERS.remove(entry.getKey(), order);
                continue;
            }
            tickOrder(wagon, order);
            clearCrewMovement(wagon);
        }
    }

    private static void tickOrder(Entity wagon, DriveOrder order) {
        if (order.routePending) {
            order.effectiveSpeedLevel = 0;
            return;
        }
        if (order.routeFailed) {
            order.effectiveSpeedLevel = 0;
            return;
        }
        advanceRoute(wagon, order);
        if (order.routePending) {
            order.effectiveSpeedLevel = 0;
            return;
        }
        double dx = order.target.x - wagon.getX();
        double dz = order.target.z - wagon.getZ();
        double distance = Math.hypot(dx, dz);
        double speed = Math.abs(TrottingWagonsCompat.currentSpeed(wagon));
        if (distance <= ARRIVAL_DISTANCE && speed < 0.025D) {
            order.effectiveSpeedLevel = 0;
            order.travelYaw = order.finalFacing;
            wagon.setYRot(Mth.approachDegrees(wagon.getYRot(), order.finalFacing, 3.0F));
            return;
        }

        float travelYaw = distance > 1.0E-4D
                ? ChariotFormationMath.targetYaw(dx, dz) : order.finalFacing;
        order.travelYaw = travelYaw;
        float error = Math.abs(Mth.wrapDegrees(travelYaw - wagon.getYRot()));

        double nativeDeceleration = TrottingWagonsCompat.BASE_SPEED
                / (TrottingWagonsCompat.ACCELERATION_SECONDS * 20.0D);
        double brakingDistance = speed * speed / Math.max(0.0001D, 2.0D * nativeDeceleration * 3.0D)
                + ARRIVAL_DISTANCE;
        int desired = distance <= brakingDistance ? 0 : order.requestedSpeedLevel;
        // Trotting Wagons steers through forward motion; setting throttle to zero for a
        // large yaw error creates a permanent deadlock because a wagon cannot pivot in
        // place. Take the corner slowly instead, exactly like the reference compatibility.
        if (error > 28.0F && desired > 0) desired = 1;
        if (desired == 0) {
            // Native deceleration supplies the first third. This extra bounded reduction gives
            // formation driving a useful stopping distance without teleporting or zeroing motion.
            TrottingWagonsCompat.brakeCurrentSpeed(wagon, nativeDeceleration * 2.0D);
        }
        order.effectiveSpeedLevel = desired;
    }

    private static void planRoute(Entity wagon, DriveOrder order) {
        if (!(wagon.level() instanceof ServerLevel level) || order == null) return;
        Mob[] horses = TrottingWagonsCompat.pullingHorses(wagon);
        Mob pathingHorse = horses.length == 0 ? null : horses[0];
        if (pathingHorse == null) return;
        TacticalPathfinder.SearchSession search = TacticalPathfinder.begin(
                level, pathingHorse, wagon.position(), order.destination, 12_000);
        order.routePending = RecruitAIScheduler.submitIncremental(
                RecruitAIScheduler.Priority.HIGH,
                RecruitAIScheduler.WorkType.FORMATION_PATH,
                wagon.getUUID(),
                deadline -> {
                    if (wagon.isRemoved() || !wagon.isAlive() || ORDERS.get(wagon.getUUID()) != order) {
                        order.routePending = false;
                        return true;
                    }
                    if (!search.advance(deadline)) return false;
                    TacticalPathfinder.Route route = search.result();
                    order.route = route.points();
                    order.routeFailed = route.points().isEmpty();
                    order.routeIndex = 0;
                    order.routeComplete = route.complete();
                    order.routePending = false;
                    advanceRoute(wagon, order);
                    return true;
                });
    }

    private static void advanceRoute(Entity wagon, DriveOrder order) {
        while (order.routeIndex < order.route.size()) {
            Vec3 point = order.route.get(order.routeIndex);
            if (horizontalDistanceSq(wagon.position(), point) > 2.25D * 2.25D) {
                order.target = point;
                return;
            }
            order.routeIndex++;
        }
        order.target = order.destination;
        if (!order.routeComplete && !order.route.isEmpty() && !order.routePending
                && horizontalDistanceSq(wagon.position(), order.destination) > 3.0D * 3.0D) {
            order.route = List.of();
            planRoute(wagon, order);
        }
    }

    private static List<Entity> drivenWagons(List<AbstractRecruitEntity> recruits) {
        Map<UUID, Entity> unique = new HashMap<>();
        for (AbstractRecruitEntity recruit : recruits) {
            if (recruit instanceof HorsemanEntity && isChariotCrew(recruit)
                    && recruit.getVehicle().getControllingPassenger() == recruit) {
                unique.put(recruit.getVehicle().getUUID(), recruit.getVehicle());
                CREW_WAGONS.put(recruit.getVehicle().getUUID(), new WeakReference<>(recruit.getVehicle()));
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static void clearCrewMovement(Entity wagon) {
        for (Entity passenger : wagon.getPassengers()) {
            if (passenger instanceof AbstractRecruitEntity recruit) {
                recruit.getNavigation().stop();
                recruit.clearMovePos();
                recruit.setShouldMovePos(false);
                recruit.setShouldHoldPos(false);
                recruit.setShouldFollow(false);
                recruit.setIsFollowing(false);
            }
        }
    }

    private static AbstractRecruitEntity nearestGunner(List<AbstractRecruitEntity> members, Entity wagon,
                                                        Set<UUID> boarded) {
        return members.stream().filter(recruit -> recruit instanceof BowmanEntity
                        || recruit instanceof CrossBowmanEntity)
                .filter(recruit -> !boarded.contains(recruit.getUUID()) && !recruit.isPassenger())
                .filter(recruit -> recruit.distanceToSqr(wagon) <= BOARD_RANGE * BOARD_RANGE)
                .min(Comparator.comparingDouble(recruit -> recruit.distanceToSqr(wagon))).orElse(null);
    }

    private static double nearestDistance(Entity wagon, List<HorsemanEntity> drivers) {
        return drivers.stream().mapToDouble(driver -> driver.distanceToSqr(wagon)).min()
                .orElse(Double.POSITIVE_INFINITY);
    }

    private static double horizontalDistanceSq(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private static boolean hasDraftTeam(Entity wagon) {
        Mob[] horses = TrottingWagonsCompat.pullingHorses(wagon);
        return horses.length >= 2 && horses[0] != null && horses[1] != null
                && horses[0].isAlive() && horses[1].isAlive();
    }

    private static UUID driverGroup(Entity wagon) {
        return wagon != null && wagon.getControllingPassenger() instanceof AbstractRecruitEntity recruit
                ? recruit.getGroup() : null;
    }

    private static void markCrew(AbstractRecruitEntity recruit, Entity wagon) {
        recruit.getPersistentData().putUUID(CREW_WAGON, wagon.getUUID());
        recruit.setShouldMount(false);
        CREW_WAGONS.put(wagon.getUUID(), new WeakReference<>(wagon));
    }

    private static void equipWhip(HorsemanEntity driver) {
        if (isWhip(driver.getMainHandItem())) return;
        driver.switchMainHandItem(TrottingWagonsDrivingManager::isWhip);
        if (isWhip(driver.getMainHandItem())) return;
        Item whip = BuiltInRegistries.ITEM.get(WHIP_ID);
        if (whip == Items.AIR) return;
        ItemStack old = driver.getMainHandItem().copy();
        if (!old.isEmpty()) {
            ItemStack remainder = driver.getInventory().addItem(old);
            if (!remainder.isEmpty()) driver.spawnAtLocation(remainder);
        }
        driver.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(whip));
        driver.getPersistentData().putBoolean(VIRTUAL_WHIP, true);
    }

    private static boolean isWhip(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && WHIP_ID.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        ORDERS.remove(event.getEntity().getUUID());
        CREW_WAGONS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onStop(ServerStoppedEvent event) {
        ORDERS.clear();
        CREW_WAGONS.clear();
    }

    public record BoardingResult(int wagons, Set<UUID> crewIds) {
        private static final BoardingResult EMPTY = new BoardingResult(0, Set.of());
        public boolean handled(AbstractRecruitEntity recruit) {
            return recruit != null && crewIds.contains(recruit.getUUID());
        }
    }

    public record WagonControl(int speedLevel, float steeringImpulse, boolean reversing) {
        public static final WagonControl STOP = new WagonControl(0, 0.0F, false);
    }

    private static final class DriveOrder {
        final WeakReference<Entity> wagon;
        final UUID groupId;
        Vec3 target;
        final Vec3 destination;
        final float finalFacing;
        int requestedSpeedLevel;
        int effectiveSpeedLevel;
        float travelYaw;
        List<Vec3> route = List.of();
        int routeIndex;
        boolean routeComplete;
        boolean routePending;
        boolean routeFailed;

        DriveOrder(WeakReference<Entity> wagon, UUID groupId, Vec3 target,
                   float finalFacing, int requestedSpeedLevel) {
            this.wagon = wagon;
            this.groupId = groupId;
            this.target = target;
            this.destination = target;
            this.finalFacing = finalFacing;
            this.requestedSpeedLevel = requestedSpeedLevel;
            this.travelYaw = finalFacing;
        }
    }
}
