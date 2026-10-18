package ee.lastpagans.recruitsrtscamera.combat;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.BowmanEntity;
import com.talhanation.recruits.entities.CrossBowmanEntity;
import com.talhanation.recruits.entities.NomadEntity;
import com.talhanation.recruits.entities.SiegeEngineerEntity;
import ee.lastpagans.recruitsrtscamera.registry.RTSTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.TridentItem;

/** Shared client/server eligibility for Fire At Will and ranged-majority Skirmish. */
public final class RangedWeaponEligibility {
    private RangedWeaponEligibility() {
    }

    public static boolean hasRangedWeapon(AbstractRecruitEntity recruit) {
        if (recruit == null) return false;
        if (recruit instanceof BowmanEntity || recruit instanceof CrossBowmanEntity
                || recruit instanceof NomadEntity || recruit instanceof SiegeEngineerEntity) return true;
        if (isRangedWeapon(recruit.getMainHandItem()) || isRangedWeapon(recruit.getOffhandItem())) return true;
        Container inventory = recruit.getInventory();
        if (inventory == null) return false;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (isRangedWeapon(inventory.getItem(slot))) return true;
        }
        return false;
    }

    private static boolean isRangedWeapon(ItemStack stack) {
        return stack != null && !stack.isEmpty() && (stack.getItem() instanceof ProjectileWeaponItem
                || stack.getItem() instanceof TridentItem || stack.is(RTSTags.THROWABLE_WEAPONS));
    }
}
