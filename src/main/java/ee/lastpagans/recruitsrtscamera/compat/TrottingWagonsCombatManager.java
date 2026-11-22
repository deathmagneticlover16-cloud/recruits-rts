package ee.lastpagans.recruitsrtscamera.compat;

import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Server-side scythe collision for the optional restored Combat Wagon. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TrottingWagonsCombatManager {
    private static final Map<UUID, WeakReference<Entity>> WAGONS = new HashMap<>();
    private static final Map<UUID, Set<UUID>> CONTACTS = new HashMap<>();

    private TrottingWagonsCombatManager() {
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && TrottingWagonsCompat.isScythedWagon(event.getEntity())) {
            WAGONS.put(event.getEntity().getUUID(), new WeakReference<>(event.getEntity()));
        }
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        UUID id = event.getEntity().getUUID();
        WAGONS.remove(id);
        CONTACTS.remove(id);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || WAGONS.isEmpty()) return;
        for (Map.Entry<UUID, WeakReference<Entity>> entry : List.copyOf(WAGONS.entrySet())) {
            Entity wagon = entry.getValue().get();
            if (wagon == null || wagon.isRemoved() || !wagon.isAlive()
                    || !(wagon.level() instanceof ServerLevel level)
                    || !TrottingWagonsCompat.isScythedWagon(wagon)) {
                WAGONS.remove(entry.getKey(), entry.getValue());
                CONTACTS.remove(entry.getKey());
                continue;
            }
            applyScythes(level, wagon, CONTACTS.computeIfAbsent(entry.getKey(), ignored -> new HashSet<>()));
        }
    }

    private static void applyScythes(ServerLevel level, Entity wagon, Set<UUID> previousContacts) {
        LivingEntity controller = wagon.getControllingPassenger();
        if (controller == null || !controller.isAlive()) {
            previousContacts.clear();
            return;
        }

        int damage = ScythedWagonMath.contactDamage(
                TrottingWagonsCompat.currentSpeed(wagon),
                TrottingWagonsCompat.BASE_SPEED,
                TrottingWagonsCompat.DAMAGE_PER_SPEED_LEVEL);
        List<LivingEntity> nearby = level.getEntitiesOfClass(
                LivingEntity.class,
                wagon.getBoundingBox().inflate(TrottingWagonsCompat.DAMAGE_RANGE),
                target -> validTarget(wagon, target));
        Set<UUID> currentContacts = new HashSet<>(nearby.size());

        for (LivingEntity target : nearby) {
            currentContacts.add(target.getUUID());
            if (damage <= 0 || previousContacts.contains(target.getUUID())) continue;
            // Recruits rejects allied mob/player attack sources before damage reaches the
            // victim. Scythes are environmental vehicle contact, not the driver's melee hit;
            // an unattributed source therefore preserves deliberate faction friendly fire.
            target.hurt(wagon.damageSources().generic(), damage);
            previousContacts.add(target.getUUID());
            // Deliberately no owner, team or alliance test: poor chariot handling can cut
            // through friendly troops, preserving the intended counterweight to its damage.
        }

        previousContacts.retainAll(currentContacts);
    }

    private static boolean validTarget(Entity wagon, LivingEntity target) {
        if (!target.isAlive() || wagon.getPassengers().contains(target)) return false;
        if (TrottingWagonsDrivingManager.protectedConvoyMember(wagon, target)) return false;
        Mob[] horses = TrottingWagonsCompat.pullingHorses(wagon);
        if (horses != null) {
            for (Mob horse : horses) {
                if (target == horse) return false;
            }
        }
        return true;
    }

    @SubscribeEvent
    public static void onStop(ServerStoppedEvent event) {
        WAGONS.clear();
        CONTACTS.clear();
    }
}
