package io.github.west3436.expedition.gametest;

import io.github.west3436.expedition.ExpeditionConfig;
import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.mover.DryRun;
import io.github.west3436.expedition.mover.MoveJob;
import io.github.west3436.expedition.mover.MoveRefusal;
import io.github.west3436.expedition.mover.MoveRequest;
import io.github.west3436.expedition.mover.MoveResult;
import io.github.west3436.expedition.mover.Obstacle;
import io.github.west3436.expedition.mover.QuietBlocks;
import io.github.west3436.expedition.mover.ShipMover;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The server's ceiling on a ship's box (#9): a move over the {@code ships} settings of {@link
 * ExpeditionConfig} is refused as {@link MoveRefusal#TOO_LARGE}, or a short hop over its own as
 * {@link MoveRefusal#HOP_TOO_LARGE}, and nothing changes; a box at the limits moves.
 *
 * <p>The settings are the server's, shared by every test, so each test sets the ones it needs and
 * puts the old values back before it ends, and each is in a batch of its own, so no other test runs
 * while they are changed. The ship is a 3 × 3 × 3 box, 27 blocks, with a floor of stone bricks and
 * a glass block at its top.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CeilingTests {
    /** As in {@link MoverTests}: a far chunk's entities load in real time, which can be slow. */
    private static final int TIMEOUT = 20_000;

    private static final TicketType<ChunkPos> HELD =
            TicketType.create("expedition_gametest_ceiling", Comparator.comparingLong(ChunkPos::toLong), TIMEOUT);

    /** The ship's box: 3 blocks a side, 27 blocks. */
    private static final Vec3i SIZE = new Vec3i(3, 3, 3);

    /** How far the ship goes: two chunks east, clear of its own box. */
    private static final Vec3i EAST = new Vec3i(32, 0, 0);

    /** Far enough east for the largest box the defaults allow to move clear of itself. */
    private static final Vec3i CLEAR = new Vec3i(256, 0, 0);

    /** A short hop: one block east, onto the ship's own box. */
    private static final Vec3i HOP = new Vec3i(1, 0, 0);

    private static final int VOLUME = 27;
    private static final int SIDE = 3;

    private CeilingTests() {}

    // ---- The tests ----------------------------------------------------------------------

    /** A ship one block over the volume, though within the longest side, is refused as too large. */
    @GameTest(template = "empty", batch = "ship_ceiling_volume", timeoutTicks = TIMEOUT)
    public static void a_ship_over_the_volume_is_refused(GameTestHelper h) {
        assertRefusedUnder(h, area(0), EAST, VOLUME - 1, 64, 4_096, MoveRefusal.TOO_LARGE);
    }

    /** A ship one block over the longest side, though within the volume, is refused as too large. */
    @GameTest(template = "empty", batch = "ship_ceiling_side", timeoutTicks = TIMEOUT)
    public static void a_ship_over_the_longest_side_is_refused(GameTestHelper h) {
        assertRefusedUnder(h, area(1), EAST, 131_072, SIDE - 1, 4_096, MoveRefusal.TOO_LARGE);
    }

    /**
     * A ship just at both limits moves, and so does a move clear of its own box when the short-hop
     * limit is below it, since that limit is only for a hop.
     */
    @GameTest(template = "empty", batch = "ship_ceiling_within", timeoutTicks = TIMEOUT)
    public static void a_ship_at_both_limits_moves(GameTestHelper h) {
        assertMovesUnder(h, area(2), EAST, VOLUME, SIDE, 1);
    }

    /**
     * A short hop onto the ship's own box, over the short-hop limit though within the ceiling, is
     * refused as hop too large, and nothing changes.
     */
    @GameTest(template = "empty", batch = "ship_ceiling_hop_over", timeoutTicks = TIMEOUT)
    public static void a_short_hop_over_its_limit_is_refused(GameTestHelper h) {
        assertRefusedUnder(h, area(3), HOP, 131_072, 64, VOLUME - 1, MoveRefusal.HOP_TOO_LARGE);
    }

    /** A short hop onto the ship's own box, at the short-hop limit, moves. */
    @GameTest(template = "empty", batch = "ship_ceiling_hop_within", timeoutTicks = TIMEOUT)
    public static void a_short_hop_at_its_limit_moves(GameTestHelper h) {
        assertMovesUnder(h, area(4), HOP, 131_072, 64, VOLUME);
    }

    /**
     * The defaults, measured in #8: 131,072 blocks (64 × 64 × 32), 64 a side, and 4,096 blocks (16³)
     * for a short hop. Judged by dry runs far from any loaded chunk, since the ceiling is asked
     * before anything in the world is read.
     */
    @GameTest(template = "empty", batch = "ship_ceiling_defaults", timeoutTicks = TIMEOUT)
    public static void the_defaults_are_the_measured_ones(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Settings saved = Settings.now();
        try {
            new Settings(
                            ExpeditionConfig.MAX_VOLUME.getDefault(),
                            ExpeditionConfig.MAX_LONGEST_SIDE.getDefault(),
                            ExpeditionConfig.MAX_HOP_VOLUME.getDefault())
                    .apply();
            BlockPos far = new BlockPos(-20_480, 100, -40_960);
            assertTooLarge(h, level, far, new Vec3i(64, 64, 32), CLEAR, false);
            assertTooLarge(h, level, far, new Vec3i(64, 32, 64), CLEAR, false);
            assertTooLarge(h, level, far, new Vec3i(64, 64, 33), CLEAR, true);
            assertTooLarge(h, level, far, new Vec3i(65, 1, 1), CLEAR, true);
            assertHopTooLarge(h, level, far, new Vec3i(16, 16, 16), false);
            assertHopTooLarge(h, level, far, new Vec3i(16, 16, 17), true);
            // Clear of itself, the same box is no hop, so only the main ceiling applies.
            assertTooLarge(h, level, far, new Vec3i(16, 16, 17), CLEAR, false);
        } finally {
            saved.apply();
        }
        h.succeed();
    }

    // ---- What each test does ------------------------------------------------------------

    /**
     * Under the given settings, a move of the ship by {@code offset} is refused with {@code code},
     * the dry run says so first, and neither end changes.
     */
    private static void assertRefusedUnder(
            GameTestHelper h, BlockPos a, Vec3i offset, int volume, int side, int hop, String code) {
        ServerLevel level = h.getLevel();
        BoundingBox from = box(a);
        BoundingBox to = from.moved(offset.getX(), offset.getY(), offset.getZ());
        h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, level, from);
                    hold(h, level, to);
                })
                .thenExecute(() -> {
                    wipe(level, to);
                    build(level, a);
                    Map<BlockPos, BlockState> before = blocks(level, from, to);
                    MoveRequest request = MoveRequest.by(level, from, level, offset);
                    Settings saved = Settings.now();
                    try {
                        new Settings(volume, side, hop).apply();
                        DryRun dry = ShipMover.dryRun(request);
                        h.assertTrue(
                                dry.refusal().equals(Optional.of(code))
                                        && dry.obstacles().get(0).equals(new Obstacle.OfMove(code)),
                                "expected a dry run refused as " + code + ", found " + dry.obstacles());
                        MoveJob job = ShipMover.start(request);
                        h.assertTrue(job.isDone(), "a refused move is still going");
                        MoveResult result = job.result().join();
                        h.assertTrue(
                                result.refusal().equals(Optional.of(code)),
                                "expected " + code + ", the move gave " + result);
                    } finally {
                        saved.apply();
                    }
                    h.assertTrue(
                            blocks(level, from, to).equals(before), "a refused move changed the blocks at either end");
                    wipe(level, from);
                    wipe(level, to);
                })
                .thenSucceed();
    }

    /** Under the given settings, a move of the ship by {@code offset} goes ahead and carries it. */
    private static void assertMovesUnder(GameTestHelper h, BlockPos a, Vec3i offset, int volume, int side, int hop) {
        ServerLevel level = h.getLevel();
        BoundingBox from = box(a);
        BoundingBox to = from.moved(offset.getX(), offset.getY(), offset.getZ());
        MoveJob[] job = {null};
        h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, level, from);
                    hold(h, level, to);
                })
                .thenExecute(() -> {
                    wipe(level, to);
                    build(level, a);
                    MoveRequest request = MoveRequest.by(level, from, level, offset);
                    Settings saved = Settings.now();
                    try {
                        new Settings(volume, side, hop).apply();
                        DryRun dry = ShipMover.dryRun(request);
                        h.assertTrue(dry.wouldMove(), "the dry run found " + dry.obstacles());
                        job[0] = ShipMover.start(request);
                        if (job[0].isDone()) {
                            MoveResult result = job[0].result().join();
                            h.assertTrue(result.moved(), "the move within the limits gave " + result);
                        }
                    } finally {
                        saved.apply();
                    }
                })
                .thenWaitUntil(() -> h.assertTrue(job[0].isDone(), "the move is still going"))
                .thenExecute(() -> {
                    MoveResult result = job[0].result().join();
                    h.assertTrue(result.moved(), "the move within the limits gave " + result);
                    BlockPos b = a.offset(offset);
                    h.assertTrue(
                            level.getBlockState(b).is(Blocks.STONE_BRICKS)
                                    && level.getBlockState(b.offset(1, 2, 1)).is(Blocks.GLASS),
                            "the ship didn't arrive at " + b.toShortString());
                    h.assertTrue(level.getBlockState(a).isAir(), "the ship's floor stayed behind");
                    wipe(level, from);
                    wipe(level, to);
                })
                .thenSucceed();
    }

    private static void assertTooLarge(
            GameTestHelper h, ServerLevel level, BlockPos corner, Vec3i size, Vec3i offset, boolean refused) {
        BoundingBox box = BoundingBox.fromCorners(corner, corner.offset(size).offset(-1, -1, -1));
        DryRun dry = ShipMover.dryRun(MoveRequest.by(level, box, level, offset));
        boolean tooLarge = dry.refusal().equals(Optional.of(MoveRefusal.TOO_LARGE));
        h.assertTrue(
                tooLarge == refused,
                "a box of " + size.toShortString() + (refused ? " wasn't" : " was") + " too large: " + dry.codes());
        h.assertFalse(dry.codes().contains(MoveRefusal.HOP_TOO_LARGE), "a move clear of its box was a hop");
    }

    private static void assertHopTooLarge(
            GameTestHelper h, ServerLevel level, BlockPos corner, Vec3i size, boolean refused) {
        BoundingBox box = BoundingBox.fromCorners(corner, corner.offset(size).offset(-1, -1, -1));
        DryRun dry = ShipMover.dryRun(MoveRequest.by(level, box, level, HOP));
        h.assertFalse(
                dry.codes().contains(MoveRefusal.TOO_LARGE), "a hop of " + size.toShortString() + " was too large");
        h.assertTrue(
                dry.refusal().equals(Optional.of(MoveRefusal.HOP_TOO_LARGE)) == refused,
                "a hop of " + size.toShortString() + (refused ? " wasn't" : " was") + " too large: " + dry.codes());
    }

    // ---- The settings -------------------------------------------------------------------

    /** The ship settings at one moment, to set for a test and to put back after it. */
    private record Settings(int volume, int side, int hop) {
        static Settings now() {
            return new Settings(
                    ExpeditionConfig.MAX_VOLUME.getAsInt(),
                    ExpeditionConfig.MAX_LONGEST_SIDE.getAsInt(),
                    ExpeditionConfig.MAX_HOP_VOLUME.getAsInt());
        }

        /** Sets them in the loaded config, as a reload would, without writing the file. */
        void apply() {
            set(ExpeditionConfig.MAX_VOLUME, volume);
            set(ExpeditionConfig.MAX_LONGEST_SIDE, side);
            set(ExpeditionConfig.MAX_HOP_VOLUME, hop);
        }

        private static void set(ModConfigSpec.IntValue value, int to) {
            value.set(to);
        }
    }

    // ---- The ship -----------------------------------------------------------------------

    /** A test's area: chunk-aligned, far from the GameTest grid and from every other test's. */
    private static BlockPos area(int index) {
        return new BlockPos(-20_480 + 512 * index, 100, -35_840);
    }

    private static BoundingBox box(BlockPos corner) {
        return BoundingBox.fromCorners(corner, corner.offset(SIZE.getX() - 1, SIZE.getY() - 1, SIZE.getZ() - 1));
    }

    /** Loads the chunks around a box, keeps them loaded, and throws until their entities have loaded. */
    private static void hold(GameTestHelper h, ServerLevel level, BoundingBox box) {
        for (int x = (box.minX() - 1) >> 4; x <= (box.maxX() + 1) >> 4; x++) {
            for (int z = (box.minZ() - 1) >> 4; z <= (box.maxZ() + 1) >> 4; z++) {
                ChunkPos c = new ChunkPos(x, z);
                level.getChunkSource().addRegionTicket(HELD, c, 2, c);
                level.getChunk(x, z);
                h.assertTrue(level.areEntitiesLoaded(c.toLong()), "entities aren't loaded yet in " + c);
            }
        }
    }

    /** A floor of stone bricks and a glass block in the middle of the top layer. */
    private static void build(ServerLevel level, BlockPos corner) {
        wipe(level, box(corner));
        for (int x = 0; x < SIZE.getX(); x++) {
            for (int z = 0; z < SIZE.getZ(); z++) {
                level.setBlock(corner.offset(x, 0, z), Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        level.setBlock(corner.offset(1, 2, 1), Blocks.GLASS.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    /** Takes every block out of a box, quietly, so nothing drops. */
    private static void wipe(ServerLevel level, BoundingBox box) {
        for (BlockPos p : positions(box)) {
            LevelChunk chunk = level.getChunkAt(p);
            QuietBlocks.set(level, chunk, p, Blocks.AIR.defaultBlockState());
        }
    }

    private static Iterable<BlockPos> positions(BoundingBox box) {
        return BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
    }

    private static Map<BlockPos, BlockState> blocks(ServerLevel level, BoundingBox... boxes) {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (BoundingBox box : boxes) {
            for (BlockPos p : positions(box)) {
                blocks.put(p.immutable(), level.getBlockState(p));
            }
        }
        return blocks;
    }
}
