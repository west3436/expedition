package io.github.west3436.expedition.mover;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * What a box holds, counted without changing anything: its mass and what a move of it would carry.
 *
 * @param mass the solid blocks, as {@link #isSolid} counts them: a ship's mass
 * @param blocks the blocks that aren't air, solid or not
 * @param blockEntities the block entities
 */
public record ShipSurvey(int mass, int blocks, int blockEntities) {
    /**
     * Whether a block counts toward a ship's mass. Air doesn't, and nor does anything a placed block
     * replaces: water and lava, fire, grass, snow layers, vines and the like. Everything else does,
     * one each, whatever it is made of.
     *
     * @param state the block
     * @return true if it is solid
     */
    public static boolean isSolid(BlockState state) {
        return !state.isAir() && !state.canBeReplaced();
    }

    /**
     * Counts what a box holds. It reads every position of the box, in one go: a box of 64 blocks a
     * side is a quarter of a million reads.
     *
     * <p>Thread: server thread only. Every chunk the box spans must be loaded.
     *
     * @param level the level
     * @param box the box
     * @return the counts
     */
    public static ShipSurvey of(ServerLevel level, BoundingBox box) {
        int mass = 0;
        int blocks = 0;
        int blockEntities = 0;
        for (BlockPos pos :
                BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            blocks++;
            if (isSolid(state)) {
                mass++;
            }
            if (state.hasBlockEntity() && level.getBlockEntity(pos) != null) {
                blockEntities++;
            }
        }
        return new ShipSurvey(mass, blocks, blockEntities);
    }
}
