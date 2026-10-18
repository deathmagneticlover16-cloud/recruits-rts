package ee.lastpagans.recruitsrtscamera.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Collision-shape based formation grounding for modded terrain. Heightmaps only know the
 * containing block Y; many modded soils/paths have a fractional collision top. This resolves
 * the real standable surface without any block-ID whitelist.
 */
public final class TerrainSurfaceResolver {
    private TerrainSurfaceResolver() {}

    public static Vec3 resolve(ServerLevel level, Entity sample, Vec3 ideal) {
        if (level == null || sample == null || ideal == null) return ideal;
        int x = (int) Math.floor(ideal.x);
        int z = (int) Math.floor(ideal.z);
        int base = (int) Math.floor(ideal.y);
        Vec3 best = null;
        double bestDelta = Double.MAX_VALUE;

        for (int y = base + 2; y >= base - 4; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.getFluidState().isSource()) continue;
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (shape.isEmpty()) continue;
            AABB bounds;
            try {
                bounds = shape.bounds();
            } catch (IllegalStateException ex) {
                continue;
            }
            double surfaceY = y + bounds.maxY;
            if (Math.abs(surfaceY - ideal.y) > 3.0D) continue;
            AABB standing = sample.getBoundingBox().move(
                    ideal.x - sample.getX(),
                    surfaceY - sample.getY(),
                    ideal.z - sample.getZ()
            ).inflate(-0.025D, 0.0D, -0.025D);
            if (!level.noCollision(sample, standing)) continue;
            double delta = Math.abs(surfaceY - ideal.y);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = new Vec3(ideal.x, surfaceY, ideal.z);
            }
        }
        return best == null ? ideal : best;
    }
}
