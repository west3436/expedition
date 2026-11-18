package io.github.west3436.expedition.gametest;

import com.mojang.authlib.GameProfile;
import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.mover.ClaimCheck;
import io.github.west3436.expedition.mover.DryRun;
import io.github.west3436.expedition.mover.MoveJob;
import io.github.west3436.expedition.mover.MoveRefusal;
import io.github.west3436.expedition.mover.MoveRequest;
import io.github.west3436.expedition.mover.MoveResult;
import io.github.west3436.expedition.mover.MoveSettings;
import io.github.west3436.expedition.mover.Obstacle;
import io.github.west3436.expedition.mover.QuietBlocks;
import io.github.west3436.expedition.mover.ShipMover;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * Claimed and protected land (#31): a move a player started is refused with {@code claimed} where
 * that player may not build, at either end, unless they are an operator; a move with no player is
 * never asked.
 *
 * <p>The GameTest server protects no spawn ({@link net.minecraft.server.MinecraftServer
 * #isUnderSpawnProtection} is a dedicated server's), so the first test stands in a dedicated
 * server's spawn protection, by its own rule, around a spawn at the destination. The second stands
 * in a claim mod, which cancels NeoForge's block events.
 *
 * <p>The ship is a floor of stone bricks in a box 3 blocks a side, going two chunks east. Each test
 * builds at its own chunk-aligned area, away from the other tests', and clears both ends when done.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ClaimTests {
    private static final String BATCH = "claims";

    /** As in {@link MoverTests}: a far chunk's entities load in real time, which can be slow. */
    private static final int TIMEOUT = 20_000;

    private static final TicketType<ChunkPos> HELD =
            TicketType.create("expedition_gametest_claims", Comparator.comparingLong(ChunkPos::toLong), TIMEOUT);

    private static final Vec3i SIZE = new Vec3i(3, 3, 3);
    private static final Vec3i EAST = new Vec3i(32, 0, 0);

    /** The player who isn't an operator, until the test makes them one. */
    private static final GameProfile PILOT =
            new GameProfile(UUID.fromString("6f1c3a52-31a0-4c5e-9d0e-31a7c1a1e031"), "expedition_claim_pilot");

    /** The player a stand-in claim mod keeps out. */
    private static final GameProfile TRESPASSER =
            new GameProfile(UUID.fromString("6f1c3a52-31a0-4c5e-9d0e-31a7c1a1e032"), "expedition_claim_trespasser");

    /** The stand-in claim mod's one claim, if it keeps one now; read on the server thread. */
    private static @Nullable Claim claim;

    private static boolean listening;

    private ClaimTests() {}

    // ---- The tests ----------------------------------------------------------------------

    /**
     * A move into spawn protection is refused for a player who isn't an operator, and nothing
     * changes; a dry run names the destination's chunk. The same move is allowed for an operator,
     * and a move with no player, as from the console, goes ahead.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void a_move_into_spawn_protection_is_refused_for_a_player_who_is_not_an_operator(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos a = area(0);
        BlockPos b = a.offset(EAST);
        MoveRequest request = MoveRequest.by(level, box(a), level, EAST);
        MoveJob[] job = {null};
        h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, level, box(a));
                    hold(h, level, box(b));
                })
                .thenExecute(() -> {
                    build(level, a);
                    wipe(level, box(b));
                    Map<BlockPos, BlockState> before = blocks(level, a, b);
                    ServerPlayer pilot = FakePlayerFactory.get(level, PILOT);
                    PlayerList players = level.getServer().getPlayerList();
                    h.assertFalse(players.isOp(PILOT), "the pilot is an operator already");
                    MoveSettings settings = MoveSettings.DEFAULT.withChecks(
                            List.of(new ClaimCheck(pilot, spawnProtection(b.offset(1, 0, 1), 1))));

                    MoveJob refused = ShipMover.start(request, settings);
                    h.assertTrue(refused.isDone(), "a refused move is still going");
                    MoveResult result = refused.result().join();
                    h.assertTrue(result.refusal().equals(Optional.of(MoveRefusal.CLAIMED)), "the move gave " + result);
                    h.assertTrue(blocks(level, a, b).equals(before), "the refused move changed something");
                    DryRun dry = ShipMover.dryRun(request, settings);
                    h.assertTrue(
                            dry.obstacles()
                                    .equals(List.of(new Obstacle.InChunk(
                                            MoveRefusal.CLAIMED, level.dimension(), new ChunkPos(b)))),
                            "the dry run found " + dry.obstacles());

                    players.op(PILOT);
                    try {
                        DryRun asOperator = ShipMover.dryRun(request, settings);
                        h.assertTrue(
                                asOperator.wouldMove(), "an operator's move was refused: " + asOperator.obstacles());
                    } finally {
                        players.deop(PILOT);
                    }

                    h.assertTrue(
                            MoveSettings.DEFAULT.startedBy(null).equals(MoveSettings.DEFAULT),
                            "a move with no player has a check");
                    h.assertTrue(
                            MoveSettings.DEFAULT.startedBy(pilot).checks().size() == 1,
                            "a move a player started has no claim check");
                    job[0] = ShipMover.start(request, MoveSettings.DEFAULT.startedBy(null));
                })
                .thenWaitUntil(() -> h.assertTrue(job[0].isDone(), "the move is still going"))
                .thenExecute(() -> {
                    MoveResult result = job[0].result().join();
                    h.assertTrue(result.moved(), "the move with no player gave " + result);
                    h.assertTrue(level.getBlockState(b).is(Blocks.STONE_BRICKS), "the ship didn't arrive at " + b);
                    wipe(level, box(a));
                    wipe(level, box(b));
                })
                .thenSucceed();
    }

    /**
     * A claim mod that cancels the block events keeps a player's move out of its claim, at the
     * source (breaking) and at the destination (placing), and a dry run names the claimed chunk at
     * each end. With no claim, the same move would go ahead.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void a_claim_kept_by_another_mod_refuses_the_move_at_either_end(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos a = area(1);
        BlockPos b = a.offset(EAST);
        MoveRequest request = MoveRequest.by(level, box(a), level, EAST);
        h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, level, box(a));
                    hold(h, level, box(b));
                })
                .thenExecute(() -> {
                    listen();
                    build(level, a);
                    wipe(level, box(b));
                    Map<BlockPos, BlockState> before = blocks(level, a, b);
                    ServerPlayer trespasser = FakePlayerFactory.get(level, TRESPASSER);
                    // The server itself protects nothing here.
                    MoveSettings settings = MoveSettings.DEFAULT.withChecks(
                            List.of(new ClaimCheck(trespasser, (l, pos, player) -> true)));
                    try {
                        claim = new Claim(level.dimension(), box(a), false);
                        assertRefusedAt(h, level, request, settings, new ChunkPos(a));
                        claim = new Claim(level.dimension(), box(b), true);
                        assertRefusedAt(h, level, request, settings, new ChunkPos(b));
                        // A claim on breaking at the destination isn't asked there: the ship places.
                        claim = new Claim(level.dimension(), box(b), false);
                        DryRun unclaimed = ShipMover.dryRun(request, settings);
                        h.assertTrue(unclaimed.wouldMove(), "the dry run found " + unclaimed.obstacles());
                    } finally {
                        claim = null;
                    }
                    h.assertTrue(blocks(level, a, b).equals(before), "the refused moves changed something");
                    wipe(level, box(a));
                    wipe(level, box(b));
                })
                .thenSucceed();
    }

    // ---- Stand-ins ----------------------------------------------------------------------

    /**
     * Spawn protection as a dedicated server keeps it ({@code DedicatedServer#isUnderSpawnProtection}),
     * around a spawn of the test's own: in the overworld, no closer to the spawn than {@code radius}
     * along x or z. (The server's operators are let through by the claim check itself.)
     */
    private static ClaimCheck.Protection spawnProtection(BlockPos spawn, int radius) {
        return (level, pos, player) -> level.dimension() != Level.OVERWORLD
                || Math.max(Math.abs(pos.getX() - spawn.getX()), Math.abs(pos.getZ() - spawn.getZ())) > radius;
    }

    /** One claim of the stand-in claim mod: on breaking, or on placing, inside a box. */
    private record Claim(net.minecraft.resources.ResourceKey<Level> level, BoundingBox box, boolean placing) {
        boolean keepsOut(Level in, BlockPos pos, boolean placed) {
            return in.dimension() == level && placed == placing && box.isInside(pos);
        }
    }

    /** Listens, once, as a claim mod would: cancels the trespasser's events inside the claim. */
    private static void listen() {
        if (listening) {
            return;
        }
        listening = true;
        NeoForge.EVENT_BUS.addListener((BlockEvent.BreakEvent event) -> {
            Claim now = claim;
            if (now != null
                    && event.getPlayer().getUUID().equals(TRESPASSER.getId())
                    && now.keepsOut((Level) event.getLevel(), event.getPos(), false)) {
                event.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener((BlockEvent.EntityPlaceEvent event) -> {
            Claim now = claim;
            if (now != null
                    && event.getEntity() != null
                    && event.getEntity().getUUID().equals(TRESPASSER.getId())
                    && now.keepsOut((Level) event.getLevel(), event.getPos(), true)) {
                event.setCanceled(true);
            }
        });
    }

    // ---- The ship -----------------------------------------------------------------------

    /** A test's area: chunk-aligned, far from the GameTest grid and from the other tests' areas. */
    private static BlockPos area(int index) {
        return new BlockPos(-20_480 + 512 * index, 100, -46_080);
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

    /** A floor of stone bricks. */
    private static void build(ServerLevel level, BlockPos corner) {
        wipe(level, box(corner));
        for (int x = 0; x < SIZE.getX(); x++) {
            for (int z = 0; z < SIZE.getZ(); z++) {
                level.setBlock(corner.offset(x, 0, z), Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    /** Takes every block out of a box, quietly, so nothing drops. */
    private static void wipe(ServerLevel level, BoundingBox box) {
        for (BlockPos p : positions(box)) {
            QuietBlocks.set(level, level.getChunkAt(p), p, Blocks.AIR.defaultBlockState());
        }
    }

    private static Iterable<BlockPos> positions(BoundingBox box) {
        return BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
    }

    private static Map<BlockPos, BlockState> blocks(ServerLevel level, BlockPos... corners) {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (BlockPos corner : corners) {
            for (BlockPos p : positions(box(corner))) {
                blocks.put(p.immutable(), level.getBlockState(p));
            }
        }
        return blocks;
    }

    // ---- Assertions ---------------------------------------------------------------------

    private static void assertRefusedAt(
            GameTestHelper h, ServerLevel level, MoveRequest request, MoveSettings settings, ChunkPos chunk) {
        MoveJob job = ShipMover.start(request, settings);
        h.assertTrue(job.isDone(), "a refused move is still going");
        MoveResult result = job.result().join();
        h.assertTrue(result.refusal().equals(Optional.of(MoveRefusal.CLAIMED)), "the move gave " + result);
        DryRun dry = ShipMover.dryRun(request, settings);
        h.assertTrue(
                dry.obstacles().equals(List.of(new Obstacle.InChunk(MoveRefusal.CLAIMED, level.dimension(), chunk))),
                "with " + claim + ", the dry run found " + dry.obstacles());
    }
}
