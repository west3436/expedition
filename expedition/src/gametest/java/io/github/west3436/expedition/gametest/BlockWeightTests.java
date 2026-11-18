package io.github.west3436.expedition.gametest;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.ship.BlockWeights;
import io.github.west3436.expedition.ship.ShipMass;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Block weights from the {@code expedition:block_weight} data map (#54), and a box's mass summed
 * from them ({@link ShipMass}).
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BlockWeightTests {
    private static final double EPSILON = 1e-9;

    private BlockWeightTests() {}

    /** A box of iron blocks reads more mass than the same box of oak planks, by their weights. */
    @GameTest(template = "empty")
    public static void a_heavy_box_outweighs_a_light_one(GameTestHelper h) {
        BoundingBox iron = fill(h, new BlockPos(0, 1, 0), Blocks.IRON_BLOCK.defaultBlockState());
        BoundingBox planks = fill(h, new BlockPos(4, 1, 0), Blocks.OAK_PLANKS.defaultBlockState());
        double heavy = ShipMass.of(h.getLevel(), iron);
        double light = ShipMass.of(h.getLevel(), planks);
        check(heavy > light, "iron " + heavy + " isn't heavier than planks " + light);
        near(27 * 4.0, heavy, "27 iron blocks");
        near(27 * 0.5, light, "27 oak planks");
        h.succeed();
    }

    /** A block the data map doesn't list weighs 1; air and fluids weigh nothing. */
    @GameTest(template = "empty")
    public static void unlisted_blocks_weigh_one_and_fluids_nothing(GameTestHelper h) {
        near(1, BlockWeights.weight(Blocks.STONE.defaultBlockState()), "stone");
        near(1, BlockWeights.weight(Blocks.DIRT.defaultBlockState()), "dirt");
        near(0, BlockWeights.weight(Blocks.AIR.defaultBlockState()), "air");
        near(0, BlockWeights.weight(Blocks.WATER.defaultBlockState()), "water");
        near(0, BlockWeights.weight(Blocks.LAVA.defaultBlockState()), "lava");

        BoundingBox stone = fill(h, new BlockPos(0, 1, 0), Blocks.STONE.defaultBlockState());
        near(27, ShipMass.of(h.getLevel(), stone), "27 stone");
        h.succeed();
    }

    /**
     * Partial blocks count at their own weight, a later entry overrides an earlier tag, and
     * waterlogging adds nothing.
     */
    @GameTest(template = "empty")
    public static void partial_blocks_count_and_water_adds_nothing(GameTestHelper h) {
        BlockState stoneSlab = Blocks.STONE_SLAB.defaultBlockState();
        BlockState oakSlab = Blocks.OAK_SLAB.defaultBlockState();
        // #minecraft:slabs is 0.5; #minecraft:wooden_slabs, listed after it, is 0.25.
        near(0.5, BlockWeights.weight(stoneSlab), "a stone slab");
        near(0.25, BlockWeights.weight(oakSlab), "an oak slab");
        near(0.25, BlockWeights.weight(oakSlab.setValue(SlabBlock.WATERLOGGED, true)), "a waterlogged oak slab");
        near(0.3, BlockWeights.weight(Blocks.OAK_FENCE.defaultBlockState()), "an oak fence");
        near(0.1, BlockWeights.weight(Blocks.TORCH.defaultBlockState()), "a torch");
        near(3, BlockWeights.weight(Blocks.OBSIDIAN.defaultBlockState()), "obsidian");
        near(
                0.2,
                BlockWeights.weight(
                        Blocks.GLASS_PANE.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)),
                "a waterlogged glass pane");
        check(
                BlockWeights.weight(Blocks.NETHERITE_BLOCK.defaultBlockState())
                        > BlockWeights.weight(Blocks.GOLD_BLOCK.defaultBlockState()),
                "netherite isn't heavier than gold");
        check(
                BlockWeights.weight(Blocks.GOLD_BLOCK.defaultBlockState())
                        > BlockWeights.weight(Blocks.IRON_BLOCK.defaultBlockState()),
                "gold isn't heavier than iron");
        h.succeed();
    }

    /** Fills a 3 × 3 × 3 box at a position relative to the test, and gives its absolute box. */
    private static BoundingBox fill(GameTestHelper h, BlockPos corner, BlockState state) {
        for (BlockPos pos : BlockPos.betweenClosed(corner, corner.offset(2, 2, 2))) {
            h.setBlock(pos, state);
        }
        BlockPos min = h.absolutePos(corner);
        BlockPos max = h.absolutePos(corner.offset(2, 2, 2));
        return BoundingBox.fromCorners(min, max);
    }

    private static void near(double expected, double actual, String what) {
        check(Math.abs(expected - actual) < EPSILON, what + " weighs " + actual + ", not " + expected);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }
}
