package ee.lastpagans.recruitsrtscamera.compat;

import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * Optional restoration bridge for Trotting Wagons 1.0.4.
 *
 * <p>The published jar predates the GitHub Combat Wagon and contains none of its entity,
 * item, menu or rendering registrations. RTS registers a unique combat variant only when
 * Trotting Wagons is actually installed. All contact with its Java API is reflective, so
 * a server or client without the optional mod never resolves one of its classes.</p>
 */
public final class TrottingWagonsCompat {
    public static final String MOD_ID = "trotting_wagons";
    public static final String ENTITY_PATH = "scythed_combat_wagon";
    public static final String MODEL_PATH = "rts_scythed_wagon";

    public static final double BASE_SPEED = 0.23D;
    public static final double ACCELERATION_SECONDS = 5.0D;
    public static final float TURN_RATE = 4.0F;
    public static final int MAX_HEALTH = 80;
    public static final double DAMAGE_RANGE = 1.5D;
    public static final double DAMAGE_PER_SPEED_LEVEL = 8.0D;

    private static final Vec3[] RIDERS = {
            new Vec3(0.0D, 1.25D, 0.0D),
            new Vec3(0.0D, 0.0D, 0.0D)
    };
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(
            ForgeRegistries.ENTITY_TYPES, RecruitsRTSCameraMod.MOD_ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(
            ForgeRegistries.ITEMS, RecruitsRTSCameraMod.MOD_ID);

    private static RegistryObject<EntityType<Entity>> scythedWagon;
    private static RegistryObject<Item> scythedWagonItem;
    private static Constructor<?> armoredWagonConstructor;
    private static Constructor<?> wagonItemConstructor;
    private static Field speedField;
    private static Field accelerationField;
    private static Field turnRateField;
    private static Field maxHealthField;
    private static Field currentSpeedField;
    private static Field horsesField;
    private static Field ridersField;
    private static Method setHealthMethod;
    private static Method getColorMethod;
    private static boolean registered;

    private TrottingWagonsCompat() {
    }

    public static void register(IEventBus modBus) {
        if (registered || modBus == null || !ModList.get().isLoaded(MOD_ID)) return;
        if (!resolveApi()) {
            RecruitsRTSCameraMod.LOGGER.warn(
                    "Trotting Wagons is installed, but its 1.20.1 wagon API is incompatible; "
                            + "the RTS scythed combat wagon will stay disabled.");
            return;
        }

        registered = true;
        scythedWagon = ENTITIES.register(ENTITY_PATH, () -> EntityType.Builder
                .<Entity>of(TrottingWagonsCompat::createWagon, MobCategory.MISC)
                .sized(3.0F, 2.9F)
                .clientTrackingRange(10)
                .build(ENTITY_PATH));
        scythedWagonItem = ITEMS.register(ENTITY_PATH, TrottingWagonsCompat::createWagonItem);
        ENTITIES.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(TrottingWagonsCompat::addCreativeItem);
    }

    public static boolean isAvailable() {
        return registered && ModList.get().isLoaded(MOD_ID)
                && scythedWagon != null && scythedWagon.isPresent();
    }

    public static EntityType<?> entityType() {
        return isAvailable() ? scythedWagon.get() : null;
    }

    public static boolean isScythedWagon(Entity entity) {
        return entity != null && isAvailable() && entity.getType() == scythedWagon.get();
    }

    public static ItemStack createItemStack() {
        return createItemStack(DyeColor.WHITE);
    }

    public static ItemStack createItemStack(DyeColor color) {
        if (!isAvailable() || scythedWagonItem == null || !scythedWagonItem.isPresent()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(scythedWagonItem.get());
        CompoundTag tag = stack.getOrCreateTag();
        tag.putInt("color", (color == null ? DyeColor.WHITE : color).getId());
        return stack;
    }

    public static double currentSpeed(Entity wagon) {
        if (!isScythedWagon(wagon) || currentSpeedField == null) return 0.0D;
        try {
            return currentSpeedField.getDouble(wagon);
        } catch (IllegalAccessException | RuntimeException exception) {
            return wagon.getDeltaMovement().horizontalDistance();
        }
    }

    public static Mob[] pullingHorses(Entity wagon) {
        if (!isScythedWagon(wagon) || horsesField == null) return new Mob[0];
        try {
            Object value = horsesField.get(wagon);
            return value instanceof Mob[] horses ? horses : new Mob[0];
        } catch (IllegalAccessException | RuntimeException exception) {
            return new Mob[0];
        }
    }

    public static DyeColor color(Entity wagon) {
        if (!isScythedWagon(wagon) || getColorMethod == null) return DyeColor.WHITE;
        try {
            Object value = getColorMethod.invoke(wagon);
            return value instanceof DyeColor color ? color : DyeColor.WHITE;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return DyeColor.WHITE;
        }
    }

    private static boolean resolveApi() {
        try {
            Class<?> armored = Class.forName(
                    "net.favouriteless.trotting_wagons.common.entities.wagons.ArmoredWagon",
                    false,
                    TrottingWagonsCompat.class.getClassLoader());
            Class<?> wagonItem = Class.forName(
                    "net.favouriteless.trotting_wagons.common.items.WagonItem",
                    false,
                    TrottingWagonsCompat.class.getClassLoader());
            Class<?> abstractWagon = armored.getSuperclass();
            Class<?> abstractVehicle = abstractWagon.getSuperclass();
            armoredWagonConstructor = armored.getConstructor(EntityType.class, Level.class);
            wagonItemConstructor = wagonItem.getConstructor(Supplier.class, Item.Properties.class);
            speedField = field(abstractWagon, "speed");
            accelerationField = field(abstractWagon, "acceleration");
            turnRateField = field(abstractWagon, "turnRate");
            maxHealthField = field(abstractWagon, "maxHealth");
            currentSpeedField = field(abstractWagon, "currentSpeed");
            horsesField = field(abstractWagon, "horses");
            ridersField = field(abstractVehicle, "riders");
            setHealthMethod = method(abstractWagon, "setHealth", float.class);
            getColorMethod = method(abstractWagon, "getColor");
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            RecruitsRTSCameraMod.LOGGER.warn("Could not resolve the optional Trotting Wagons API", exception);
            armoredWagonConstructor = null;
            wagonItemConstructor = null;
            return false;
        }
    }

    private static Entity createWagon(EntityType<Entity> type, Level level) {
        try {
            Object created = armoredWagonConstructor.newInstance(type, level);
            if (!(created instanceof Entity entity)) throw new IllegalStateException("Wagon is not an entity");
            speedField.setDouble(entity, BASE_SPEED);
            accelerationField.setDouble(entity, ACCELERATION_SECONDS);
            turnRateField.setFloat(entity, TURN_RATE);
            maxHealthField.setInt(entity, MAX_HEALTH);
            ridersField.set(entity, RIDERS.clone());
            setHealthMethod.invoke(entity, (float) MAX_HEALTH);
            return entity;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IllegalStateException("Could not create the RTS scythed combat wagon", exception);
        }
    }

    private static Item createWagonItem() {
        try {
            Supplier<EntityType<?>> entitySupplier = () -> scythedWagon.get();
            Object created = wagonItemConstructor.newInstance(
                    entitySupplier,
                    new Item.Properties().stacksTo(1));
            if (created instanceof Item item) return item;
            throw new IllegalStateException("Trotting Wagons returned a non-item wagon item");
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Could not create the RTS scythed combat wagon item", exception);
        }
    }

    private static void addCreativeItem(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(CreativeModeTabs.REDSTONE_BLOCKS) && isAvailable()) {
            event.accept(createItemStack());
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field result = owner.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        Method result = owner.getDeclaredMethod(name, parameters);
        result.setAccessible(true);
        return result;
    }
}
