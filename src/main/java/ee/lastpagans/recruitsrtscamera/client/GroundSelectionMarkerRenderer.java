package ee.lastpagans.recruitsrtscamera.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.config.RTSClientConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Rome-II-style selection markers under owned Recruits.
 *
 * <p>Hovering any loaded soldier, card or friendly banner exposes a hollow marker under
 * every loaded member of that group. Selecting the group fills the marker. The old full
 * entity glow remains available as a legacy client config mode.</p>
 */
public final class GroundSelectionMarkerRenderer {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final ResourceLocation CIRCLE = new ResourceLocation(
            RecruitsRTSCameraMod.MOD_ID, "textures/gui/commander/selection_marker_circle.png"
    );
    private static final ResourceLocation SQUARE = new ResourceLocation(
            RecruitsRTSCameraMod.MOD_ID, "textures/gui/commander/selection_marker_square.png"
    );
    private static final float HALF_SIZE = 0.58F;
    private static final float Y_OFFSET = 0.035F;

    private GroundSelectionMarkerRenderer() {
    }

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES
                || !RTSCameraController.isActive()
                || !RTSClientConfig.usesGroundMarkers()
                || MC.level == null
                || MC.player == null
                || MC.screen != null) {
            return;
        }

        Set<UUID> selected = RTSCameraController.commanderSelectedGroups();
        UUID hovered = CommanderOverlay.hoveredFriendlyGroupId();
        if (hovered == null) hovered = RTSCameraController.commanderHoveredFriendlyGroup();
        if (selected.isEmpty() && hovered == null) return;

        List<AbstractRecruitEntity> visibleMarkers = new ArrayList<>();
        for (AbstractRecruitEntity recruit : RTSCameraController.commanderLoadedOwnedRecruits()) {
            if (recruit == null || !recruit.isAlive() || recruit.isRemoved()) continue;
            UUID groupId = recruit.getGroup();
            if (groupId == null) continue;
            if (selected.contains(groupId) || groupId.equals(hovered)) visibleMarkers.add(recruit);
        }
        if (visibleMarkers.isEmpty()) return;

        ResourceLocation texture = RTSClientConfig.GROUND_MARKER_SHAPE.get() == RTSClientConfig.GroundMarkerShape.SQUARE
                ? SQUARE : CIRCLE;
        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        Matrix4f matrix = poseStack.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);

        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (AbstractRecruitEntity recruit : visibleMarkers) {
            UUID groupId = recruit.getGroup();
            boolean isSelected = selected.contains(groupId);

            Entity controlled = recruit.getVehicle() != null ? recruit.getVehicle() : recruit;
            double x = controlled.getX();
            double z = controlled.getZ();
            double y = controlled.getBoundingBox().minY + Y_OFFSET;
            float half = Math.max(HALF_SIZE, (float) controlled.getBbWidth() * 0.58F);
            float v0 = isSelected ? 0.5F : 0.0F;
            float v1 = isSelected ? 1.0F : 0.5F;
            int alpha = isSelected ? 205 : 225;

            vertex(buffer, matrix, x - half, y, z - half, 0.0F, v0, alpha);
            vertex(buffer, matrix, x - half, y, z + half, 0.0F, v1, alpha);
            vertex(buffer, matrix, x + half, y, z + half, 1.0F, v1, alpha);
            vertex(buffer, matrix, x + half, y, z - half, 1.0F, v0, alpha);
        }

        Tesselator.getInstance().end();

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    private static void vertex(
            BufferBuilder buffer, Matrix4f matrix,
            double x, double y, double z,
            float u, float v, int alpha
    ) {
        buffer.vertex(matrix, (float) x, (float) y, (float) z)
                .uv(u, v)
                .color(255, 224, 74, alpha)
                .endVertex();
    }
}
