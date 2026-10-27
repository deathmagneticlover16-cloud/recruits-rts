package ee.lastpagans.recruitsrtscamera.compat;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.TridentItem;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Optional, classloader-safe integrations for installed equipment mods. */
public final class TroopEquipmentCompat {
    private static final String MUSKET_NAMESPACE = "musketmod";
    private static final Set<String> MUSKET_GUNS = Set.of(
            "musket", "musket_with_bayonet", "musket_with_scope", "blunderbuss", "pistol"
    );
    private static final Set<String> EPIC_KNIGHTS_NAMESPACES = Set.of(
            "magistuarmory", "slavicarmory", "darkagesarmory", "magistuarmoryaddon", "antiquelegacy"
    );
    private static final Map<MethodKey, Optional<Method>> METHODS = new ConcurrentHashMap<>();

    private TroopEquipmentCompat() { }

    /** Musket Rest moves a quiet crossbowman's long gun into its offhand. */
    public static ItemStack equippedCombatWeapon(AbstractRecruitEntity recruit) {
        if (recruit == null) return ItemStack.EMPTY;
        ItemStack main = recruit.getMainHandItem();
        ItemStack off = recruit.getOffhandItem();
        if (isMusketGun(off) && (main == null || main.isEmpty() || !isRangedWeapon(main))) return off;
        if (main != null && !main.isEmpty()) return main;
        return off == null ? ItemStack.EMPTY : off;
    }

    public static boolean isRangedWeapon(ItemStack stack) {
        return stack != null && !stack.isEmpty() && (stack.getItem() instanceof ProjectileWeaponItem
                || stack.getItem() instanceof TridentItem || isMusketGun(stack));
    }

    public static boolean isMusketGun(ItemStack stack) {
        ResourceLocation id = id(stack);
        return id != null && MUSKET_NAMESPACE.equals(id.getNamespace()) && MUSKET_GUNS.contains(id.getPath());
    }

    public static boolean isMusketCartridge(ItemStack stack) {
        ResourceLocation id = id(stack);
        return id != null && MUSKET_NAMESPACE.equals(id.getNamespace()) && "cartridge".equals(id.getPath());
    }

    /** Musket Mod defines the bayonet value as total melee damage, not bullet damage. */
    public static double meleeDamage(ItemStack stack, double fallback) {
        ResourceLocation id = id(stack);
        if (id == null || !MUSKET_NAMESPACE.equals(id.getNamespace())
                || !"musket_with_bayonet".equals(id.getPath())) return fallback;
        try {
            Class<?> config = Class.forName("ewewukek.musketmod.Config", false,
                    stack.getItem().getClass().getClassLoader());
            Field field = config.getField("bayonetDamage");
            Object value = field.get(null);
            return value instanceof Number number ? number.doubleValue() : fallback;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return 5.0D;
        }
    }

    /** Converts Musket Mod's configured angular standard deviation into the shared 0-100 display. */
    public static double rangedAccuracy(ItemStack stack) {
        if (!isMusketGun(stack)) return Double.NaN;
        double deviation = invokeNumber(stack.getItem(), "bulletStdDev");
        return Double.isFinite(deviation) ? Math.max(0.0D, Math.min(100.0D, 100.0D - deviation * 25.0D))
                : Double.NaN;
    }

    /** Reports configured shots/minute from GunItem.getLoadingDuration, including Quick Charge. */
    public static double rangedRateOfFire(ItemStack stack) {
        if (!isMusketGun(stack)) return Double.NaN;
        Object duration = invoke(stack.getItem(), "getLoadingDuration", new Class<?>[]{ItemStack.class}, stack);
        if (duration == null) return Double.NaN;
        double stages = invokeNumber(duration, "getLeft");
        double ticksPerStage = invokeNumber(duration, "getRight");
        double ticks = stages * ticksPerStage;
        return Double.isFinite(ticks) && ticks > 0.0D ? 1200.0D / ticks : Double.NaN;
    }

    /**
     * Epic Knights and all supplied armor add-ons inherit the core weapon/shield implementations.
     * Their configured getWeight() value is already expressed in kilograms.
     */
    public static double explicitEquipmentWeight(ItemStack stack) {
        ResourceLocation id = id(stack);
        if (id == null) return Double.NaN;
        if (isMusketGun(stack)) {
            return switch (id.getPath()) {
                case "pistol" -> 3.5D;
                case "blunderbuss" -> 8.0D;
                default -> 7.0D;
            };
        }
        if (!EPIC_KNIGHTS_NAMESPACES.contains(id.getNamespace())) return Double.NaN;
        double kilograms = invokeNumber(stack.getItem(), "getWeight");
        return Double.isFinite(kilograms) && kilograms > 0.0D ? kilograms : Double.NaN;
    }

    public static boolean isEpicKnightsItem(ItemStack stack) {
        ResourceLocation id = id(stack);
        return id != null && EPIC_KNIGHTS_NAMESPACES.contains(id.getNamespace());
    }

    private static ResourceLocation id(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        return BuiltInRegistries.ITEM.getKey(stack.getItem());
    }

    private static double invokeNumber(Object target, String name) {
        Object result = invoke(target, name, new Class<?>[0]);
        return result instanceof Number number ? number.doubleValue() : Double.NaN;
    }

    private static Object invoke(Object target, String name, Class<?>[] parameters, Object... arguments) {
        if (target == null) return null;
        Class<?> type = target instanceof Class<?> clazz ? clazz : target.getClass();
        Method method = METHODS.computeIfAbsent(new MethodKey(type, name, ListKey.of(parameters)),
                ignored -> find(type, name, parameters)).orElse(null);
        if (method == null) return null;
        try {
            return method.invoke(Modifier.isStatic(method.getModifiers()) ? null : target, arguments);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Optional<Method> find(Class<?> type, String name, Class<?>[] parameters) {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            try {
                Method method = cursor.getDeclaredMethod(name, parameters);
                if (!method.trySetAccessible()) return Optional.empty();
                return Optional.of(method);
            } catch (NoSuchMethodException ignored) {
                // Continue through the optional mod's class hierarchy.
            }
        }
        return Optional.empty();
    }

    private record MethodKey(Class<?> type, String name, ListKey parameters) { }

    private record ListKey(java.util.List<Class<?>> values) {
        static ListKey of(Class<?>[] values) { return new ListKey(java.util.List.of(values)); }
    }
}
