package ee.lastpagans.recruitsrtscamera.projectile;

import ee.lastpagans.recruitsrtscamera.registry.RTSEntities;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.network.NetworkHooks;

/**
 * Generic recruit-thrown weapon projectile. The projectile keeps the real ItemStack so
 * Antique Legacy pila/spears and future tagged weapons render as themselves in flight.
 */
public final class ThrownRecruitWeaponEntity extends ThrowableItemProjectile {
    private float damage = 6.0F;

    public ThrownRecruitWeaponEntity(EntityType<? extends ThrownRecruitWeaponEntity> type, Level level) {
        super(type, level);
    }

    public ThrownRecruitWeaponEntity(Level level, LivingEntity owner, ItemStack stack, float damage) {
        super(RTSEntities.THROWN_RECRUIT_WEAPON.get(), owner, level);
        setItem(stack.copyWithCount(1));
        this.damage = Math.max(1.0F, damage);
    }

    @Override
    protected Item getDefaultItem() {
        return Items.TRIDENT;
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        super.onHitEntity(hit);
        if (!level().isClientSide && hit.getEntity() instanceof LivingEntity target) {
            target.hurt(level().damageSources().thrown(this, getOwner()), damage);
            discard();
        }
    }

    @Override
    protected void onHit(HitResult hit) {
        super.onHit(hit);
        if (!level().isClientSide && hit.getType() == HitResult.Type.BLOCK) {
            discard();
        }
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
