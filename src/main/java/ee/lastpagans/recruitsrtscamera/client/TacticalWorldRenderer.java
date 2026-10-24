package ee.lastpagans.recruitsrtscamera.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Batched world-space routes, formation ghosts and smoothly animated group standards. */
public final class TacticalWorldRenderer {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final ResourceLocation SLOT_TEXTURE = new ResourceLocation(
            RecruitsRTSCameraMod.MOD_ID, "textures/gui/commander/selection_marker_circle.png");
    private static final ResourceLocation BETSY_ROSS = new ResourceLocation(
            RecruitsRTSCameraMod.MOD_ID, "textures/misc/betsy_ross_group_marker.png");
    private static final Map<UUID, SmoothAnchor> FLAG_ANCHORS = new HashMap<>();
    private static long lastFrameNanos = System.nanoTime();

    private TacticalWorldRenderer() { }

    public static void reset() {
        FLAG_ANCHORS.clear();
        lastFrameNanos = System.nanoTime();
    }

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES
                || !RTSCameraController.isActive() || MC.level == null || MC.player == null
                || MC.screen != null) return;

        // Selection rings share this single battlefield-render entry point as well.
        GroundSelectionMarkerRenderer.render(event);

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

        TacticalRenderScene scene = RTSCameraController.tacticalRenderScene(event.getPartialTick());
        renderColorGeometry(matrix, scene);
        renderSlots(matrix, scene.slots());
        renderFlags(matrix, cameraPos);

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    private static void renderColorGeometry(Matrix4f matrix, TacticalRenderScene scene) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (TacticalRenderScene.RouteRibbon route : scene.routes()) drawRibbon(buffer, matrix, route);
        for (TacticalRenderScene.FacingArrow arrow : scene.arrows()) drawArrow(buffer, matrix, arrow);
        Tesselator.getInstance().end();
    }

    private static void drawRibbon(BufferBuilder buffer, Matrix4f matrix, TacticalRenderScene.RouteRibbon route) {
        List<Vec3> points = route.points();
        double pulse = route.flowing() ? 0.82D + 0.18D * Math.sin(MC.level.getGameTime() * 0.15D) : 1.0D;
        for (int i = 1; i < points.size(); i++) {
            Vec3 a = points.get(i - 1);
            Vec3 b = points.get(i);
            double dx = b.x - a.x;
            double dz = b.z - a.z;
            double length = Math.hypot(dx, dz);
            if (length < 1.0E-4D) continue;
            double half = route.width() * 0.5D;
            double sideX = -dz / length * half;
            double sideZ = dx / length * half;
            int color = scaledAlpha(route.color(), pulse);
            quad(buffer, matrix,
                    a.add(sideX, 0.045D, sideZ), a.add(-sideX, 0.045D, -sideZ),
                    b.add(-sideX, 0.045D, -sideZ), b.add(sideX, 0.045D, sideZ), color);
            // A narrow bright core gives the ribbon a clean antialiased-looking spine.
            double coreX = sideX * 0.28D;
            double coreZ = sideZ * 0.28D;
            int core = withAlpha(color, Math.min(220, alpha(color) + 55));
            quad(buffer, matrix,
                    a.add(coreX, 0.052D, coreZ), a.add(-coreX, 0.052D, -coreZ),
                    b.add(-coreX, 0.052D, -coreZ), b.add(coreX, 0.052D, coreZ), core);
            if (route.flowing()) {
                double spacing = 2.4D;
                double phase = ((MC.level.getGameTime() + MC.getFrameTime())
                        * 0.075D * route.flowSpeed()) % spacing;
                Vec3 forward = new Vec3(dx / length, 0.0D, dz / length);
                Vec3 side = new Vec3(-forward.z, 0.0D, forward.x);
                for (double distance = phase; distance < length; distance += spacing) {
                    Vec3 center = a.add(forward.scale(distance)).add(0.0D, 0.062D, 0.0D);
                    Vec3 tip = center.add(forward.scale(0.48D));
                    Vec3 rear = center.add(forward.scale(-0.30D));
                    int chevron = withAlpha(core, Math.min(245, alpha(core) + 25));
                    quad(buffer, matrix, rear.add(side.scale(0.28D)), rear.add(side.scale(-0.28D)),
                            tip, tip, chevron);
                }
            }
        }
    }

    private static void drawArrow(BufferBuilder buffer, Matrix4f matrix, TacticalRenderScene.FacingArrow arrow) {
        double radians = Math.toRadians(arrow.yaw());
        Vec3 forward = new Vec3(-Math.sin(radians), 0.0D, Math.cos(radians));
        Vec3 side = new Vec3(forward.z, 0.0D, -forward.x);
        Vec3 origin = arrow.origin().add(0.0D, 0.065D, 0.0D);
        Vec3 neck = origin.add(forward.scale(5.2D));
        Vec3 tip = origin.add(forward.scale(8.0D));
        int color = arrow.color();
        quad(buffer, matrix, origin.add(side.scale(0.28D)), origin.add(side.scale(-0.28D)),
                neck.add(side.scale(-0.28D)), neck.add(side.scale(0.28D)), color);
        quad(buffer, matrix, neck.add(side.scale(1.25D)), neck.add(side.scale(-1.25D)), tip, tip, color);
    }

    private static void renderSlots(Matrix4f matrix, List<TacticalRenderScene.SlotDecal> slots) {
        if (slots.isEmpty()) return;
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, SLOT_TEXTURE);
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (TacticalRenderScene.SlotDecal slot : slots) {
            double radians = Math.toRadians(slot.yaw());
            double rightX = Math.cos(radians);
            double rightZ = Math.sin(radians);
            double forwardX = -Math.sin(radians);
            double forwardZ = Math.cos(radians);
            double half = slot.heavy() ? 0.68D : 0.42D;
            Vec3 c = slot.position().add(0.0D, 0.055D, 0.0D);
            Vec3 right = new Vec3(rightX * half, 0.0D, rightZ * half);
            Vec3 forward = new Vec3(forwardX * half, 0.0D, forwardZ * half);
            texVertex(buffer, matrix, c.subtract(right).subtract(forward), 0.0F, 0.0F, slot.color());
            texVertex(buffer, matrix, c.subtract(right).add(forward), 0.0F, 0.5F, slot.color());
            texVertex(buffer, matrix, c.add(right).add(forward), 1.0F, 0.5F, slot.color());
            texVertex(buffer, matrix, c.add(right).subtract(forward), 1.0F, 0.0F, slot.color());
        }
        Tesselator.getInstance().end();
    }

    private static void renderFlags(Matrix4f matrix, Vec3 cameraPos) {
        long now = System.nanoTime();
        double seconds = Math.min(0.10D, Math.max(0.0D, (now - lastFrameNanos) / 1_000_000_000.0D));
        lastFrameNanos = now;
        double smoothing = 1.0D - Math.exp(-seconds * 7.5D);
        List<FlagView> flags = new ArrayList<>();
        for (RTSCommandFacade.UnitCardView view : RTSCommandFacade.unitCards()) {
            if (!Double.isFinite(view.centerX()) || !Double.isFinite(view.anchorY()) || !Double.isFinite(view.centerZ())) continue;
            Vec3 target = new Vec3(view.centerX(), view.anchorY() + 6.4D, view.centerZ());
            SmoothAnchor anchor = FLAG_ANCHORS.computeIfAbsent(view.groupId(), ignored -> new SmoothAnchor(target));
            anchor.approach(target, smoothing);
            flags.add(new FlagView(view.groupId(), anchor.position, view.selected(), false));
        }
        if (CommanderOverlay.worldEnemyBannersVisible()) {
            for (RTSCommandFacade.EnemyBannerView view : RTSCommandFacade.enemyBanners()) {
                if (!Double.isFinite(view.centerX()) || !Double.isFinite(view.anchorY()) || !Double.isFinite(view.centerZ())) continue;
                Vec3 target = new Vec3(view.centerX(), view.anchorY() + 6.4D, view.centerZ());
                SmoothAnchor anchor = FLAG_ANCHORS.computeIfAbsent(view.groupId(), ignored -> new SmoothAnchor(target));
                anchor.approach(target, smoothing);
                flags.add(new FlagView(view.groupId(), anchor.position, false, true));
            }
        }
        java.util.Set<UUID> visible = flags.stream().map(FlagView::id).collect(java.util.stream.Collectors.toSet());
        FLAG_ANCHORS.keySet().removeIf(id -> !visible.contains(id));
        if (flags.isEmpty()) return;

        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, BETSY_ROSS);
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (FlagView flag : flags) drawFlag(buffer, matrix, flag, cameraPos);
        Tesselator.getInstance().end();
    }

    private static void drawFlag(BufferBuilder buffer, Matrix4f matrix, FlagView flag, Vec3 cameraPos) {
        Vec3 toCamera = cameraPos.subtract(flag.position);
        double horizontal = Math.hypot(toCamera.x, toCamera.z);
        Vec3 right = horizontal < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D)
                : new Vec3(toCamera.z / horizontal, 0.0D, -toCamera.x / horizontal);
        Vec3 toward = horizontal < 1.0E-4D ? new Vec3(0.0D, 0.0D, 1.0D)
                : new Vec3(toCamera.x / horizontal, 0.0D, toCamera.z / horizontal);
        double distance = cameraPos.distanceTo(flag.position);
        double scale = Mth.clamp(0.82D + distance / 90.0D, 0.82D, 1.55D);
        double width = 2.7D * scale;
        double height = 1.8D * scale;
        Vec3 clothCenter = flag.position;
        int color = flag.enemy ? 0xE8FFB8B8 : flag.selected ? 0xFFFFFFFF : 0xE8E8E8E8;
        double time = (MC.level.getGameTime() + MC.getFrameTime()) * 0.12D;
        int strips = 8;
        for (int i = 0; i < strips; i++) {
            double u0 = i / (double) strips;
            double u1 = (i + 1) / (double) strips;
            double wave0 = Math.sin(time + u0 * Math.PI * 2.2D) * 0.12D * scale * u0;
            double wave1 = Math.sin(time + u1 * Math.PI * 2.2D) * 0.12D * scale * u1;
            Vec3 x0 = right.scale((u0 - 0.5D) * width).add(toward.scale(wave0));
            Vec3 x1 = right.scale((u1 - 0.5D) * width).add(toward.scale(wave1));
            Vec3 top0 = clothCenter.add(x0).add(0.0D, height * 0.5D, 0.0D);
            Vec3 bottom0 = clothCenter.add(x0).add(0.0D, -height * 0.5D, 0.0D);
            Vec3 top1 = clothCenter.add(x1).add(0.0D, height * 0.5D, 0.0D);
            Vec3 bottom1 = clothCenter.add(x1).add(0.0D, -height * 0.5D, 0.0D);
            texVertex(buffer, matrix, top0, (float) u0, 0.0F, color);
            texVertex(buffer, matrix, bottom0, (float) u0, 1.0F, color);
            texVertex(buffer, matrix, bottom1, (float) u1, 1.0F, color);
            texVertex(buffer, matrix, top1, (float) u1, 0.0F, color);
        }
    }

    private static void quad(BufferBuilder buffer, Matrix4f matrix, Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color) {
        colorVertex(buffer, matrix, a, color); colorVertex(buffer, matrix, b, color);
        colorVertex(buffer, matrix, c, color); colorVertex(buffer, matrix, d, color);
    }

    private static void colorVertex(BufferBuilder buffer, Matrix4f matrix, Vec3 point, int color) {
        buffer.vertex(matrix, (float) point.x, (float) point.y, (float) point.z)
                .color(red(color), green(color), blue(color), alpha(color)).endVertex();
    }

    private static void texVertex(BufferBuilder buffer, Matrix4f matrix, Vec3 point, float u, float v, int color) {
        buffer.vertex(matrix, (float) point.x, (float) point.y, (float) point.z).uv(u, v)
                .color(red(color), green(color), blue(color), alpha(color)).endVertex();
    }

    private static int alpha(int color) { return color >>> 24 & 255; }
    private static int red(int color) { return color >>> 16 & 255; }
    private static int green(int color) { return color >>> 8 & 255; }
    private static int blue(int color) { return color & 255; }
    private static int withAlpha(int color, int alpha) { return color & 0x00FFFFFF | Mth.clamp(alpha, 0, 255) << 24; }
    private static int scaledAlpha(int color, double scale) { return withAlpha(color, (int) Math.round(alpha(color) * scale)); }

    private static final class SmoothAnchor {
        Vec3 position;
        SmoothAnchor(Vec3 position) { this.position = position; }
        void approach(Vec3 target, double amount) { position = position.lerp(target, Mth.clamp(amount, 0.0D, 1.0D)); }
    }

    private record FlagView(UUID id, Vec3 position, boolean selected, boolean enemy) { }
}
