package ee.lastpagans.recruitsrtscamera.compat;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.SiegeEngineerEntity;
import com.talhanation.recruits.entities.ai.controller.siegeengineer.SiegeWeaponBallistaController;
import com.talhanation.recruits.entities.ai.controller.siegeengineer.SiegeWeaponCatapultController;
import com.talhanation.recruits.util.FormationUtils;
import com.talhanation.recruits.util.RecruitCommanderUtil;
import ee.lastpagans.recruitsrtscamera.formation.ArtilleryFormationMath;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Strictly optional bridge to Recruits' built-in Siege Weapons compatibility.
 *
 * There are deliberately no references to com.talhanation.siegeweapons classes here.
 * If the Siege Weapons mod is absent this class returns immediately and the rest of RTS
 * Camera remains completely usable.
 */
public final class SiegeWeaponsCompat {
    public static final String MOD_ID = "siegeweapons";

    private SiegeWeaponsCompat() {
    }

    public static boolean isAvailable() {
        return ModList.get().isLoaded(MOD_ID) && Main.isSiegeWeaponsLoaded && Main.isSiegeWeaponsCompatible;
    }

    public static boolean isArtilleryRecruit(AbstractRecruitEntity recruit) {
        return isAvailable() && recruit instanceof SiegeEngineerEntity engineer && isAttachedToSiegeWeapon(engineer);
    }

    public static int artilleryCrews(List<AbstractRecruitEntity> recruits) {
        if (!isAvailable() || recruits == null) {
            return 0;
        }
        int count = 0;
        for (AbstractRecruitEntity recruit : recruits) {
            if (recruit instanceof SiegeEngineerEntity) {
                count++;
            }
        }
        return count;
    }

    public static int attachedGuns(List<AbstractRecruitEntity> recruits) {
        return attachedEngineers(recruits).size();
    }

    /** Manned artillery pieces which must bypass infantry and native formation movement. */
    public static List<SiegeEngineerEntity> attachedEngineers(List<AbstractRecruitEntity> recruits) {
        if (!isAvailable() || recruits == null || recruits.isEmpty()) {
            return List.of();
        }
        List<SiegeEngineerEntity> result = new ArrayList<>();
        for (AbstractRecruitEntity recruit : recruits) {
            if (recruit instanceof SiegeEngineerEntity engineer && isAttachedToSiegeWeapon(engineer)) {
                result.add(engineer);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Recruits which can still be handed to the native formation runtime. A mounted siege engineer
     * is removed because the siege controller, not ordinary recruit navigation, owns its motion.
     */
    public static List<AbstractRecruitEntity> withoutAttachedArtillery(List<AbstractRecruitEntity> recruits) {
        if (recruits == null || recruits.isEmpty() || !isAvailable()) {
            return recruits == null ? List.of() : List.copyOf(recruits);
        }
        List<AbstractRecruitEntity> result = new ArrayList<>(recruits.size());
        for (AbstractRecruitEntity recruit : recruits) {
            if (!(recruit instanceof SiegeEngineerEntity engineer) || !isAttachedToSiegeWeapon(engineer)) {
                result.add(recruit);
            }
        }
        return List.copyOf(result);
    }

    public static double currentBatteryWidth(List<AbstractRecruitEntity> recruits, float facingYaw) {
        List<SiegeEngineerEntity> engineers = attachedEngineers(recruits);
        if (engineers.size() <= 1) {
            return 0.0D;
        }
        List<ArtilleryFormationMath.Point> positions = new ArrayList<>(engineers.size());
        for (SiegeEngineerEntity engineer : engineers) {
            Vec3 pos = gunPosition(engineer);
            positions.add(new ArtilleryFormationMath.Point(pos.x, pos.z));
        }
        double spacing = ArtilleryFormationMath.estimateSpacing(positions, facingYaw);
        return spacing * (engineers.size() - 1.0D);
    }

    /**
     * Give every manned gun its own native Recruits move destination in an RTS battery line.
     *
     * This is intentionally NOT a teleport or direct Siege Weapons control. Recruits' own
     * SiegeEngineerEntity#setFollowState(0) + move-pos path is what drives steering, forward/
     * backward motion, collision and the actual siege entity. RTS Camera only supplies sane,
     * non-overlapping destinations.
     *
     * @param requestedWidth null for a normal translated move (preserve sane current spacing),
     *                       otherwise the player's RMB frontage width for a deliberate redeploy.
     */
    public static BatteryOrder commandBatteryLine(
            ServerPlayer sender,
            List<AbstractRecruitEntity> recruits,
            Vec3 center,
            float facingYaw,
            Double requestedWidth
    ) {
        if (!isAvailable() || sender == null || recruits == null || center == null) {
            return BatteryOrder.EMPTY;
        }
        List<SiegeEngineerEntity> engineers = new ArrayList<>(attachedEngineers(recruits));
        if (engineers.isEmpty()) {
            return BatteryOrder.EMPTY;
        }

        double spacing;
        if (requestedWidth != null && Double.isFinite(requestedWidth)) {
            spacing = ArtilleryFormationMath.spacingForWidth(engineers.size(), requestedWidth);
        } else {
            List<ArtilleryFormationMath.Point> gunPositions = new ArrayList<>(engineers.size());
            for (SiegeEngineerEntity engineer : engineers) {
                Vec3 pos = gunPosition(engineer);
                gunPositions.add(new ArtilleryFormationMath.Point(pos.x, pos.z));
            }
            spacing = ArtilleryFormationMath.estimateSpacing(gunPositions, facingYaw);
        }

        List<ArtilleryFormationMath.Point> slots = ArtilleryFormationMath.lineSlots(
                engineers.size(),
                new ArtilleryFormationMath.Point(center.x, center.z),
                facingYaw,
                spacing
        );
        if (slots.size() != engineers.size()) {
            return BatteryOrder.EMPTY;
        }

        // Preserve current left-to-right order so guns do not unnecessarily cross paths.
        engineers.sort(Comparator
                .comparingDouble((SiegeEngineerEntity engineer) -> {
                    Vec3 pos = gunPosition(engineer);
                    return ArtilleryFormationMath.projection(
                            new ArtilleryFormationMath.Point(pos.x, pos.z), facingYaw
                    );
                })
                .thenComparing(engineer -> engineer.getUUID().toString()));

        int ordered = 0;
        for (int i = 0; i < engineers.size(); i++) {
            SiegeEngineerEntity engineer = engineers.get(i);
            ArtilleryFormationMath.Point slot = slots.get(i);
            BlockPos surface = FormationUtils.getPositionOrSurface(
                    sender.serverLevel(),
                    BlockPos.containing(slot.x(), center.y, slot.z())
            );
            if (surface == null || !sender.serverLevel().hasChunkAt(surface)) {
                continue;
            }

            // A movement order cancels a designated bombardment target, exactly as a player
            // would expect when redeploying a battery. Do not disable ranged fire globally;
            // the gun may resume normal Recruits behaviour after arriving.
            engineer.setShouldStrategicFire(false);
            engineer.setTarget(null);
            if (engineer.siegeController != null) {
                engineer.siegeController.setTargetPos(null);
            }

            // THIS is the native movement path Recruits' normal command system uses.
            RecruitCommanderUtil.setRecruitsMove(List.of(engineer), surface);
            ordered++;
        }

        double actualWidth = engineers.size() <= 1 ? 0.0D : spacing * (engineers.size() - 1.0D);
        return new BatteryOrder(ordered, spacing, actualWidth);
    }

    private static Vec3 gunPosition(SiegeEngineerEntity engineer) {
        if (engineer != null && engineer.siegeController != null) {
            Entity gun = engineer.siegeController.getSiegeEntity();
            if (gun != null) {
                return gun.position();
            }
        }
        return engineer == null ? Vec3.ZERO : engineer.position();
    }

    public static boolean strategicFire(SiegeEngineerEntity engineer, BlockPos target) {
        if (!isAvailable() || engineer == null || target == null || engineer.siegeController == null
                || engineer.siegeController.getSiegeEntity() == null) {
            return false;
        }
        engineer.setTarget(null);
        engineer.setShouldRanged(true);
        engineer.setStrategicFirePos(target);
        engineer.setShouldStrategicFire(true);
        engineer.siegeController.setTargetPos(Vec3.atCenterOf(target));
        return true;
    }

    public static boolean focusTarget(SiegeEngineerEntity engineer, LivingEntity target) {
        if (!isAvailable() || engineer == null || target == null || engineer.siegeController == null
                || engineer.siegeController.getSiegeEntity() == null) {
            return false;
        }
        engineer.setShouldStrategicFire(false);
        engineer.setShouldRanged(true);
        engineer.setTarget(target);
        engineer.siegeController.setTargetPos(target.position());
        return true;
    }

    /** Stop native siege steering/movement without detaching the gun or erasing RTS formation memory. */
    public static void haltEngineer(SiegeEngineerEntity engineer) {
        if (!isAttachedToSiegeWeapon(engineer)) {
            return;
        }
        try {
            engineer.siegeController.setTargetPos(null);
            engineer.siegeController.reset();
        } catch (RuntimeException | LinkageError ignored) {
            // Optional compatibility must never make a HALT order crash the RTS layer.
        }
    }

    public static boolean holdFire(SiegeEngineerEntity engineer) {
        if (!isAvailable() || engineer == null || engineer.siegeController == null) {
            return false;
        }
        engineer.setShouldStrategicFire(false);
        engineer.setShouldRanged(false);
        engineer.setTarget(null);
        engineer.siegeController.setTargetPos(null);
        return true;
    }

    public static boolean fireAtWill(SiegeEngineerEntity engineer) {
        if (!isAvailable() || engineer == null || engineer.siegeController == null
                || engineer.siegeController.getSiegeEntity() == null) {
            return false;
        }
        engineer.setShouldStrategicFire(false);
        engineer.setShouldRanged(true);
        engineer.siegeController.setTargetPos(null);
        return true;
    }

    public static boolean isAttachedToSiegeWeapon(SiegeEngineerEntity engineer) {
        return isAvailable() && engineer != null && engineer.siegeController != null
                && engineer.siegeController.getSiegeEntity() != null;
    }

    /** Recruits' siege controllers compare squared target distance against these limits. */
    public static boolean targetInRange(SiegeEngineerEntity engineer, Vec3 target) {
        if (!isAttachedToSiegeWeapon(engineer) || target == null) {
            return false;
        }
        double distanceSq = engineer.distanceToSqr(target);
        if (engineer.siegeController instanceof SiegeWeaponCatapultController) {
            return distanceSq >= SiegeWeaponCatapultController.MIN_ENGAGE_DISTANCE
                    && distanceSq <= SiegeWeaponCatapultController.MAX_ENGAGE_DISTANCE;
        }
        if (engineer.siegeController instanceof SiegeWeaponBallistaController) {
            return distanceSq >= SiegeWeaponBallistaController.MIN_ENGAGE_DISTANCE
                    && distanceSq <= SiegeWeaponBallistaController.MAX_ENGAGE_DISTANCE;
        }
        return true;
    }

    /** Best-effort state label using Recruits' own reflection-backed siege wrappers. */
    public static String firingState(SiegeEngineerEntity engineer) {
        if (!isAttachedToSiegeWeapon(engineer)) {
            return "unmanned";
        }
        try {
            if (engineer.siegeController instanceof SiegeWeaponCatapultController controller
                    && controller.catapult != null) {
                if (controller.noAmmoMessage) return "no ammunition";
                if (controller.catapult.isShooting()) return "firing";
                if (controller.catapult.isLoading()) return "loading";
                if (controller.catapult.isProjectileLoaded() || controller.catapult.isLoaded()) return "ready";
                return "unloaded";
            }
            if (engineer.siegeController instanceof SiegeWeaponBallistaController controller
                    && controller.ballista != null) {
                if (controller.noAmmoMessage) return "no ammunition";
                if (controller.ballista.isShooting()) return "firing";
                if (controller.ballista.isLoading()) return "loading";
                if (controller.ballista.isProjectileLoaded() || controller.ballista.isLoaded()) return "ready";
                return "unloaded";
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Optional compatibility must never be able to take the RTS layer down.
        }
        return "attached";
    }

    public static String gunType(SiegeEngineerEntity engineer) {
        if (!isAttachedToSiegeWeapon(engineer)) {
            return "unmanned";
        }
        Entity siege = engineer.siegeController.getSiegeEntity();
        String name = siege.getType().toString().toLowerCase(Locale.ROOT);
        if (name.contains("ballista")) {
            return "ballista";
        }
        if (name.contains("catapult")) {
            return "catapult";
        }
        return "siege weapon";
    }

    public record BatteryOrder(int guns, double spacing, double width) {
        public static final BatteryOrder EMPTY = new BatteryOrder(0, 0.0D, 0.0D);

        public boolean movedAny() {
            return guns > 0;
        }
    }
}
