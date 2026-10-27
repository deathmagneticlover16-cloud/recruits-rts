package ee.lastpagans.recruitsrtscamera.troops;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.BowmanEntity;
import com.talhanation.recruits.entities.CrossBowmanEntity;
import com.talhanation.recruits.entities.NomadEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.combat.RangedWeaponEligibility;
import ee.lastpagans.recruitsrtscamera.config.RTSServerConfig;
import ee.lastpagans.recruitsrtscamera.formation.FormationKind;
import ee.lastpagans.recruitsrtscamera.formation.FormationService;
import ee.lastpagans.recruitsrtscamera.registry.RTSTags;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server-authoritative live troop statistics and full-inventory encumbrance. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TroopStatService {
    private static final UUID ENCUMBRANCE_SPEED_ID = UUID.fromString("a980324a-29ed-43dd-a05c-57afcce94702");
    private static final String WEIGHT_KEY = "RecruitsRTSCarriedWeight";
    private static final String CAPACITY_KEY = "RecruitsRTSCarryCapacity";
    private static final String LOAD_RATIO_KEY = "RecruitsRTSLoadRatio";
    private static final Map<UUID, LiveState> LIVE = new HashMap<>();

    private TroopStatService() { }

    public static SoldierStats sample(AbstractRecruitEntity recruit, double fatigue) {
        if (recruit == null || !recruit.isAlive()) return SoldierStats.EMPTY;
        Load load = calculateLoad(recruit);
        double ratio = load.capacity <= 0.0D ? 0.0D : load.weight / load.capacity;
        double movementPenalty = RTSServerConfig.ENCUMBRANCE_ENABLED.get()
                ? Math.min(RTSServerConfig.ENCUMBRANCE_MAX_SPEED_PENALTY.get(), movementPenalty(ratio)) : 0.0D;
        LivingEntity carrier = recruit.getVehicle() instanceof LivingEntity living ? living : recruit;
        if (carrier != recruit) movementPenalty *= 0.65D;
        applyCarrier(recruit, carrier, -movementPenalty);

        recruit.getPersistentData().putDouble(WEIGHT_KEY, load.weight);
        recruit.getPersistentData().putDouble(CAPACITY_KEY, load.capacity);
        recruit.getPersistentData().putDouble(LOAD_RATIO_KEY, ratio);

        double excess = Math.max(0.0D, ratio - 0.55D);
        double fatigueGain = RTSServerConfig.ENCUMBRANCE_ENABLED.get()
                ? Math.min(3.0D, 1.0D + excess * RTSServerConfig.ENCUMBRANCE_FATIGUE_SCALE.get()) : 1.0D;
        double fatigueRecovery = 1.0D / (1.0D + excess * 1.60D);
        if (!RTSServerConfig.ENCUMBRANCE_ENABLED.get()) fatigueRecovery = 1.0D;
        TroopStatProfile profile = liveProfile(recruit, fatigue, load.ammunition, carrier);
        SoldierStats result = new SoldierStats(profile, load.weight, load.capacity, ratio,
                fatigueGain, fatigueRecovery, load.ammunition, load.food);
        LiveState state = LIVE.computeIfAbsent(recruit.getUUID(), ignored -> new LiveState());
        state.stats = result;
        state.sampleTick = recruit.level().getGameTime();
        return result;
    }

    public static SoldierStats current(AbstractRecruitEntity recruit) {
        if (recruit == null) return SoldierStats.EMPTY;
        LiveState state = LIVE.get(recruit.getUUID());
        if (state != null && state.sampleTick >= recruit.level().getGameTime() - 10L) return state.stats;
        double fatigue = recruit.getPersistentData().getDouble("RecruitsRTSCameraFatigue");
        return sample(recruit, fatigue);
    }

    /** Rebuild dynamic combat values after fatigue changes without rescanning the inventory. */
    public static SoldierStats updateFatigue(AbstractRecruitEntity recruit, double fatigue) {
        SoldierStats old = current(recruit);
        LivingEntity carrier = recruit.getVehicle() instanceof LivingEntity living ? living : recruit;
        SoldierStats updated = new SoldierStats(
                liveProfile(recruit, fatigue, old.ammunition(), carrier),
                old.carriedWeight(), old.capacity(), old.loadRatio(),
                old.fatigueGainMultiplier(), old.fatigueRecoveryMultiplier(), old.ammunition(), old.food());
        LiveState state = LIVE.computeIfAbsent(recruit.getUUID(), ignored -> new LiveState());
        state.stats = updated;
        state.sampleTick = recruit.level().getGameTime();
        return updated;
    }

    public static double chargeMultiplier(AbstractRecruitEntity recruit) {
        if (recruit == null) return 1.0D;
        double baseline = recruit.getVehicle() instanceof LivingEntity ? 68.0D : 30.0D;
        return Math.max(0.65D, Math.min(1.35D,
                current(recruit).profile().value(TroopStatistic.CHARGE_BONUS) / baseline));
    }

    private static TroopStatProfile liveProfile(
            AbstractRecruitEntity recruit, double fatigue, int ammunition, LivingEntity carrier
    ) {
        ItemStack weapon = recruit.getMainHandItem();
        double level = Math.max(0, recruit.getXpLevel());
        double damage = recruit.getAttackDamage();
        if (weapon.getItem() instanceof BowItem) damage = 6.0D;
        else if (weapon.getItem() instanceof CrossbowItem) damage = 9.0D;
        else if (weapon.getItem() instanceof TridentItem) damage = 8.0D;

        double meleeSkill = 44.0D + Math.min(30.0D, level * 3.0D) - fatigue * 0.16D;
        double meleeDefence = 24.0D + recruit.getArmorValue() * 2.4D
                + (recruit.getOffhandItem().getItem() instanceof ShieldItem ? 18.0D : 0.0D)
                + Math.min(16.0D, level * 2.0D) - fatigue * 0.13D;
        boolean mounted = recruit.getVehicle() instanceof LivingEntity;
        double charge = mounted ? 68.0D : 30.0D;
        if (recruit.level() instanceof ServerLevel levelServer && recruit.getGroup() != null
                && FormationService.activeKind(levelServer, recruit.getGroup()) == FormationKind.CAVALRY_WEDGE) {
            charge += 17.0D;
        }
        AttributeInstance movement = carrier.getAttribute(Attributes.MOVEMENT_SPEED);
        double speed = movement == null ? 0.0D : movement.getValue() * 200.0D;
        double accuracy = rangedAccuracy(recruit, weapon, fatigue, level);
        double rateOfFire = rangedRate(weapon);
        return TroopStatProfile.builder()
                .set(TroopStatistic.DAMAGE, damage)
                .set(TroopStatistic.MELEE_SKILL, meleeSkill)
                .set(TroopStatistic.MELEE_DEFENCE, meleeDefence)
                .set(TroopStatistic.CHARGE_BONUS, charge)
                .set(TroopStatistic.SPEED, speed)
                .set(TroopStatistic.MORALE, recruit.getMorale())
                .set(TroopStatistic.FATIGUE, fatigue)
                .set(TroopStatistic.ACCURACY, accuracy)
                .set(TroopStatistic.AMMUNITION, ammunition)
                .set(TroopStatistic.RATE_OF_FIRE, rateOfFire)
                .build();
    }

    private static double rangedAccuracy(AbstractRecruitEntity recruit, ItemStack weapon, double fatigue, double level) {
        if (!RangedWeaponEligibility.hasRangedWeapon(recruit)) return 0.0D;
        double base = weapon.getItem() instanceof CrossbowItem ? 68.0D
                : weapon.getItem() instanceof BowItem ? 62.0D
                : weapon.getItem() instanceof TridentItem ? 55.0D : 48.0D;
        return base + Math.min(24.0D, level * 2.5D) - fatigue * 0.20D;
    }

    private static double rangedRate(ItemStack weapon) {
        return weapon.getItem() instanceof BowItem ? 72.0D
                : weapon.getItem() instanceof CrossbowItem ? 44.0D
                : weapon.getItem() instanceof TridentItem ? 34.0D : 0.0D;
    }

    private static Load calculateLoad(AbstractRecruitEntity recruit) {
        Container inventory = recruit.getInventory();
        if (inventory == null) return new Load(0.0D, capacity(recruit), 0, 0);
        double total = 0.0D;
        int ammunition = 0;
        int food = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty()) continue;
            double weight = stackWeight(stack);
            total += weight;
            if (isAmmunition(stack)) ammunition += stack.getCount();
            if (stack.getItem().getFoodProperties() != null) food += stack.getCount();
        }
        return new Load(total, capacity(recruit), ammunition, food);
    }

    private static double capacity(AbstractRecruitEntity recruit) {
        double capacity = recruit instanceof BowmanEntity || recruit instanceof CrossBowmanEntity
                || recruit instanceof NomadEntity ? RTSServerConfig.ENCUMBRANCE_RANGED_CAPACITY.get()
                : RTSServerConfig.ENCUMBRANCE_FOOT_CAPACITY.get();
        if (recruit.getVehicle() instanceof LivingEntity) capacity += RTSServerConfig.ENCUMBRANCE_MOUNTED_BONUS.get();
        capacity += Math.min(8.0D, Math.max(0, recruit.getXpLevel()) * 0.8D);
        return capacity;
    }

    private static double stackWeight(ItemStack stack) {
        int count = stack.getCount();
        if (stack.is(RTSTags.ENCUMBRANCE_WEIGHTLESS)) return 0.0D;
        if (stack.is(RTSTags.ENCUMBRANCE_LIGHT)) return count * 0.05D;
        if (stack.is(RTSTags.ENCUMBRANCE_HEAVY)) return count * 1.0D;
        if (stack.is(RTSTags.ENCUMBRANCE_VERY_HEAVY)) return count * 2.5D;
        Item item = stack.getItem();
        if (stack.is(ItemTags.ARROWS) || isNamedAmmunition(stack)) return count * 0.04D;
        if (item instanceof ArmorItem armor) return count * (1.5D + armor.getDefense() * 1.15D);
        if (item instanceof ShieldItem) return count * 6.0D;
        if (item instanceof CrossbowItem) return count * 5.0D;
        if (item instanceof BowItem) return count * 2.5D;
        if (item instanceof TridentItem) return count * 7.0D;
        if (item instanceof AxeItem) return count * 6.0D;
        if (item instanceof SwordItem) return count * 4.0D;
        if (item instanceof DiggerItem) return count * 5.0D;
        if (item instanceof ProjectileWeaponItem) return count * 4.0D;
        if (item instanceof PotionItem) return count * 0.8D;
        if (item.getFoodProperties() != null) {
            return count * (0.06D + item.getFoodProperties().getNutrition() * 0.015D);
        }
        if (item instanceof BlockItem) return count * 0.18D;
        return count * (stack.getMaxStackSize() <= 1 ? 1.25D : 0.05D);
    }

    private static boolean isAmmunition(ItemStack stack) {
        return stack.is(ItemTags.ARROWS) || isNamedAmmunition(stack)
                || stack.is(RTSTags.THROWABLE_WEAPONS);
    }

    private static boolean isNamedAmmunition(ItemStack stack) {
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return path.contains("bolt") || path.contains("cartridge") || path.contains("bullet")
                || path.contains("cannon_ball");
    }

    private static double movementPenalty(double ratio) {
        if (ratio <= 0.65D) return 0.0D;
        if (ratio <= 1.0D) return (ratio - 0.65D) / 0.35D * 0.08D;
        if (ratio <= 1.5D) return 0.08D + (ratio - 1.0D) / 0.5D * 0.20D;
        return Math.min(0.48D, 0.28D + (ratio - 1.5D) * 0.20D);
    }

    private static void applyCarrier(AbstractRecruitEntity recruit, LivingEntity carrier, double amount) {
        LiveState state = LIVE.computeIfAbsent(recruit.getUUID(), ignored -> new LiveState());
        if (state.carrier != null && state.carrier != carrier) removeModifier(state.carrier);
        state.carrier = carrier;
        AttributeInstance speed = carrier.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        AttributeModifier existing = speed.getModifier(ENCUMBRANCE_SPEED_ID);
        if (existing != null && Math.abs(existing.getAmount() - amount) <= 1.0E-4D) return;
        if (existing != null) speed.removeModifier(ENCUMBRANCE_SPEED_ID);
        if (amount < -1.0E-5D) speed.addTransientModifier(new AttributeModifier(
                ENCUMBRANCE_SPEED_ID, "RTS inventory encumbrance", amount,
                AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    private static void removeModifier(LivingEntity entity) {
        if (entity == null) return;
        AttributeInstance speed = entity.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(ENCUMBRANCE_SPEED_ID);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMeleeAttack(LivingAttackEvent event) {
        if (event.getEntity().level().isClientSide
                || !(event.getSource().getEntity() instanceof AbstractRecruitEntity attacker)
                || event.getSource().getDirectEntity() != attacker) return;
        double skill = current(attacker).profile().value(TroopStatistic.MELEE_SKILL);
        double defence = event.getEntity() instanceof AbstractRecruitEntity defender
                ? current(defender).profile().value(TroopStatistic.MELEE_DEFENCE) : 35.0D;
        double chance = Math.max(0.65D, Math.min(0.99D, 0.92D + (skill - defence) * 0.003D));
        if (attacker.getRandom().nextDouble() > chance) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onProjectile(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide || !(event.getEntity() instanceof net.minecraft.world.entity.projectile.Projectile projectile)
                || !(projectile.getOwner() instanceof AbstractRecruitEntity shooter)) return;
        LivingEntity target = shooter.getTarget();
        if (target == null || !target.isAlive()) return;
        Vec3 velocity = projectile.getDeltaMovement();
        double speed = velocity.length();
        if (speed < 1.0E-4D) return;
        double accuracy = current(shooter).profile().value(TroopStatistic.ACCURACY);
        Vec3 desired = target.getEyePosition().subtract(projectile.position()).normalize().scale(speed);
        if (accuracy > 50.0D) {
            projectile.setDeltaMovement(velocity.lerp(desired, Math.min(0.28D, (accuracy - 50.0D) * 0.005D)));
        } else if (accuracy < 50.0D) {
            double spread = (50.0D - accuracy) / 50.0D * speed * 0.035D;
            projectile.setDeltaMovement(velocity.add(
                    shooter.getRandom().nextGaussian() * spread,
                    shooter.getRandom().nextGaussian() * spread * 0.5D,
                    shooter.getRandom().nextGaussian() * spread));
        }
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof AbstractRecruitEntity recruit) {
            LiveState state = LIVE.remove(recruit.getUUID());
            if (state != null) removeModifier(state.carrier);
        }
    }

    @SubscribeEvent
    public static void onStop(ServerStoppedEvent event) {
        LIVE.values().forEach(state -> removeModifier(state.carrier));
        LIVE.clear();
    }

    public record SoldierStats(
            TroopStatProfile profile,
            double carriedWeight,
            double capacity,
            double loadRatio,
            double fatigueGainMultiplier,
            double fatigueRecoveryMultiplier,
            int ammunition,
            int food
    ) {
        private static final SoldierStats EMPTY = new SoldierStats(
                TroopStatProfile.NEUTRAL, 0.0D, 1.0D, 0.0D, 1.0D, 1.0D, 0, 0);
    }

    private record Load(double weight, double capacity, int ammunition, int food) { }

    private static final class LiveState {
        LivingEntity carrier;
        SoldierStats stats = SoldierStats.EMPTY;
        long sampleTick = Long.MIN_VALUE;
    }
}
