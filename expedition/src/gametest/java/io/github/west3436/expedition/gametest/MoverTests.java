package io.github.west3436.expedition.gametest;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.mover.LightChecks;
import io.github.west3436.expedition.mover.MoveCheck;
import io.github.west3436.expedition.mover.MoveJob;
import io.github.west3436.expedition.mover.MoveRequest;
import io.github.west3436.expedition.mover.MoveResult;
import io.github.west3436.expedition.mover.MoveSettings;
import io.github.west3436.expedition.mover.QuietBlocks;
import io.github.west3436.expedition.mover.RelocationHandover;
import io.github.west3436.expedition.mover.ShipMover;
import io.github.west3436.expedition.mover.ShipSurvey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The mover in a real level (#5): a small ship, a room of stone bricks and glass holding a chest of
 * items, a furnace part-way through smelting, a torch, a snow layer and a pig, moved 100 blocks
 * within the Overworld and again into the Nether, sliced over many ticks, hopped a short way onto
 * itself, and handed over to a recording {@link RelocationHandover}.
 *
 * <p>Each test builds its ship far from the GameTest grid, at its own chunk-aligned area, holds the
 * chunks at both ends loaded, and clears both ends when it is done.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MoverTests {
    private static final String BATCH = "mover";

    /**
     * Each test's limit in game ticks. A GameTest server ticks as fast as it can, and a far chunk's
     * entities load from disk in real time, which on a busy machine can take thousands of ticks.
     */
    private static final int TIMEOUT = 20_000;

    /** Keeps a test's areas loaded and their entities ticking; lapses when the test would time out. */
    private static final TicketType<ChunkPos> HELD =
            TicketType.create("expedition_gametest_held", Comparator.comparingLong(ChunkPos::toLong), TIMEOUT);

    /** The ship's box: 5 wide, 4 high, 5 deep. */
    private static final Vec3i SIZE = new Vec3i(5, 4, 5);

    /** A floor and a roof of 25 stone bricks each, 32 glass walls, a chest, a furnace and a torch. */
    private static final int MASS = 25 + 25 + 32 + 3;

    /** The solid blocks and the snow layer, which isn't solid. */
    private static final int BLOCKS = MASS + 1;

    private static final Vec3i CHEST = new Vec3i(1, 1, 1);
    private static final Vec3i FURNACE = new Vec3i(3, 1, 1);
    private static final Vec3i TORCH = new Vec3i(1, 1, 3);
    private static final Vec3i SNOW = new Vec3i(3, 1, 3);
    private static final Vec3 PIG = new Vec3(2.5, 1, 2.5);
    private static final float PIG_HEALTH = 7;
    private static final int COOK_TIME = 77;
    private static final int BURN_TIME = 1_000;

    private MoverTests() {}

    // ---- The tests ----------------------------------------------------------------------

    /**
     * The acceptance test: the ship moves 100 blocks east within the Overworld, then into the Nether,
     * and after each move every block, the chest's items, the furnace's progress and fuel and the pig
     * are at the destination, the source is air, nothing dropped, the light followed the torch and
     * the furnace carries on smelting.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void moves_a_ship_100_blocks_and_into_the_nether(GameTestHelper h) {
        ServerLevel overworld = h.getLevel();
        ServerLevel nether = level(h, Level.NETHER);
        BlockPos a = area(0);
        BlockPos b = a.offset(100, 0, 0);
        BlockPos c = new BlockPos(b.getX(), 64, b.getZ());
        Run run = new Run();
        long[] batches = {0};
        h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, overworld, box(a));
                    hold(h, overworld, box(b));
                    // The furnace has to go on smelting in the Nether long after it has emptied.
                    force(nether, box(c), true);
                    hold(h, nether, box(c));
                })
                .thenExecute(() -> {
                    // The Nether is netherrack at y 64, and a move never writes over a solid block.
                    wipe(nether, box(c));
                    run.pig = build(overworld, a);
                    ShipSurvey survey = ShipSurvey.of(overworld, box(a));
                    h.assertTrue(survey.equals(new ShipSurvey(MASS, BLOCKS, 2)), "the survey says " + survey);
                    run.before = snapshot(overworld, a, run.pig);
                    batches[0] = LightChecks.batchesTaken();
                    run.job = ShipMover.start(MoveRequest.by(overworld, box(a), overworld, new Vec3i(100, 0, 0)));
                })
                .thenWaitUntil(() -> h.assertTrue(run.job.isDone(), "the move within the Overworld is still going"))
                .thenExecute(() -> {
                    // The light checks went to the light engine in batches (#69), and the light
                    // still follows the torch below.
                    h.assertTrue(
                            LightChecks.batchesTaken() > batches[0],
                            "the light engine took none of the move's batches");
                    assertCarriedAll(h, run.job.result().join());
                    assertArrived(h, run.before, overworld, b);
                    assertLeft(h, overworld, box(a), run.pig, false);
                })
                .thenWaitUntil(() -> {
                    assertLit(h, overworld, b, a);
                    assertSmelting(h, overworld, b, run.before);
                })
                .thenExecute(() -> {
                    run.before = snapshot(overworld, b, run.pig);
                    run.job = ShipMover.start(new MoveRequest(overworld, box(b), nether, c));
                })
                .thenWaitUntil(() -> h.assertTrue(run.job.isDone(), "the move into the Nether is still going"))
                .thenExecute(() -> {
                    assertCarriedAll(h, run.job.result().join());
                    assertArrived(h, run.before, nether, c);
                    assertLeft(h, overworld, box(b), run.pig, true);
                })
                .thenWaitUntil(() -> {
                    assertLit(h, nether, c, b);
                    assertSmelting(h, nether, c, run.before);
                })
                .thenExecute(() -> {
                    wipe(overworld, box(a));
                    wipe(overworld, box(b));
                    wipe(nether, box(c));
                    force(nether, box(c), false);
                })
                .thenSucceed();
    }

    /**
     * On a budget of 24 work units a tick, the same move takes dozens of ticks, and still carries
     * everything once and leaves nothing behind.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void a_move_over_budget_is_sliced_over_many_ticks(GameTestHelper h) {
        ServerLevel overworld = h.getLevel();
        BlockPos a = area(1);
        BlockPos b = a.offset(100, 0, 0);
        Run run = new Run();
        h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, overworld, box(a));
                    hold(h, overworld, box(b));
                })
                .thenExecute(() -> {
                    run.pig = build(overworld, a);
                    run.before = snapshot(overworld, a, run.pig);
                    MoveSettings tiny = MoveSettings.DEFAULT.withBudget(24, ShipMover.SERVER_NANOS_PER_TICK);
                    run.job = ShipMover.start(MoveRequest.by(overworld, box(a), overworld, new Vec3i(100, 0, 0)), tiny);
                    h.assertFalse(run.job.isDone(), "a move of over a thousand work units finished on a budget of 24");
                })
                .thenWaitUntil(() -> h.assertTrue(run.job.isDone(), "the sliced move is still going"))
                .thenExecute(() -> {
                    MoveResult result = run.job.result().join();
                    assertCarriedAll(h, result);
                    h.assertTrue(result.ticks() > 10, "the sliced move took only " + result.ticks() + " ticks");
                    assertArrived(h, run.before, overworld, b);
                    assertLeft(h, overworld, box(a), run.pig, false);
                    wipe(overworld, box(a));
                    wipe(overworld, box(b));
                })
                .thenSucceed();
    }

    /**
     * A hop of two blocks east, one up and one north lands on the ship's own box; it is done in one
     * tick, in an order that empties each position before it is written.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void a_short_hop_onto_its_own_box_moves_whole(GameTestHelper h) {
        ServerLevel overworld = h.getLevel();
        BlockPos a = area(2);
        Vec3i offset = new Vec3i(2, 1, -1);
        BlockPos b = a.offset(offset);
        BoundingBox both = box(a).inflatedBy(2);
        Run run = new Run();
        h.startSequence()
                .thenWaitUntil(() -> hold(h, overworld, both))
                .thenExecute(() -> {
                    run.pig = build(overworld, a);
                    run.before = snapshot(overworld, a, run.pig);
                    run.job = ShipMover.start(MoveRequest.by(overworld, box(a), overworld, offset));
                })
                .thenWaitUntil(() -> h.assertTrue(run.job.isDone(), "the hop is still going"))
                .thenExecute(() -> {
                    assertCarriedAll(h, run.job.result().join());
                    assertArrived(h, run.before, overworld, b);
                    BoundingBox left = box(a);
                    BoundingBox arrived = box(b);
                    for (BlockPos p : BlockPos.betweenClosed(
                            left.minX(), left.minY(), left.minZ(), left.maxX(), left.maxY(), left.maxZ())) {
                        if (!arrived.isInside(p)) {
                            h.assertTrue(overworld.getBlockState(p).isAir(), "the hop left a block at " + p);
                        }
                    }
                    assertNothingDropped(h, overworld, both);
                    wipe(overworld, both);
                })
                .thenSucceed();
    }

    /**
     * The hand-over begins before any block moves and holds the move until it is ready; it is
     * completed once, after every block and the pig have arrived.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void the_handover_begins_before_and_completes_after(GameTestHelper h) {
        ServerLevel overworld = h.getLevel();
        BlockPos a = area(3);
        BlockPos b = a.offset(100, 0, 0);
        Run run = new Run();
        h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, overworld, box(a));
                    hold(h, overworld, box(b));
                })
                .thenExecute(() -> {
                    run.pig = build(overworld, a);
                    run.before = snapshot(overworld, a, run.pig);
                    run.recorder = new Recorder(overworld, a, b, run.pig, new CompletableFuture<>());
                    run.job = ShipMover.start(
                            MoveRequest.by(overworld, box(a), overworld, new Vec3i(100, 0, 0)),
                            MoveSettings.DEFAULT.withHandover(run.recorder));
                    h.assertTrue(
                            run.recorder.events.equals(List.of("begin: ship at the source, destination clear")),
                            "the hand-over saw " + run.recorder.events);
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    h.assertFalse(run.job.isDone(), "the move went ahead before the hand-over was ready");
                    assertArrived(h, run.before, overworld, a);
                    assertClear(h, overworld, box(b));
                    run.recorder.begun.complete(Optional.empty());
                })
                .thenWaitUntil(() -> h.assertTrue(run.job.isDone(), "the move is still going"))
                .thenExecute(() -> {
                    assertCarriedAll(h, run.job.result().join());
                    h.assertTrue(
                            run.recorder.events.equals(List.of(
                                    "begin: ship at the source, destination clear",
                                    "complete by [100, 0, 0]: ship and pig at the destination, source clear")),
                            "the hand-over saw " + run.recorder.events);
                    assertArrived(h, run.before, overworld, b);
                    wipe(overworld, box(a));
                    wipe(overworld, box(b));
                })
                .thenSucceed();
    }

    /**
     * A check that refuses at the start changes nothing and begins no hand-over; one that refuses
     * once the hand-over is ready cancels it, and changes nothing either.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void a_refusal_changes_nothing_and_cancels_the_handover(GameTestHelper h) {
        ServerLevel overworld = h.getLevel();
        BlockPos a = area(4);
        BlockPos b = a.offset(100, 0, 0);
        MoveRequest request = MoveRequest.by(overworld, box(a), overworld, new Vec3i(100, 0, 0));
        int[] calls = {0};
        Run run = new Run();
        h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, overworld, box(a));
                    hold(h, overworld, box(b));
                })
                .thenExecute(() -> {
                    run.pig = build(overworld, a);
                    run.before = snapshot(overworld, a, run.pig);

                    Recorder early =
                            new Recorder(overworld, a, b, run.pig, CompletableFuture.completedFuture(Optional.empty()));
                    MoveCheck refuse = r -> Optional.of("test_refusal");
                    MoveJob refused = ShipMover.start(
                            request, MoveSettings.DEFAULT.withHandover(early).withChecks(List.of(refuse)));
                    h.assertTrue(refused.isDone(), "a refused move is still going");
                    Optional<String> refusal = refused.result().join().refusal();
                    h.assertTrue(refusal.equals(Optional.of("test_refusal")), "the refusal was " + refusal);
                    h.assertTrue(
                            early.events.isEmpty(), "a move refused at the start told the hand-over " + early.events);

                    MoveCheck second = r -> ++calls[0] == 2 ? Optional.of("test_late_refusal") : Optional.empty();
                    run.recorder =
                            new Recorder(overworld, a, b, run.pig, CompletableFuture.completedFuture(Optional.empty()));
                    run.job = ShipMover.start(
                            request,
                            MoveSettings.DEFAULT.withHandover(run.recorder).withChecks(List.of(second)));
                })
                .thenWaitUntil(() -> h.assertTrue(run.job.isDone(), "the late refusal is still going"))
                .thenExecute(() -> {
                    MoveResult result = run.job.result().join();
                    h.assertTrue(
                            result.refusal().equals(Optional.of("test_late_refusal")),
                            "the late refusal was " + result.refusal());
                    h.assertFalse(result.moved(), "a refused move says it moved");
                    h.assertTrue(calls[0] == 2, "the check was asked " + calls[0] + " times, not twice");
                    h.assertTrue(
                            run.recorder.events.equals(
                                    List.of("begin: ship at the source, destination clear", "cancel")),
                            "the hand-over saw " + run.recorder.events);
                    assertArrived(h, run.before, overworld, a);
                    assertClear(h, overworld, box(b));
                    assertNothingDropped(h, overworld, box(a));
                    wipe(overworld, box(a));
                })
                .thenSucceed();
    }

    /**
     * An item frame hung under the ship's roof, on a move of one position a tick: the roof would go
     * in the first ticks and the rest of the box over 125 more, longer than the 101 ticks between a
     * frame's checks of what holds it up. The frame goes first, with the roof above it, and arrives
     * still hung, holding its item, and nothing drops. The integrity fuzz (#12) found frames popping
     * off in such moves, before the mover carried them first.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void an_item_frame_stays_hung_through_a_slow_move(GameTestHelper h) {
        ServerLevel overworld = h.getLevel();
        BlockPos a = area(5);
        Vec3i offset = new Vec3i(100, 0, 0);
        BoundingBox from = BoundingBox.fromCorners(a, a.offset(4, 5, 4));
        BoundingBox to = from.moved(offset.getX(), offset.getY(), offset.getZ());
        Vec3i frameAt = new Vec3i(2, 4, 2);
        UUID[] frame = new UUID[1];
        MoveJob[] job = new MoveJob[1];
        h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, overworld, from);
                    hold(h, overworld, to);
                })
                .thenExecute(() -> {
                    wipe(overworld, from);
                    wipe(overworld, to);
                    for (BlockPos p : BlockPos.betweenClosed(
                            from.minX(), from.maxY(), from.minZ(), from.maxX(), from.maxY(), from.maxZ())) {
                        overworld.setBlock(p, Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                    ItemFrame hung = new ItemFrame(overworld, a.offset(frameAt), Direction.DOWN);
                    ItemStack keepsake = new ItemStack(Items.DIAMOND);
                    keepsake.set(DataComponents.CUSTOM_NAME, Component.literal("Keepsake"));
                    hung.setItem(keepsake, false);
                    h.assertTrue(hung.survives(), "the frame can't hang under the roof");
                    overworld.addFreshEntity(hung);
                    frame[0] = hung.getUUID();
                    MoveSettings slow = MoveSettings.DEFAULT.withBudget(1, ShipMover.SERVER_NANOS_PER_TICK);
                    job[0] = ShipMover.start(MoveRequest.by(overworld, from, overworld, offset), slow);
                })
                .thenWaitUntil(() -> h.assertTrue(job[0].isDone(), "the slow move is still going"))
                .thenExecute(() -> {
                    MoveResult result = job[0].result().join();
                    h.assertTrue(result.moved(), "the move was refused: " + result.refusal());
                    h.assertTrue(result.ticks() > 120, "the slow move took only " + result.ticks() + " ticks");
                    h.assertTrue(result.entities() == 1 && result.entitiesLeft() == 0, "the move carried " + result);
                    Entity moved = overworld.getEntity(frame[0]);
                    if (!(moved instanceof ItemFrame f) || !f.isAlive()) {
                        throw new GameTestAssertException("the item frame is gone: " + moved);
                    }
                    BlockPos expected = a.offset(frameAt).offset(offset);
                    h.assertTrue(f.getPos().equals(expected), "the frame is at " + f.getPos() + ", not " + expected);
                    h.assertTrue(
                            f.getItem().is(Items.DIAMOND)
                                    && f.getItem().getHoverName().getString().equals("Keepsake"),
                            "the frame holds " + f.getItem());
                    h.assertTrue(f.survives(), "the frame can't hang where it arrived");
                    assertNothingDropped(h, overworld, from);
                    assertNothingDropped(h, overworld, to);
                })
                .thenIdle(105)
                .thenExecute(() -> {
                    h.assertTrue(overworld.getEntity(frame[0]) instanceof ItemFrame, "the frame fell after arriving");
                    assertNothingDropped(h, overworld, to);
                    wipe(overworld, from);
                    wipe(overworld, to);
                })
                .thenSucceed();
    }

    // ---- The ship -----------------------------------------------------------------------

    /** What a ship held at one moment: its blocks, the chest's and furnace's saved state and the pig. */
    private record Ship(
            Map<Vec3i, BlockState> blocks, CompoundTag chest, CompoundTag furnace, UUID pig, long gameTime) {}

    /** A test's area: chunk-aligned, far from the GameTest grid and from every other test's. */
    private static BlockPos area(int index) {
        return new BlockPos(-20_480 + 512 * index, 100, -20_480);
    }

    private static BoundingBox box(BlockPos corner) {
        return BoundingBox.fromCorners(corner, corner.offset(SIZE.getX() - 1, SIZE.getY() - 1, SIZE.getZ() - 1));
    }

    private static ServerLevel level(GameTestHelper h, ResourceKey<Level> dimension) {
        ServerLevel level = h.getLevel().getServer().getLevel(dimension);
        if (level == null) {
            throw new GameTestAssertException("no " + dimension.location());
        }
        return level;
    }

    /** One test's ship as it goes: the pig, what the ship held before the move, the move. */
    private static final class Run {
        UUID pig;
        Ship before;
        MoveJob job;
        Recorder recorder;
    }

    /**
     * Loads the chunks around a box, keeps them loaded with their entities ticking, and throws until
     * their entities have loaded too: a pig added before then can't be found.
     */
    private static void hold(GameTestHelper h, ServerLevel level, BoundingBox box) {
        for (int x = (box.minX() - 1) >> 4; x <= (box.maxX() + 1) >> 4; x++) {
            for (int z = (box.minZ() - 1) >> 4; z <= (box.maxZ() + 1) >> 4; z++) {
                ChunkPos c = new ChunkPos(x, z);
                level.getChunkSource().addRegionTicket(HELD, c, 2, c);
                level.getChunk(x, z);
                h.assertTrue(
                        level.areEntitiesLoaded(c.toLong()),
                        "entities aren't loaded yet in " + c + " of "
                                + level.dimension().location());
            }
        }
    }

    /**
     * Force-loads the chunks {@link #hold} holds around a box, or lets them go. A level with no player
     * and no forced chunk stops ticking its entities and block entities 300 ticks after it empties,
     * and region tickets don't count. The GameTest runner force-loads the chunks of each test's
     * structure in the Overworld, so the Overworld keeps ticking, but nothing does in the Nether.
     */
    private static void force(ServerLevel level, BoundingBox box, boolean forced) {
        for (int x = (box.minX() - 1) >> 4; x <= (box.maxX() + 1) >> 4; x++) {
            for (int z = (box.minZ() - 1) >> 4; z <= (box.maxZ() + 1) >> 4; z++) {
                level.setChunkForced(x, z, forced);
            }
        }
    }

    /**
     * Builds the ship with its lowest corner at {@code corner}: a floor and roof of stone bricks,
     * glass walls, and inside a chest of items, a lit furnace part-way through smelting raw iron, a
     * torch, a snow layer and a pig that stays still.
     *
     * @return the pig's id
     */
    private static UUID build(ServerLevel level, BlockPos corner) {
        BoundingBox box = box(corner);
        wipe(level, box);
        for (BlockPos p :
                BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            int x = p.getX() - corner.getX();
            int y = p.getY() - corner.getY();
            int z = p.getZ() - corner.getZ();
            BlockState state;
            if (y == 0 || y == SIZE.getY() - 1) {
                state = Blocks.STONE_BRICKS.defaultBlockState();
            } else if (x == 0 || x == SIZE.getX() - 1 || z == 0 || z == SIZE.getZ() - 1) {
                state = Blocks.GLASS.defaultBlockState();
            } else {
                continue;
            }
            level.setBlock(p, state, Block.UPDATE_CLIENTS);
        }
        HolderLookup.Provider registries = level.registryAccess();

        BlockPos chestAt = corner.offset(CHEST);
        level.setBlock(
                chestAt,
                Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH),
                Block.UPDATE_CLIENTS);
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(chestAt);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 7));
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Keepsake"));
        sword.setDamageValue(17);
        chest.setItem(4, sword);
        chest.setItem(26, new ItemStack(Items.COBBLESTONE, 32));

        BlockPos furnaceAt = corner.offset(FURNACE);
        level.setBlock(
                furnaceAt,
                Blocks.FURNACE
                        .defaultBlockState()
                        .setValue(AbstractFurnaceBlock.FACING, Direction.SOUTH)
                        .setValue(AbstractFurnaceBlock.LIT, true),
                Block.UPDATE_CLIENTS);
        FurnaceBlockEntity furnace = (FurnaceBlockEntity) level.getBlockEntity(furnaceAt);
        furnace.setItem(0, new ItemStack(Items.RAW_IRON, 5));
        furnace.setItem(1, new ItemStack(Items.COAL, 3));
        CompoundTag smelting = furnace.saveWithoutMetadata(registries);
        smelting.putInt("BurnTime", BURN_TIME);
        smelting.putInt("CookTime", COOK_TIME);
        smelting.putInt("CookTimeTotal", 200);
        furnace.loadWithComponents(smelting, registries);
        furnace.setChanged();

        level.setBlock(corner.offset(TORCH), Blocks.TORCH.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(corner.offset(SNOW), Blocks.SNOW.defaultBlockState(), Block.UPDATE_CLIENTS);

        Pig pig = EntityType.PIG.create(level);
        pig.moveTo(corner.getX() + PIG.x, corner.getY() + PIG.y, corner.getZ() + PIG.z, 90, 0);
        pig.setNoAi(true);
        pig.setCustomName(Component.literal("Passenger"));
        pig.setHealth(PIG_HEALTH);
        level.addFreshEntity(pig);
        return pig.getUUID();
    }

    /** What the ship with its lowest corner at {@code corner} holds now. */
    private static Ship snapshot(ServerLevel level, BlockPos corner, UUID pig) {
        Map<Vec3i, BlockState> blocks = new HashMap<>();
        BoundingBox box = box(corner);
        for (BlockPos p :
                BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            blocks.put(p.subtract(corner), level.getBlockState(p));
        }
        return new Ship(
                blocks,
                saved(level, corner.offset(CHEST)),
                saved(level, corner.offset(FURNACE)),
                pig,
                level.getGameTime());
    }

    private static CompoundTag saved(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            throw new GameTestAssertException("no block entity at " + pos.toShortString());
        }
        return blockEntity.saveWithoutMetadata(level.registryAccess());
    }

    /** Takes every block and entity out of a box, quietly, so nothing drops. */
    private static void wipe(ServerLevel level, BoundingBox box) {
        for (Entity e : level.getEntities((Entity) null, AABB.of(box).inflate(2), e -> !(e instanceof Player))) {
            e.discard();
        }
        for (BlockPos p :
                BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            LevelChunk chunk = level.getChunkAt(p);
            QuietBlocks.takeBlockEntity(level, chunk, p);
            QuietBlocks.set(level, chunk, p, Blocks.AIR.defaultBlockState());
        }
    }

    // ---- What a move must leave ---------------------------------------------------------

    private static void assertCarriedAll(GameTestHelper h, MoveResult result) {
        h.assertTrue(result.moved(), "the move was refused: " + result.refusal());
        h.assertTrue(
                result.mass() == MASS
                        && result.blocks() == BLOCKS
                        && result.blockEntities() == 2
                        && result.entities() == 1
                        && result.entitiesLeft() == 0,
                "the move carried " + result);
    }

    /** The ship is at {@code corner} as it was, the furnace as far on as the ticks since allow. */
    private static void assertArrived(GameTestHelper h, Ship before, ServerLevel level, BlockPos corner) {
        before.blocks.forEach((rel, state) -> {
            BlockPos p = corner.offset(rel);
            BlockState now = level.getBlockState(p);
            if (now != state) {
                throw new GameTestAssertException("expected " + state + " at " + p.toShortString() + " in "
                        + level.dimension().location() + ", found " + now);
            }
        });
        CompoundTag chest = saved(level, corner.offset(CHEST));
        h.assertTrue(chest.equals(before.chest), "the chest holds " + chest + ", not " + before.chest);

        CompoundTag furnace = saved(level, corner.offset(FURNACE));
        long ticks = level.getGameTime() - before.gameTime;
        int cook = furnace.getInt("CookTime");
        int burn = furnace.getInt("BurnTime");
        int cookBefore = before.furnace.getInt("CookTime");
        int burnBefore = before.furnace.getInt("BurnTime");
        h.assertTrue(
                furnace.get("Items").equals(before.furnace.get("Items")),
                "the furnace holds " + furnace.get("Items") + ", not " + before.furnace.get("Items"));
        h.assertTrue(
                cookBefore >= COOK_TIME && cook >= cookBefore && cook <= cookBefore + ticks,
                "the furnace's progress went from " + cookBefore + " to " + cook + " in " + ticks + " ticks");
        h.assertTrue(
                burn <= burnBefore && burn >= burnBefore - ticks,
                "the furnace's fuel went from " + burnBefore + " to " + burn + " in " + ticks + " ticks");

        Entity pig = level.getEntity(before.pig);
        if (!(pig instanceof Pig p) || !p.isAlive()) {
            throw new GameTestAssertException(
                    "the pig isn't in " + level.dimension().location() + ": " + pig);
        }
        Vec3 expected = Vec3.atLowerCornerOf(corner).add(PIG);
        h.assertTrue(p.position().distanceTo(expected) < 1.0E-6, "the pig is at " + p.position() + ", not " + expected);
        h.assertTrue(p.getHealth() == PIG_HEALTH, "the pig's health is " + p.getHealth());
        h.assertTrue(
                p.getCustomName() != null && p.getCustomName().getString().equals("Passenger"),
                "the pig is called " + p.getCustomName());
    }

    /** A box the ship left is air, nothing dropped there, and the pig isn't there. */
    private static void assertLeft(
            GameTestHelper h, ServerLevel level, BoundingBox box, UUID pig, boolean wentElsewhere) {
        assertClear(h, level, box);
        assertNothingDropped(h, level, box);
        if (wentElsewhere) {
            Entity left = level.getEntity(pig);
            h.assertTrue(
                    left == null, "the pig is still in " + level.dimension().location() + ": " + left);
        }
        h.assertTrue(
                level.getEntities((Entity) null, AABB.of(box), e -> e instanceof Pig)
                        .isEmpty(),
                "a pig is still in the box it left");
    }

    private static void assertClear(GameTestHelper h, ServerLevel level, BoundingBox box) {
        for (BlockPos p :
                BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            h.assertTrue(
                    level.getBlockState(p).isAir(),
                    "expected air at " + p.toShortString() + ", found " + level.getBlockState(p));
        }
    }

    private static void assertNothingDropped(GameTestHelper h, ServerLevel level, BoundingBox box) {
        List<ItemEntity> dropped =
                level.getEntitiesOfClass(ItemEntity.class, AABB.of(box).inflate(2));
        h.assertTrue(dropped.isEmpty(), "the move dropped " + dropped);
    }

    /** The torch lights its new place and no longer its old one. */
    private static void assertLit(GameTestHelper h, ServerLevel level, BlockPos corner, BlockPos oldCorner) {
        int light = level.getBrightness(LightLayer.BLOCK, corner.offset(TORCH));
        h.assertTrue(light == 14, "block light at the moved torch is " + light);
        ServerLevel old = h.getLevel();
        int oldLight = old.getBrightness(LightLayer.BLOCK, oldCorner.offset(TORCH));
        h.assertTrue(oldLight == 0, "block light where the torch was is still " + oldLight);
    }

    /** The furnace at its new place goes on smelting. */
    private static void assertSmelting(GameTestHelper h, ServerLevel level, BlockPos corner, Ship before) {
        int cook = saved(level, corner.offset(FURNACE)).getInt("CookTime");
        h.assertTrue(
                cook > before.furnace.getInt("CookTime"),
                "the moved furnace's progress is " + cook + ", no further on");
    }

    // ---- A hand-over that writes down what it saw ---------------------------------------

    /** Records each call, with what stood at the source and the destination at the time. */
    private static final class Recorder implements RelocationHandover {
        final List<String> events = new ArrayList<>();
        final CompletableFuture<Optional<String>> begun;
        private final ServerLevel level;
        private final BlockPos source;
        private final BlockPos destination;
        private final UUID pig;

        Recorder(
                ServerLevel level,
                BlockPos source,
                BlockPos destination,
                UUID pig,
                CompletableFuture<Optional<String>> begun) {
            this.level = level;
            this.source = source;
            this.destination = destination;
            this.pig = pig;
            this.begun = begun;
        }

        private boolean shipAt(BlockPos corner) {
            return level.getBlockState(corner.offset(CHEST)).is(Blocks.CHEST)
                    && level.getBlockState(corner).is(Blocks.STONE_BRICKS)
                    && level.getBlockState(corner.offset(SIZE).offset(-1, -1, -1))
                            .is(Blocks.STONE_BRICKS);
        }

        private boolean clearAt(BlockPos corner) {
            BoundingBox box = box(corner);
            for (BlockPos p :
                    BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
                if (!level.getBlockState(p).isAir()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public Ticket begin(ServerLevel from, BoundingBox box) {
            events.add("begin: " + (shipAt(source) ? "ship at the source" : "ship gone")
                    + (clearAt(destination) ? ", destination clear" : ", destination occupied"));
            return new Ticket() {
                @Override
                public CompletableFuture<Optional<String>> begun() {
                    return begun;
                }

                @Override
                public void complete(ServerLevel to, Vec3i offset) {
                    Entity moved = to.getEntity(pig);
                    boolean pigThere = moved != null && box(destination).isInside(moved.blockPosition());
                    events.add("complete by [" + offset.getX() + ", " + offset.getY() + ", " + offset.getZ() + "]: "
                            + (shipAt(destination) ? "ship" : "no ship")
                            + (pigThere ? " and pig" : ", no pig")
                            + " at the destination, source "
                            + (clearAt(source) ? "clear" : "not clear"));
                }

                @Override
                public void cancel() {
                    events.add("cancel");
                }
            };
        }
    }
}
