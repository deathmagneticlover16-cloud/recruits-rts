package ee.lastpagans.recruitsrtscamera.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import ee.lastpagans.recruitsrtscamera.projectile.ThrownRecruitWeaponEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * World-space renderer for recruit-thrown weapons.
 *
 * Vanilla ThrownItemRenderer deliberately billboards an ItemStack toward the camera, which is
 * ideal for snowballs/potions but makes a long spear look like its inventory icon flying across
 * the screen. Commander instead treats the item's long axis as a physical shaft: it is rotated
 * into the projectile velocity, pitched with the ballistic arc and rendered a little larger so
 * volleys remain readable in cinematic RTS shots.
 */
public final class ThrownRecruitWeaponRenderer extends EntityRenderer<ThrownRecruitWeaponEntity> {
    private static final float WORLD_SCALE = 1.65F;
    private static final float DIAGONAL_ITEM_CORRECTION = -45.0F;

    private final ItemRenderer itemRenderer;

    public ThrownRecruitWeaponRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.itemRenderer = context.getItemRenderer();
        this.shadowRadius = 0.0F;
    }

    @Override
    public void render(ThrownRecruitWeaponEntity entity, float entityYaw, float partialTicks,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        ItemStack stack = entity.getItem();
        if (stack == null || stack.isEmpty()) {
            super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
            return;
        }

        Vec3 velocity = entity.getDeltaMovement();
        double horizontal = Math.hypot(velocity.x, velocity.z);
        float yaw;
        float elevation;
        if (velocity.lengthSqr() > 1.0E-6D) {
            yaw = (float) Math.toDegrees(Math.atan2(velocity.x, velocity.z));
            elevation = (float) Math.toDegrees(Math.atan2(velocity.y, Math.max(1.0E-6D, horizontal)));
        } else {
            yaw = entity.getYRot();
            elevation = -entity.getXRot();
        }

        poseStack.pushPose();
        // Face the shaft into the direction of travel. Generated Minecraft-style weapon art is
        // normally drawn bottom-left -> top-right, hence the final 45 degree correction that
        // turns its visual long axis into local +Y before the ballistic rotations are applied.
        poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
        poseStack.mulPose(Axis.XP.rotationDegrees(90.0F - elevation));
        poseStack.mulPose(Axis.ZP.rotationDegrees(DIAGONAL_ITEM_CORRECTION));
        poseStack.scale(WORLD_SCALE, WORLD_SCALE, WORLD_SCALE);

        // FIXED renders the actual baked/custom item model in world space rather than using the
        // camera-facing ground-item billboard transform from ThrownItemRenderer.
        itemRenderer.renderStatic(
                stack,
                ItemDisplayContext.FIXED,
                packedLight,
                OverlayTexture.NO_OVERLAY,
                poseStack,
                buffer,
                entity.level(),
                entity.getId()
        );
        poseStack.popPose();
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(ThrownRecruitWeaponEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
