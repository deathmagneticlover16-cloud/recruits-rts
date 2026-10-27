package ee.lastpagans.recruitsrtscamera.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.config.RTSClientConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * World-space fog-of-war renderer.
 *
 * This deliberately does not use Minecraft's global distance fog: global fog cannot have
 * holes around friendly vision sources. Instead hidden world cells become overlapping,
 * camera-facing mist volumes. The server remains authoritative about whether a target is
 * visible; this renderer is only the visual presentation of that same visibility map.
 */
public final class FogOfWarWorldRenderer {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final ResourceLocation FOG_TEXTURE = new ResourceLocation(
            RecruitsRTSCameraMod.MOD_ID,
            "textures/misc/fog_puff.png"
    );

    private static final int CELL_SIZE = 12;
    private static final long RESAMPLE_MS = 260L;
    private static final double CAMERA_CLEAR_RADIUS = 8.0D;
    private static final double CAMERA_FADE_RADIUS = 20.0D;

    private static final List<FogCell> CELLS = new ArrayList<>();
    private static long lastSampleMillis;
    private static int lastCameraCellX = Integer.MIN_VALUE;
    private static int lastCameraCellZ = Integer.MIN_VALUE;
    private static int lastRasterCells;
    private static int lastEmittedQuads;

    private FogOfWarWorldRenderer() {
    }

    public static void reset() {
        CELLS.clear();
        lastSampleMillis = 0L;
        lastCameraCellX = Integer.MIN_VALUE;
        lastCameraCellZ = Integer.MIN_VALUE;
        lastRasterCells = 0;
        lastEmittedQuads = 0;
    }

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES
                || !RTSCameraController.isActive()
                || !ClientPermissionState.fogEnabled()
                || !RTSClientConfig.FOG_RENDER_ENABLED.get()
                || MC.level == null
                || MC.player == null
                || MC.screen != null) {
            return;
        }

        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();
        rebuildIfNeeded(cameraPos);
        if (CELLS.isEmpty()) {
            return;
        }

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
        RenderSystem.setShaderTexture(0, FOG_TEXTURE);

        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

        Vector3f left = new Vector3f(camera.getLeftVector());
        Vector3f up = new Vector3f(camera.getUpVector());
        long now = System.currentTimeMillis();
        double animationSpeed = RTSClientConfig.FOG_ANIMATION_SPEED.get();
        double t = now / 1000.0D * animationSpeed;
        double densityMultiplier = RTSClientConfig.FOG_DENSITY.get();
        double fogHeight = RTSClientConfig.FOG_HEIGHT.get();
        int emittedQuads = 0;

        for (FogCell cell : CELLS) {
            double horizontalDistance = Math.sqrt(horizontalDistanceSqr(
                    cameraPos.x, cameraPos.z, cell.x(), cell.z()
            ));
            float cameraFade = (float) clamp(
                    (horizontalDistance - CAMERA_CLEAR_RADIUS)
                            / (CAMERA_FADE_RADIUS - CAMERA_CLEAR_RADIUS),
                    0.0D,
                    1.0D
            );
            if (cameraFade <= 0.01F) {
                continue;
            }

            float hidden = cell.hiddenFactor();
            float density = (float) clamp(hidden * cameraFade * densityMultiplier, 0.0D, 2.5D);
            if (density <= 0.025F) {
                continue;
            }

            // Raster LOD: distant and thin fog needs one sheet, while dense nearby banks
            // retain depth. This bounds quad emission instead of drawing eight per cell.
            int layers = density < 0.30F || horizontalDistance > 120.0D ? 1
                    : density < 0.80F ? 2 : 3;
            for (int layer = 0; layer < layers; layer++) {
                double phase = cell.seed() * 0.017453292519943295D + layer * 1.91D;
                double driftX = Math.sin(t * 0.095D + phase) * (1.2D + layer * 0.35D);
                double driftZ = Math.cos(t * 0.073D + phase * 1.37D) * (1.2D + layer * 0.30D);
                double bob = Math.sin(t * 0.12D + phase * 0.73D) * 1.0D;

                double centerX = cell.x() + driftX;
                double centerZ = cell.z() + driftZ;
                double verticalSpan = Math.max(8.0D, fogHeight - 18.0D);
                double layerGap = verticalSpan / Math.max(1, layers - 1);
                double centerY = cell.groundY() + 8.0D + layer * layerGap + bob;

                float width = (float) (18.0D + pseudo01(cell.seed() + layer * 37) * 8.0D);
                float height = (float) Math.min(28.0D, 14.0D + pseudo01(cell.seed() + layer * 71) * 7.0D
                        + Math.max(0.0D, fogHeight - 68.0D) * 0.08D);
                int alpha = (int) clamp(30.0D + density * 82.0D - layer * 3.0D, 12.0D, 168.0D);

                // Two slightly offset sheets per layer make the bank read as a volume instead
                // of a wall of identical flat sprites.
                emitBillboard(buffer, matrix, left, up,
                        centerX, centerY, centerZ,
                        width, height,
                        183, 188, 181, alpha);
                emittedQuads++;

                if (horizontalDistance < 96.0D && density >= 0.45F) {
                    emitBillboard(buffer, matrix, left, up,
                            centerX + Math.cos(phase) * 2.4D,
                            centerY + 2.1D,
                            centerZ + Math.sin(phase) * 2.4D,
                            width * 0.78F,
                            height * 0.82F,
                            201, 203, 198,
                            Math.max(10, (int) (alpha * 0.58F)));
                    emittedQuads++;
                }
            }
        }

        lastEmittedQuads = emittedQuads;

        if (emittedQuads > 0) {
            Tesselator.getInstance().end();
        } else {
            buffer.discard();
        }

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    private static void rebuildIfNeeded(Vec3 cameraPos) {
        long now = System.currentTimeMillis();
        int cameraCellX = floorDiv(cameraPos.x, CELL_SIZE);
        int cameraCellZ = floorDiv(cameraPos.z, CELL_SIZE);
        if (!CELLS.isEmpty()
                && now - lastSampleMillis < RESAMPLE_MS
                && cameraCellX == lastCameraCellX
                && cameraCellZ == lastCameraCellZ) {
            return;
        }

        CELLS.clear();
        lastEmittedQuads = 0;
        List<RTSCameraController.FogVisionSource> sources = RTSCameraController.fogVisionSources();
        int sampleRadius = RTSClientConfig.FOG_RENDER_DISTANCE.get();
        double edgeFadeDistance = RTSClientConfig.FOG_EDGE_FADE_DISTANCE.get();
        int cells = (int) Math.ceil(sampleRadius / (double) CELL_SIZE);
        double radiusSq = sampleRadius * (double) sampleRadius;

        // Stage-four visibility raster. Friendly sources stamp only the cells inside their
        // radius plus feather instead of every fog cell scanning every source.
        int diameter = cells * 2 + 1;
        float[][] hiddenRaster = new float[diameter][diameter];
        for (float[] row : hiddenRaster) Arrays.fill(row, 1.0F);
        double feather = Math.max(0.001D, edgeFadeDistance);
        for (RTSCameraController.FogVisionSource source : sources) {
            double influence = source.radius() + feather;
            int reach = (int) Math.ceil(influence / CELL_SIZE);
            int sourceCellX = floorDiv(source.position().x, CELL_SIZE);
            int sourceCellZ = floorDiv(source.position().z, CELL_SIZE);
            for (int worldCellX = sourceCellX - reach; worldCellX <= sourceCellX + reach; worldCellX++) {
                int rasterX = worldCellX - (cameraCellX - cells);
                if (rasterX < 0 || rasterX >= diameter) continue;
                double worldX = worldCellX * (double) CELL_SIZE + CELL_SIZE * 0.5D;
                for (int worldCellZ = sourceCellZ - reach; worldCellZ <= sourceCellZ + reach; worldCellZ++) {
                    int rasterZ = worldCellZ - (cameraCellZ - cells);
                    if (rasterZ < 0 || rasterZ >= diameter) continue;
                    double worldZ = worldCellZ * (double) CELL_SIZE + CELL_SIZE * 0.5D;
                    double distance = Math.sqrt(horizontalDistanceSqr(
                            source.position().x, source.position().z, worldX, worldZ));
                    double outside = distance - source.radius();
                    float hidden = outside <= 0.0D ? 0.0F
                            : (float) clamp(outside / feather, 0.0D, 1.0D);
                    hiddenRaster[rasterX][rasterZ] = Math.min(hiddenRaster[rasterX][rasterZ], hidden);
                }
            }
        }

        for (int dz = -cells; dz <= cells; dz++) {
            for (int dx = -cells; dx <= cells; dx++) {
                int worldX = (cameraCellX + dx) * CELL_SIZE + CELL_SIZE / 2;
                int worldZ = (cameraCellZ + dz) * CELL_SIZE + CELL_SIZE / 2;
                if (horizontalDistanceSqr(cameraPos.x, cameraPos.z, worldX, worldZ) > radiusSq) {
                    continue;
                }

                BlockPos probe = new BlockPos(worldX, MC.level.getMinBuildHeight(), worldZ);
                if (!MC.level.hasChunkAt(probe)) {
                    continue;
                }

                float hidden = hiddenRaster[dx + cells][dz + cells];
                if (hidden <= 0.025F) {
                    continue;
                }

                int groundY = MC.level.getHeight(Heightmap.Types.MOTION_BLOCKING, worldX, worldZ);
                int seed = mix(worldX, worldZ);
                CELLS.add(new FogCell(worldX + 0.5D, groundY, worldZ + 0.5D, hidden, seed));
            }
        }
        lastRasterCells = diameter * diameter;

        // Cell membership and ordering remain valid until the camera enters another
        // sampling cell, avoiding a full translucent-depth sort on every render frame.
        CELLS.sort(Comparator.comparingDouble((FogCell cell) ->
                -horizontalDistanceSqr(cameraPos.x, cameraPos.z, cell.x(), cell.z())));

        lastSampleMillis = now;
        lastCameraCellX = cameraCellX;
        lastCameraCellZ = cameraCellZ;
    }

    public static int lastRasterCells() { return lastRasterCells; }
    public static int lastEmittedQuads() { return lastEmittedQuads; }

    private static void emitBillboard(
            BufferBuilder buffer,
            Matrix4f matrix,
            Vector3f left,
            Vector3f up,
            double cx,
            double cy,
            double cz,
            float width,
            float height,
            int red,
            int green,
            int blue,
            int alpha
    ) {
        double hx = width * 0.5D;
        double hy = height * 0.5D;

        double lx = left.x() * hx;
        double ly = left.y() * hx;
        double lz = left.z() * hx;
        double ux = up.x() * hy;
        double uy = up.y() * hy;
        double uz = up.z() * hy;

        vertex(buffer, matrix, cx - lx - ux, cy - ly - uy, cz - lz - uz, 0.0F, 1.0F, red, green, blue, alpha);
        vertex(buffer, matrix, cx + lx - ux, cy + ly - uy, cz + lz - uz, 1.0F, 1.0F, red, green, blue, alpha);
        vertex(buffer, matrix, cx + lx + ux, cy + ly + uy, cz + lz + uz, 1.0F, 0.0F, red, green, blue, alpha);
        vertex(buffer, matrix, cx - lx + ux, cy - ly + uy, cz - lz + uz, 0.0F, 0.0F, red, green, blue, alpha);
    }

    private static void vertex(
            BufferBuilder buffer,
            Matrix4f matrix,
            double x,
            double y,
            double z,
            float u,
            float v,
            int red,
            int green,
            int blue,
            int alpha
    ) {
        buffer.vertex(matrix, (float) x, (float) y, (float) z)
                .uv(u, v)
                .color(red, green, blue, alpha)
                .endVertex();
    }

    private static int floorDiv(double value, int divisor) {
        return (int) Math.floor(value / divisor);
    }

    private static int mix(int x, int z) {
        int h = x * 0x1f1f1f1f ^ z * 0x45d9f3b;
        h ^= h >>> 16;
        h *= 0x45d9f3b;
        h ^= h >>> 16;
        return h;
    }

    private static double pseudo01(int seed) {
        int value = seed ^ (seed >>> 16);
        value *= 0x7feb352d;
        value ^= value >>> 15;
        return (value & 0x7fffffff) / (double) Integer.MAX_VALUE;
    }

    private static double horizontalDistanceSqr(double ax, double az, double bx, double bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return dx * dx + dz * dz;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record FogCell(double x, int groundY, double z, float hiddenFactor, int seed) {
    }
}
