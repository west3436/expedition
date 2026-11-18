package io.github.west3436.expedition.ship;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * A ship's mass: the sum of what the blocks in its box weigh ({@link BlockWeights}). It is what the
 * thrust model ({@link io.github.west3436.expedition.drive.ThrustModel}) and the Shuttle Computer
 * read.
 */
public final class ShipMass {
    private ShipMass() {}

    /**
     * Sums what the blocks in a box weigh. It reads every position of the box, in one go: a box of
     * 64 blocks a side is a quarter of a million reads.
     *
     * <p>Thread: the level's own. Every chunk the box spans should be loaded; on a server, reading an
     * unloaded one loads it.
     *
     * @param level the level, or any other source of blocks
     * @param box the box, both corners inside
     * @return the box's mass, at least 0
     */
    public static double of(BlockGetter level, BoundingBox box) {
        double mass = 0;
        for (BlockPos pos :
                BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            mass += BlockWeights.weight(level.getBlockState(pos));
        }
        return mass;
    }
}
