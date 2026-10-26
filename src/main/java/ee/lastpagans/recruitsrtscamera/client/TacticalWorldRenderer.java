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
    private static TacticalRenderScene retainedScene = TacticalRenderScene.EMPTY;
    private static List<RouteMesh> previousRoutes = List.of();
    private static List<RouteMesh> retainedRoutes = List.of();
    private static List<ArrowMesh> previousArrows = List.of();
    private static List<ArrowMesh> retainedArrows = List.of();
    private static List<SlotMesh> previousSlots = List.of();
    private static List<SlotMesh> retainedSlots = List.of();
    private static long lastFrameNanos = System.nanoTime();

    private TacticalWorldRenderer() { }

    public static void reset() {
        FLAG_ANCHORS.clear();
        retainedScene = TacticalRenderScene.EMPTY;
        previousRoutes = List.of();
        retainedRoutes = List.of();
        previousArrows = List.of();
        retainedArrows = List.of();
        previousSlots = List.of();
        retainedSlots = List.of();
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
        retainGeometry(scene);
        float interpolation = Mth.clamp(event.getPartialTick(), 0.0F, 1.0F);
        renderColorGeometry(matrix, interpolation);
        renderSlots(matrix, interpolation);
        renderFlags(matrix, cameraPos);

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    private static void renderColorGeometry(Matrix4f matrix, float interpolation) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int index = 0; index < retainedRoutes.size(); index++) {
            RouteMesh previous = index < previousRoutes.size() ? previousRoutes.get(index) : null;
            if (!compatible(previous, retainedRoutes.get(index))) previous = null;
            drawRibbon(buffer, matrix, previous, retainedRoutes.get(index), interpolation);
        }
        for (int index = 0; index < retainedArrows.size(); index++) {
            ArrowMesh previous = index < previousArrows.size() ? previousArrows.get(index) : null;
            if (!compatible(previous, retainedArrows.get(index))) previous = null;
            drawArrow(buffer, matrix, previous, retainedArrows.get(index), interpolation);
        }
        Tesselator.getInstance().end();
    }

    private static void drawRibbon(
            BufferBuilder buffer, Matrix4f matrix, RouteMesh previous, RouteMesh mesh, float interpolation
    ) {
        TacticalRenderScene.RouteRibbon route = mesh.route();
        double pulse = route.flowing() ? 0.82D + 0.18D * Math.sin(MC.level.getGameTime() * 0.15D) : 1.0D;
        for (int index = 0; index < mesh.segments().size(); index++) {
            RouteSegment segment = mesh.segments().get(index);
            RouteSegment old = previous != null && index < previous.segments().size()
                    ? previous.segments().get(index) : null;
            int color = scaledAlpha(route.color(), pulse);
            drawQuad(buffer, matrix, old == null ? null : old.band(), segment.band(), interpolation, color);
            int core = withAlpha(color, Math.min(220, alpha(color) + 55));
            drawQuad(buffer, matrix, old == null ? null : old.core(), segment.core(), interpolation, core);
            if (route.flowing()) {
                Vec3 start = old == null ? segment.start() : old.start().lerp(segment.start(), interpolation);
                Vec3 forwardBlend = old == null ? segment.forward() : old.forward().lerp(segment.forward(), interpolation);
                Vec3 sideBlend = old == null ? segment.side() : old.side().lerp(segment.side(), interpolation);
                Vec3 forward = forwardBlend.lengthSqr() < 1.0E-6D ? segment.forward() : forwardBlend.normalize();
                Vec3 side = sideBlend.lengthSqr() < 1.0E-6D ? segment.side() : sideBlend.normalize();
                double length = old == null ? segment.length() : Mth.lerp(interpolation, old.length(), segment.length());
                double spacing = 2.4D;
                double phase = ((MC.level.getGameTime() + MC.getFrameTime())
                        * 0.075D * route.flowSpeed()) % spacing;
                for (double distance = phase; distance < length; distance += spacing) {
                    Vec3 center = start.add(forward.scale(distance)).add(0.0D, 0.062D, 0.0D);
                    Vec3 tip = center.add(forward.scale(0.48D));
                    Vec3 rear = center.add(forward.scale(-0.30D));
                    int chevron = withAlpha(core, Math.min(245, alpha(core) + 25));
                    quad(buffer, matrix, rear.add(side.scale(0.28D)), rear.add(side.scale(-0.28D)),
                            tip, tip, chevron);
                }
            }
        }
    }

    private static void drawArrow(
            BufferBuilder buffer, Matrix4f matrix, ArrowMesh previous, ArrowMesh arrow, float interpolation
    ) {
        for (int index = 0; index < arrow.quads().size(); index++) {
            Quad old = previous != null && index < previous.quads().size() ? previous.quads().get(index) : null;
            drawQuad(buffer, matrix, old, arrow.quads().get(index), interpolation, arrow.color());
        }
    }

    private static void renderSlots(Matrix4f matrix, float interpolation) {
        if (retainedSlots.isEmpty()) return;
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, SLOT_TEXTURE);
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int index = 0; index < retainedSlots.size(); index++) {
            SlotMesh slot = retainedSlots.get(index);
            SlotMesh old = index < previousSlots.size() ? previousSlots.get(index) : null;
            if (old != null && old.a().distanceToSqr(slot.a()) > 16.0D) old = null;
            texVertex(buffer, matrix, old == null ? null : old.a(), slot.a(), interpolation, 0.0F, 0.0F, slot.color());
            texVertex(buffer, matrix, old == null ? null : old.b(), slot.b(), interpolation, 0.0F, 0.5F, slot.color());
            texVertex(buffer, matrix, old == null ? null : old.c(), slot.c(), interpolation, 1.0F, 0.5F, slot.color());
            texVertex(buffer, matrix, old == null ? null : old.d(), slot.d(), interpolation, 1.0F, 0.0F, slot.color());
        }
        Tesselator.getInstance().end();
    }

    private static void retainGeometry(TacticalRenderScene scene) {
        if (scene == retainedScene) return;
        retainedScene = scene;
        previousRoutes = retainedRoutes;
        List<RouteMesh> routes = new ArrayList<>(scene.routes().size());
        for (TacticalRenderScene.RouteRibbon route : scene.routes()) routes.add(buildRouteMesh(route));
        retainedRoutes = List.copyOf(routes);
        previousArrows = retainedArrows;
        List<ArrowMesh> arrows = new ArrayList<>(scene.arrows().size());
        for (TacticalRenderScene.FacingArrow arrow : scene.arrows()) arrows.add(buildArrowMesh(arrow));
        retainedArrows = List.copyOf(arrows);
        previousSlots = retainedSlots;
        List<SlotMesh> slots = new ArrayList<>(scene.slots().size());
        for (TacticalRenderScene.SlotDecal slot : scene.slots()) slots.add(buildSlotMesh(slot));
        retainedSlots = List.copyOf(slots);
    }

    private static boolean compatible(RouteMesh previous, RouteMesh current) {
        if (previous == null || previous.segments().size() != current.segments().size()) return false;
        return current.segments().isEmpty() || previous.segments().get(0).start()
                .distanceToSqr(current.segments().get(0).start()) <= 16.0D;
    }

    private static boolean compatible(ArrowMesh previous, ArrowMesh current) {
        return previous != null && previous.quads().size() == current.quads().size()
                && (current.quads().isEmpty() || previous.quads().get(0).a()
                .distanceToSqr(current.quads().get(0).a()) <= 16.0D);
    }

    private static RouteMesh buildRouteMesh(TacticalRenderScene.RouteRibbon route) {
        List<RouteSegment> segments = new ArrayList<>();
        List<Vec3> points = route.points();
        for (int i = 1; i < points.size(); i++) {
            Vec3 a = points.get(i - 1);
            Vec3 b = points.get(i);
            double dx = b.x - a.x;
            double dz = b.z - a.z;
            double length = Math.hypot(dx, dz);
            if (length < 1.0E-4D) continue;
            Vec3 forward = new Vec3(dx / length, 0.0D, dz / length);
            Vec3 side = new Vec3(-forward.z, 0.0D, forward.x);
            double half = route.width() * 0.5D;
            Vec3 bandSide = side.scale(half);
            Vec3 coreSide = bandSide.scale(0.28D);
            Quad band = new Quad(a.add(bandSide).add(0.0D, 0.045D, 0.0D),
                    a.subtract(bandSide).add(0.0D, 0.045D, 0.0D),
                    b.subtract(bandSide).add(0.0D, 0.045D, 0.0D),
                    b.add(bandSide).add(0.0D, 0.045D, 0.0D));
            Quad core = new Quad(a.add(coreSide).add(0.0D, 0.052D, 0.0D),
                    a.subtract(coreSide).add(0.0D, 0.052D, 0.0D),
                    b.subtract(coreSide).add(0.0D, 0.052D, 0.0D),
                    b.add(coreSide).add(0.0D, 0.052D, 0.0D));
            segments.add(new RouteSegment(a, forward, side, length, band, core));
        }
        return new RouteMesh(route, List.copyOf(segments));
    }

    private static ArrowMesh buildArrowMesh(TacticalRenderScene.FacingArrow arrow) {
        double radians = Math.toRadians(arrow.yaw());
        Vec3 forward = new Vec3(-Math.sin(radians), 0.0D, Math.cos(radians));
        Vec3 side = new Vec3(forward.z, 0.0D, -forward.x);
        Vec3 origin = arrow.origin().add(0.0D, 0.065D, 0.0D);
        Vec3 neck = origin.add(forward.scale(5.2D));
        Vec3 tip = origin.add(forward.scale(8.0D));
        return new ArrowMesh(arrow.color(), List.of(
                new Quad(origin.add(side.scale(0.28D)), origin.add(side.scale(-0.28D)),
                        neck.add(side.scale(-0.28D)), neck.add(side.scale(0.28D))),
                new Quad(neck.add(side.scale(1.25D)), neck.add(side.scale(-1.25D)), tip, tip)));
    }

    private static SlotMesh buildSlotMesh(TacticalRenderScene.SlotDecal slot) {
        double radians = Math.toRadians(slot.yaw());
        double half = slot.heavy() ? 0.68D : 0.42D;
        Vec3 center = slot.position().add(0.0D, 0.055D, 0.0D);
        Vec3 right = new Vec3(Math.cos(radians) * half, 0.0D, Math.sin(radians) * half);
        Vec3 forward = new Vec3(-Math.sin(radians) * half, 0.0D, Math.cos(radians) * half);
        return new SlotMesh(center.subtract(right).subtract(forward), center.subtract(right).add(forward),
                center.add(right).add(forward), center.add(right).subtract(forward), slot.color());
    }

    private static void drawQuad(BufferBuilder buffer, Matrix4f matrix, Quad quad, int color) {
        quad(buffer, matrix, quad.a(), quad.b(), quad.c(), quad.d(), color);
    }

    private static void drawQuad(
            BufferBuilder buffer, Matrix4f matrix, Quad previous, Quad current, float amount, int color
    ) {
        colorVertex(buffer, matrix, previous == null ? null : previous.a(), current.a(), amount, color);
        colorVertex(buffer, matrix, previous == null ? null : previous.b(), current.b(), amount, color);
        colorVertex(buffer, matrix, previous == null ? null : previous.c(), current.c(), amount, color);
        colorVertex(buffer, matrix, previous == null ? null : previous.d(), current.d(), amount, color);
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
            GroupFlagClientState.FlagTexture custom = GroupFlagClientState.texture(view.groupId());
            flags.add(new FlagView(view.groupId(), anchor.position, view.selected(), false,
                    custom == null ? BETSY_ROSS : custom.location(),
                    custom == null ? 1.5D : custom.aspectRatio()));
        }
        if (CommanderOverlay.worldEnemyBannersVisible()) {
            for (RTSCommandFacade.EnemyBannerView view : RTSCommandFacade.enemyBanners()) {
                if (!Double.isFinite(view.centerX()) || !Double.isFinite(view.anchorY()) || !Double.isFinite(view.centerZ())) continue;
                Vec3 target = new Vec3(view.centerX(), view.anchorY() + 6.4D, view.centerZ());
                SmoothAnchor anchor = FLAG_ANCHORS.computeIfAbsent(view.groupId(), ignored -> new SmoothAnchor(target));
                anchor.approach(target, smoothing);
                GroupFlagClientState.FlagTexture custom = GroupFlagClientState.texture(view.groupId());
                flags.add(new FlagView(view.groupId(), anchor.position, false, true,
                        custom == null ? BETSY_ROSS : custom.location(),
                        custom == null ? 1.5D : custom.aspectRatio()));
            }
        }
        java.util.Set<UUID> visible = flags.stream().map(FlagView::id).collect(java.util.stream.Collectors.toSet());
        FLAG_ANCHORS.keySet().removeIf(id -> !visible.contains(id));
        if (flags.isEmpty()) return;

        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        Map<ResourceLocation, List<FlagView>> textured = new java.util.LinkedHashMap<>();
        for (FlagView flag : flags) textured.computeIfAbsent(flag.texture, ignored -> new ArrayList<>()).add(flag);
        for (Map.Entry<ResourceLocation, List<FlagView>> entry : textured.entrySet()) {
            RenderSystem.setShaderTexture(0, entry.getKey());
            BufferBuilder buffer = Tesselator.getInstance().getBuilder();
            buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            for (FlagView flag : entry.getValue()) drawFlag(buffer, matrix, flag, cameraPos);
            Tesselator.getInstance().end();
        }
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
        double height = 1.8D * scale;
        double width = height * flag.aspectRatio;
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

    private static void colorVertex(
            BufferBuilder buffer, Matrix4f matrix, Vec3 previous, Vec3 current, float amount, int color
    ) {
        double x = previous == null ? current.x : Mth.lerp(amount, previous.x, current.x);
        double y = previous == null ? current.y : Mth.lerp(amount, previous.y, current.y);
        double z = previous == null ? current.z : Mth.lerp(amount, previous.z, current.z);
        buffer.vertex(matrix, (float) x, (float) y, (float) z)
                .color(red(color), green(color), blue(color), alpha(color)).endVertex();
    }

    private static void texVertex(BufferBuilder buffer, Matrix4f matrix, Vec3 point, float u, float v, int color) {
        buffer.vertex(matrix, (float) point.x, (float) point.y, (float) point.z).uv(u, v)
                .color(red(color), green(color), blue(color), alpha(color)).endVertex();
    }


    private static void texVertex(
            BufferBuilder buffer, Matrix4f matrix, Vec3 previous, Vec3 current, float amount,
            float u, float v, int color
    ) {
        double x = previous == null ? current.x : Mth.lerp(amount, previous.x, current.x);
        double y = previous == null ? current.y : Mth.lerp(amount, previous.y, current.y);
        double z = previous == null ? current.z : Mth.lerp(amount, previous.z, current.z);
        buffer.vertex(matrix, (float) x, (float) y, (float) z).uv(u, v)
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

    private record Quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d) { }
    private record RouteSegment(Vec3 start, Vec3 forward, Vec3 side, double length, Quad band, Quad core) { }
    private record RouteMesh(TacticalRenderScene.RouteRibbon route, List<RouteSegment> segments) { }
    private record ArrowMesh(int color, List<Quad> quads) { }
    private record SlotMesh(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color) { }

    private record FlagView(
            UUID id,
            Vec3 position,
            boolean selected,
            boolean enemy,
            ResourceLocation texture,
            double aspectRatio
    ) { }
}
