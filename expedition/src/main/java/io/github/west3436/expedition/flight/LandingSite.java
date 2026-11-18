package io.github.west3436.expedition.flight;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

/**
 * Where a ship lands (#29): on the highest solid ground under its footprint, the box's lowest layer
 * resting on it. Ground is anything that stops movement, as {@link Heightmap.Types#OCEAN_FLOOR}
 * reads it: not fluids, grass or snow layers, so a ship over a lake rests on its bed, with the water
 * written over. A footprint whose ground varies by more than the server allows is too uneven to land
 * on.
 *
 * @param refusal why the ship can't land there, or empty if it can
 * @param origin where the box's lowest corner goes, if it can
 * @param lowest the lowest ground under the footprint
 * @param highest the highest ground under the footprint
 */
public record LandingSite(Optional<String> refusal, BlockPos origin, int lowest, int highest) {
    /**
     * Whether the ship may land.
     *
     * @return true if nothing refuses it
     */
    public boolean ok() {
        return refusal.isEmpty();
    }

    /**
     * Finds where a box lands, centred on a column. The chunks under the footprint are loaded, and
     * generated if they never were.
     *
     * @param level the level it lands in
     * @param size the box, for its size; where it is now doesn't matter
     * @param x the column's X, where the middle of the box goes
     * @param z the column's Z
     * @param maxUneven the most the ground may vary under the footprint, in blocks
     * @param ship the ship's own box if it is in this level, whose blocks aren't ground; or null
     * @return the site
     */
    public static LandingSite find(
            ServerLevel level, BoundingBox size, int x, int z, int maxUneven, @Nullable BoundingBox ship) {
        int minX = x - size.getXSpan() / 2;
        int minZ = z - size.getZSpan() / 2;
        int[] tops = new int[size.getXSpan() * size.getZSpan()];
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int i = 0;
        for (int dx = 0; dx < size.getXSpan(); dx++) {
            for (int dz = 0; dz < size.getZSpan(); dz++) {
                tops[i++] = top(level, minX + dx, minZ + dz, ship, at);
            }
        }
        return judge(
                tops, minX, minZ, level.getMinBuildHeight(), level.getMaxBuildHeight(), size.getYSpan(), maxUneven);
    }

    /** The highest ground in a column, or one below the level's floor if there is none. */
    private static int top(ServerLevel level, int x, int z, @Nullable BoundingBox ship, BlockPos.MutableBlockPos at) {
        ChunkAccess chunk = level.getChunk(x >> 4, z >> 4);
        int y = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x & 15, z & 15);
        if (ship == null || x < ship.minX() || x > ship.maxX() || z < ship.minZ() || z > ship.maxZ()) {
            return y;
        }
        // The ship leaves before it lands, so its own blocks aren't ground.
        for (; y >= level.getMinBuildHeight(); y--) {
            if (ship.isInside(x, y, z)) {
                continue;
            }
            BlockState state = chunk.getBlockState(at.set(x, y, z));
            if (state.blocksMotion() && !state.liquid()) {
                return y;
            }
        }
        return y;
    }

    /**
     * Judges the ground under a footprint, without the world.
     *
     * @param tops the highest ground in each column, one below {@code minY} where there is none
     * @param minX the footprint's lowest X
     * @param minZ the footprint's lowest Z
     * @param minY the level's lowest block
     * @param maxY the level's build limit, one above its highest block
     * @param height the box's height
     * @param maxUneven the most the ground may vary, in blocks
     * @return the site
     */
    static LandingSite judge(int[] tops, int minX, int minZ, int minY, int maxY, int height, int maxUneven) {
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (int top : tops) {
            lowest = Math.min(lowest, top);
            highest = Math.max(highest, top);
        }
        BlockPos origin = new BlockPos(minX, highest + 1, minZ);
        if (highest < minY || highest + height >= maxY) {
            return new LandingSite(Optional.of(FlightRefusal.NO_GROUND), origin, lowest, highest);
        }
        if (highest - lowest > maxUneven) {
            return new LandingSite(Optional.of(FlightRefusal.TOO_UNEVEN), origin, lowest, highest);
        }
        return new LandingSite(Optional.empty(), origin, lowest, highest);
    }
}
