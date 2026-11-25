package io.github.west3436.expedition.gametest;

import io.github.west3436.expedition.registry.ExpeditionBlocks;
import io.github.west3436.expedition.ship.ShuttleComputerBlockEntity;
import io.github.west3436.ss13.api.console.ConsoleBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

/**
 * Places whole Shuttle Computers for the tests. It is a 2 x 2 console ({@link ConsoleBlock}): a
 * lone block of one breaks at its first neighbour update, so a test places all four.
 */
final class TestComputers {
    private TestComputers() {}

    /**
     * Places a whole computer, its lower left block at {@code origin}.
     *
     * @param level the level
     * @param origin the lower left block's position
     * @param facing the way its screen faces
     * @return the computer's block entity
     */
    static ShuttleComputerBlockEntity place(Level level, BlockPos origin, Direction facing) {
        ConsoleBlock.placeConsole(
                level,
                origin,
                ExpeditionBlocks.SHUTTLE_COMPUTER
                        .get()
                        .defaultBlockState()
                        .setValue(HorizontalDirectionalBlock.FACING, facing));
        if (!(level.getBlockEntity(origin) instanceof ShuttleComputerBlockEntity computer)) {
            throw new GameTestAssertException("no computer's block entity at " + origin);
        }
        return computer;
    }

    /**
     * Places a whole computer at a position relative to the test.
     *
     * @param h the test
     * @param rel the lower left block's position, relative to the test
     * @param facing the way its screen faces
     * @return the computer's block entity
     */
    static ShuttleComputerBlockEntity place(GameTestHelper h, BlockPos rel, Direction facing) {
        return place(h.getLevel(), h.absolutePos(rel), facing);
    }
}
