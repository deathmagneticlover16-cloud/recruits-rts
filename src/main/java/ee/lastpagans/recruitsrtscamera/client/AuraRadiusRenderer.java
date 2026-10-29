package ee.lastpagans.recruitsrtscamera.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/** Hold-Space, hover-only visualization of real commander and standard effect radii. */
public final class AuraRadiusRenderer {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final int SEGMENTS = 96;

    private AuraRadiusRenderer() { }

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES
                || MC.level == null || MC.player == null || MC.screen != null) return;
        RTSCameraController.AuraPreview preview = RTSCameraController.commanderAuraPreview();
        if (preview == null) return;

        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f matrix = pose.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        vertex(buffer, matrix, preview.center().x, preview.center().y, preview.center().z, 28);
        for (int i = 0; i <= SEGMENTS; i++) {
            double angle = Math.PI * 2.0D * i / SEGMENTS;
            vertex(buffer, matrix,
                    preview.center().x + Math.cos(angle) * preview.radius(), preview.center().y,
                    preview.center().z + Math.sin(angle) * preview.radius(), 28);
        }
        Tesselator.getInstance().end();

        double halfBand = 0.11D;
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = Math.PI * 2.0D * i / SEGMENTS;
            double a1 = Math.PI * 2.0D * (i + 1) / SEGMENTS;
            ringVertex(buffer, matrix, preview, a0, -halfBand);
            ringVertex(buffer, matrix, preview, a1, -halfBand);
            ringVertex(buffer, matrix, preview, a1, halfBand);
            ringVertex(buffer, matrix, preview, a0, halfBand);
        }
        Tesselator.getInstance().end();

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        pose.popPose();
    }

    private static void ringVertex(BufferBuilder buffer, Matrix4f matrix,
                                   RTSCameraController.AuraPreview preview, double angle, double offset) {
        double radius = preview.radius() + offset;
        vertex(buffer, matrix, preview.center().x + Math.cos(angle) * radius, preview.center().y + 0.006D,
                preview.center().z + Math.sin(angle) * radius, 150);
    }

    private static void vertex(BufferBuilder buffer, Matrix4f matrix,
                               double x, double y, double z, int alpha) {
        buffer.vertex(matrix, (float) x, (float) y, (float) z)
                .color(72, 156, 255, alpha).endVertex();
    }
}
