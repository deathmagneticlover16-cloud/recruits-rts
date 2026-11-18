package ee.lastpagans.recruitsrtscamera.projectile;

import ee.lastpagans.recruitsrtscamera.registry.RTSEntities;
import ee.lastpagans.recruitsrtscamera.registry.RTSSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;

/**
 * Generic recruit-thrown weapon projectile. The projectile keeps the real ItemStack so
 * Antique Legacy pila/spears and future tagged weapons render as themselves in flight.
 */
public final class ThrownRecruitWeaponEntity extends ThrowableItemProjectile {
    private static final EntityDataAccessor<Boolean> EMBEDDED = SynchedEntityData.defineId(
            ThrownRecruitWeaponEntity.class, EntityDataSerializers.BOOLEAN);
    private static final int EMBEDDED_LIFETIME_TICKS = 20 * 60;

    private float damage = 6.0F;
    private BlockPos embeddedBlock;
    private int embeddedTicks;

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
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(EMBEDDED, false);
    }

    @Override
    public void tick() {
        if (isEmbedded()) {
            // Keep normal entity bookkeeping and synchronization, but remove every source of
            // projectile motion. A zero-length trace does not re-trigger the block impact.
            float fixedYaw = getYRot();
            float fixedPitch = getXRot();
            setDeltaMovement(Vec3.ZERO);
            setNoGravity(true);
            super.tick();
            setYRot(fixedYaw);
            setXRot(fixedPitch);
            yRotO = fixedYaw;
            xRotO = fixedPitch;
            if (!level().isClientSide && (++embeddedTicks >= EMBEDDED_LIFETIME_TICKS
                    || embeddedBlock != null && level().getBlockState(embeddedBlock).isAir())) {
                discard();
            }
            return;
        }
        super.tick();
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
        if (isEmbedded()) return;
        super.onHit(hit);
        if (hit instanceof BlockHitResult blockHit && !isRemoved()) {
            Vec3 velocity = getDeltaMovement();
            double horizontal = Math.hypot(velocity.x, velocity.z);
            float yaw = (float) Math.toDegrees(Math.atan2(velocity.x, velocity.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(velocity.y, Math.max(1.0E-6D, horizontal)));
            Vec3 backwards = velocity.lengthSqr() > 1.0E-8D
                    ? velocity.normalize().scale(0.08D) : Vec3.ZERO;
            setPos(blockHit.getLocation().subtract(backwards));
            setYRot(yaw);
            setXRot(pitch);
            yRotO = yaw;
            xRotO = pitch;
            setDeltaMovement(Vec3.ZERO);
            setNoGravity(true);
            embeddedBlock = blockHit.getBlockPos();
            entityData.set(EMBEDDED, true);
            if (!level().isClientSide) {
                level().playSound(null, blockHit.getBlockPos(), RTSSounds.JAVELIN_GROUND_HIT.get(),
                        SoundSource.BLOCKS, 0.82F, 0.84F + random.nextFloat() * 0.32F);
            }
        }
    }

    public boolean isEmbedded() {
        return entityData.get(EMBEDDED);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("RTSDamage", damage);
        tag.putBoolean("RTSEmbedded", isEmbedded());
        tag.putInt("RTSEmbeddedTicks", embeddedTicks);
        if (embeddedBlock != null) tag.putLong("RTSEmbeddedBlock", embeddedBlock.asLong());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("RTSDamage")) damage = Math.max(1.0F, tag.getFloat("RTSDamage"));
        embeddedTicks = Math.max(0, tag.getInt("RTSEmbeddedTicks"));
        if (tag.contains("RTSEmbeddedBlock")) embeddedBlock = BlockPos.of(tag.getLong("RTSEmbeddedBlock"));
        if (tag.getBoolean("RTSEmbedded")) {
            entityData.set(EMBEDDED, true);
            setDeltaMovement(Vec3.ZERO);
            setNoGravity(true);
        }
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
