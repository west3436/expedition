package io.github.west3436.expedition.gametest;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.mover.ShipSurvey;
import io.github.west3436.expedition.registry.ExpeditionBlocks;
import io.github.west3436.expedition.ship.ShipMass;
import io.github.west3436.zas.engine.Face;
import io.github.west3436.zas.engine.Geometry;
import io.github.west3436.zas.geometry.Geometries;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The Ship Hull blocks (#55): a box of Ship Hull weighs half the same box of stone, and of Composite
 * Ship Hull a quarter, by the ship computer's count and the mover's alike; and ZAS³ gives both a
 * transmittance of 0 and seals every face, as it does its station wall.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ShipHullTests {
    private static final double EPSILON = 1e-9;

    /** A 3×2×3 box, 18 blocks, from (0, 1, 0). */
    private static final BlockPos FROM = new BlockPos(0, 1, 0);

    private static final BlockPos TO = new BlockPos(2, 2, 2);

    private ShipHullTests() {}

    /** Stone, then Ship Hull, then Composite Ship Hull in the same box: 18, 9 and 4.5. */
    @GameTest(template = "empty")
    public static void hull_weighs_a_half_and_composite_a_quarter(GameTestHelper h) {
        double stone = fill(h, Blocks.STONE);
        near(18, stone, "the box of stone");
        double hull = fill(h, ExpeditionBlocks.SHIP_HULL.get());
        near(stone / 2, hull, "the box of Ship Hull");
        double composite = fill(h, ExpeditionBlocks.COMPOSITE_SHIP_HULL.get());
        near(stone / 4, composite, "the box of Composite Ship Hull");
        h.succeed();
    }

    /** ZAS³ reads Expedition's thermal file: both hulls conduct no heat and seal all six faces. */
    @GameTest(template = "empty")
    public static void hulls_insulate_like_a_station_wall(GameTestHelper h) {
        for (Block block : new Block[] {ExpeditionBlocks.SHIP_HULL.get(), ExpeditionBlocks.COMPOSITE_SHIP_HULL.get()}) {
            BlockState state = block.defaultBlockState();
            Geometry g = Geometries.table().geometry(state);
            check(g.transmittance() == 0, state + " has a U of " + g.transmittance() + ", not 0");
            for (Face f : Face.values()) {
                check(g.isFaceFull(f), state + " doesn't seal its " + f + " face");
            }
        }
        // Stone, which has no such file, still conducts.
        double stone =
                Geometries.table().geometry(Blocks.STONE.defaultBlockState()).transmittance();
        check(stone > 0, "stone has a U of " + stone);
        h.succeed();
    }

    /** Fills the box with a block and weighs it, checking the mover's survey agrees. */
    private static double fill(GameTestHelper h, Block block) {
        for (BlockPos pos : BlockPos.betweenClosed(FROM, TO)) {
            h.setBlock(pos, block);
        }
        BlockPos from = h.absolutePos(FROM);
        BlockPos to = h.absolutePos(TO);
        BoundingBox box = BoundingBox.fromCorners(from, to);
        double mass = ShipMass.of(h.getLevel(), box);
        near(mass, ShipSurvey.of(h.getLevel(), box).mass(), "the mover's count of " + block);
        return mass;
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
