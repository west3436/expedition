package io.github.west3436.expedition.gametest;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.mover.DryRun;
import io.github.west3436.expedition.mover.ExpeditionTags;
import io.github.west3436.expedition.mover.MoveJob;
import io.github.west3436.expedition.mover.MoveRefusal;
import io.github.west3436.expedition.mover.MoveRequest;
import io.github.west3436.expedition.mover.MoveResult;
import io.github.west3436.expedition.mover.MoveSettings;
import io.github.west3436.expedition.mover.Obstacle;
import io.github.west3436.expedition.mover.QuietBlocks;
import io.github.west3436.expedition.mover.RelocationHandover;
import io.github.west3436.expedition.mover.ShipMover;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Refusals and the dry run (#6): a move that can't be honoured changes nothing at either end and
 * says why, and a dry run lists every obstacle without moving anything.
 *
 * <p>The ship is a floor of stone bricks with a chest of diamonds and a pig on it, in a box 3 blocks
 * a side. Reinforced deepslate stands in for an immovable block: the GameTests' own datapack ({@link
 * TestPacks}) puts it in {@code expedition:immovable}, which the mod ships empty.
 *
 * <p>Each test builds at its own chunk-aligned area, far from the GameTest grid and from {@link
 * MoverTests}' areas, and clears both ends when it is done.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RefusalTests {
    private static final String BATCH = "refusals";

    /** As in {@link MoverTests}: a far chunk's entities load in real time, which can be slow. */
    private static final int TIMEOUT = 20_000;

    private static final TicketType<ChunkPos> HELD =
            TicketType.create("expedition_gametest_refusals", Comparator.comparingLong(ChunkPos::toLong), TIMEOUT);

    /** The ship's box: 3 blocks a side. */
    private static final Vec3i SIZE = new Vec3i(3, 3, 3);

    /** How far the ship goes: two chunks east, clear of its own box. */
    private static final Vec3i EAST = new Vec3i(32, 0, 0);

    private static final Vec3i CHEST = new Vec3i(1, 1, 1);
    private static final Vec3 PIG = new Vec3(0.5, 1, 2.5);

    private RefusalTests() {}

    // ---- The tests ----------------------------------------------------------------------

    /** A stone block at the destination refuses the move as blocked, and neither end changes. */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void a_blocked_destination_is_refused_and_nothing_changes(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos a = area(0);
        BlockPos b = a.offset(EAST);
        h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, level, box(a));
                    hold(h, level, box(b));
                })
                .thenExecute(() -> {
                    build(level, a);
                    wipe(level, box(b));
                    level.setBlock(b.offset(1, 1, 1), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    Snapshot before = Snapshot.of(level, a, b);

                    MoveJob job = ShipMover.start(MoveRequest.by(level, box(a), level, EAST));
                    assertRefused(h, job, MoveRefusal.BLOCKED);
                    before.assertSame(h, level);
                    h.assertTrue(
                            level.getBlockState(b.offset(1, 1, 1)).is(Blocks.STONE), "the stone in the way is gone");
                    assertNothingDropped(h, level, box(a), box(b));
                    wipe(level, box(a));
                    wipe(level, box(b));
                })
                .thenSucceed();
    }

    /** A box holding an {@code expedition:immovable} block is refused, and neither end changes. */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void a_box_holding_an_immovable_block_is_refused(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos a = area(1);
        BlockPos b = a.offset(EAST);
        h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, level, box(a));
                    hold(h, level, box(b));
                })
                .thenExecute(() -> {
                    build(level, a);
                    wipe(level, box(b));
                    BlockPos deepslate = a.offset(2, 1, 2);
                    level.setBlock(deepslate, Blocks.REINFORCED_DEEPSLATE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    h.assertTrue(
                            level.getBlockState(deepslate).is(ExpeditionTags.IMMOVABLE),
                            "the GameTests' datapack didn't put reinforced deepslate in expedition:immovable");
                    Snapshot before = Snapshot.of(level, a, b);

                    MoveJob job = ShipMover.start(MoveRequest.by(level, box(a), level, EAST));
                    assertRefused(h, job, MoveRefusal.IMMOVABLE);
                    before.assertSame(h, level);
                    assertNothingDropped(h, level, box(a), box(b));
                    wipe(level, box(a));
                    wipe(level, box(b));
                })
                .thenSucceed();
    }

    /**
     * A dry run lists every obstacle, each with where it is: both immovable blocks in the box, both
     * blocks in the way at the destination and the armour stand standing there. It changes nothing
     * and starts no move. Once they are cleared, a dry run finds nothing.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void a_dry_run_reports_every_obstacle_and_changes_nothing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos a = area(2);
        BlockPos b = a.offset(EAST);
        h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, level, box(a));
                    hold(h, level, box(b));
                })
                .thenExecute(() -> {
                    build(level, a);
                    wipe(level, box(b));
                    Set<BlockPos> immovable = Set.of(a.offset(2, 1, 2), a.offset(0, 2, 0));
                    for (BlockPos p : immovable) {
                        level.setBlock(p, Blocks.REINFORCED_DEEPSLATE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                    Set<BlockPos> blocking = Set.of(b.offset(1, 1, 1), b.offset(2, 0, 2));
                    for (BlockPos p : blocking) {
                        level.setBlock(p, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                    ArmorStand stand = EntityType.ARMOR_STAND.create(level);
                    stand.moveTo(b.getX() + 0.5, b.getY() + 1, b.getZ() + 2.5, 0, 0);
                    stand.setNoGravity(true);
                    level.addFreshEntity(stand);
                    Snapshot before = Snapshot.of(level, a, b);
                    MoveRequest request = MoveRequest.by(level, box(a), level, EAST);

                    DryRun dry = ShipMover.dryRun(request);
                    h.assertFalse(dry.wouldMove(), "the dry run says the move would go ahead");
                    h.assertTrue(
                            dry.refusal().equals(Optional.of(MoveRefusal.IMMOVABLE)),
                            "the dry run's refusal is " + dry.refusal());
                    h.assertTrue(
                            List.copyOf(dry.codes()).equals(List.of(MoveRefusal.IMMOVABLE, MoveRefusal.BLOCKED)),
                            "the dry run's codes are " + dry.codes());
                    h.assertTrue(dry.obstacles().size() == 5, "the dry run found " + dry.obstacles());
                    h.assertTrue(
                            blocksOf(dry, MoveRefusal.IMMOVABLE, level).equals(immovable),
                            "the immovable blocks found are " + dry.obstacles(MoveRefusal.IMMOVABLE));
                    h.assertTrue(
                            blocksOf(dry, MoveRefusal.BLOCKED, level).equals(blocking),
                            "the blocks in the way found are " + dry.obstacles(MoveRefusal.BLOCKED));
                    List<Obstacle.OfEntity> entities = dry.obstacles(MoveRefusal.BLOCKED).stream()
                            .filter(o -> o instanceof Obstacle.OfEntity)
                            .map(o -> (Obstacle.OfEntity) o)
                            .toList();
                    h.assertTrue(
                            entities.size() == 1
                                    && entities.get(0).uuid().equals(stand.getUUID())
                                    && entities.get(0).type() == EntityType.ARMOR_STAND
                                    && entities.get(0).level() == level.dimension(),
                            "the entities in the way found are " + entities);

                    before.assertSame(h, level);
                    h.assertFalse(ShipMover.isMoving(level, a.offset(CHEST)), "the dry run started a move");
                    h.assertTrue(
                            ShipMover.inProgress().stream()
                                    .noneMatch(j -> j.request().equals(request)),
                            "the dry run left a move in progress");
                    assertNothingDropped(h, level, box(a), box(b));

                    // The move the dry run speaks of is refused for the first reason it gives.
                    assertRefused(h, ShipMover.start(request), MoveRefusal.IMMOVABLE);
                    before.assertSame(h, level);

                    for (BlockPos p : immovable) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                    for (BlockPos p : blocking) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                    stand.discard();
                    DryRun clear = ShipMover.dryRun(request);
                    h.assertTrue(clear.wouldMove(), "with the way clear, the dry run found " + clear.obstacles());
                    wipe(level, box(a));
                    wipe(level, box(b));
                })
                .thenSucceed();
    }

    /**
     * Grass and snow layers at the destination aren't in the way: the ship is written over them,
     * and nothing drops.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void a_replaceable_destination_is_written_over(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos a = area(3);
        BlockPos b = a.offset(EAST);
        MoveJob[] job = {null};
        h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, level, box(a));
                    hold(h, level, box(b));
                })
                .thenExecute(() -> {
                    build(level, a);
                    wipe(level, box(b));
                    // Under the chest, where the floor lands, and where air lands.
                    level.setBlock(b.offset(1, 1, 1), Blocks.SHORT_GRASS.defaultBlockState(), Block.UPDATE_CLIENTS);
                    level.setBlock(b.offset(2, 0, 2), Blocks.SNOW.defaultBlockState(), Block.UPDATE_CLIENTS);
                    level.setBlock(b.offset(0, 2, 0), Blocks.SHORT_GRASS.defaultBlockState(), Block.UPDATE_CLIENTS);
                    MoveRequest request = MoveRequest.by(level, box(a), level, EAST);
                    DryRun dry = ShipMover.dryRun(request);
                    h.assertTrue(dry.wouldMove(), "grass and snow are in the way: " + dry.obstacles());
                    job[0] = ShipMover.start(request);
                })
                .thenWaitUntil(() -> h.assertTrue(job[0].isDone(), "the move is still going"))
                .thenExecute(() -> {
                    MoveResult result = job[0].result().join();
                    h.assertTrue(result.moved(), "the move was refused: " + result.refusal());
                    h.assertTrue(
                            level.getBlockState(b.offset(1, 1, 1)).is(Blocks.CHEST),
                            "no chest where the grass was: " + level.getBlockState(b.offset(1, 1, 1)));
                    h.assertTrue(
                            level.getBlockState(b.offset(2, 0, 2)).is(Blocks.STONE_BRICKS),
                            "no floor where the snow was: " + level.getBlockState(b.offset(2, 0, 2)));
                    h.assertTrue(
                            level.getBlockState(b.offset(0, 2, 0)).isAir(),
                            "the grass is still there: " + level.getBlockState(b.offset(0, 2, 0)));
                    ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(b.offset(1, 1, 1));
                    h.assertTrue(chest != null && chest.getItem(0).is(Items.DIAMOND), "the chest lost its diamonds");
                    h.assertTrue(level.getBlockState(a.offset(1, 1, 1)).isAir(), "the chest is still at the source");
                    assertNothingDropped(h, level, box(a), box(b));
                    wipe(level, box(a));
                    wipe(level, box(b));
                })
                .thenSucceed();
    }

    /**
     * A destination in chunks that aren't loaded is refused as unloaded; a dry run names each chunk,
     * and loads none of them.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void an_unloaded_destination_is_refused(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos a = area(4);
        Vec3i far = new Vec3i(48_000, 0, 0);
        BlockPos b = a.offset(far);
        h.startSequence()
                .thenWaitUntil(() -> hold(h, level, box(a)))
                .thenExecute(() -> {
                    build(level, a);
                    Set<ChunkPos> expected = new HashSet<>();
                    for (int x = (b.getX() - 1) >> 4; x <= (b.getX() + SIZE.getX()) >> 4; x++) {
                        for (int z = (b.getZ() - 1) >> 4; z <= (b.getZ() + SIZE.getZ()) >> 4; z++) {
                            expected.add(new ChunkPos(x, z));
                        }
                    }
                    for (ChunkPos c : expected) {
                        h.assertFalse(level.hasChunk(c.x, c.z), "chunk " + c + " is loaded already");
                    }
                    Map<BlockPos, BlockState> source = blocks(level, box(a));
                    MoveRequest request = MoveRequest.by(level, box(a), level, far);

                    assertRefused(h, ShipMover.start(request), MoveRefusal.UNLOADED);
                    DryRun dry = ShipMover.dryRun(request);
                    Set<ChunkPos> found = new HashSet<>();
                    for (Obstacle o : dry.obstacles()) {
                        if (o instanceof Obstacle.InChunk c
                                && c.code().equals(MoveRefusal.UNLOADED)
                                && c.level() == level.dimension()) {
                            found.add(c.chunk());
                        } else {
                            h.fail("the dry run found " + o);
                        }
                    }
                    h.assertTrue(found.equals(expected), "the dry run found " + found + ", not " + expected);
                    for (ChunkPos c : expected) {
                        h.assertFalse(level.hasChunk(c.x, c.z), "the dry run loaded chunk " + c);
                    }
                    h.assertTrue(blocks(level, box(a)).equals(source), "the source changed");
                    wipe(level, box(a));
                })
                .thenSucceed();
    }

    /**
     * While a move waits for its hand-over, another that overlaps it is refused as busy. A block put
     * in its way meanwhile refuses it when the hand-over is ready, which cancels the hand-over, and
     * nothing changes.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void an_overlapping_move_is_busy_and_a_late_obstacle_refuses(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos a = area(5);
        BlockPos b = a.offset(EAST);
        BlockPos c = b.offset(EAST);
        Waiting handover = new Waiting();
        Snapshot[] before = {null};
        MoveJob[] job = {null};
        h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, level, box(a));
                    hold(h, level, box(b));
                    hold(h, level, box(c));
                })
                .thenExecute(() -> {
                    build(level, a);
                    wipe(level, box(b));
                    wipe(level, box(c));
                    job[0] = ShipMover.start(
                            MoveRequest.by(level, box(a), level, EAST), MoveSettings.DEFAULT.withHandover(handover));
                    h.assertFalse(job[0].isDone(), "the move didn't wait for its hand-over");

                    MoveRequest overlapping = MoveRequest.by(level, box(b), level, EAST);
                    assertRefused(h, ShipMover.start(overlapping), MoveRefusal.BUSY);
                    DryRun dry = ShipMover.dryRun(overlapping);
                    h.assertTrue(
                            dry.obstacles().equals(List.of(new Obstacle.OfMove(MoveRefusal.BUSY))),
                            "the dry run of the overlapping move found " + dry.obstacles());

                    level.setBlock(b.offset(1, 2, 1), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    before[0] = Snapshot.of(level, a, b);
                    handover.begun.complete(Optional.empty());
                })
                .thenWaitUntil(() -> h.assertTrue(job[0].isDone(), "the move is still waiting"))
                .thenExecute(() -> {
                    MoveResult result = job[0].result().join();
                    h.assertTrue(
                            result.refusal().equals(Optional.of(MoveRefusal.BLOCKED)),
                            "the late refusal was " + result.refusal());
                    h.assertTrue(
                            handover.events.equals(List.of("begin", "cancel")), "the hand-over saw " + handover.events);
                    before[0].assertSame(h, level);
                    assertNothingDropped(h, level, box(a), box(b));
                    wipe(level, box(a));
                    wipe(level, box(b));
                    wipe(level, box(c));
                })
                .thenSucceed();
    }

    // ---- The ship -----------------------------------------------------------------------

    /** A test's area: chunk-aligned, far from the GameTest grid and from {@link MoverTests}' areas. */
    private static BlockPos area(int index) {
        return new BlockPos(-20_480 + 512 * index, 100, -25_600);
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

    /** A floor of stone bricks, a chest of diamonds on it and a pig that stays still. */
    private static void build(ServerLevel level, BlockPos corner) {
        BoundingBox box = box(corner);
        wipe(level, box);
        for (int x = 0; x < SIZE.getX(); x++) {
            for (int z = 0; z < SIZE.getZ(); z++) {
                level.setBlock(corner.offset(x, 0, z), Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        BlockPos chestAt = corner.offset(CHEST);
        level.setBlock(chestAt, Blocks.CHEST.defaultBlockState(), Block.UPDATE_CLIENTS);
        ((ChestBlockEntity) level.getBlockEntity(chestAt)).setItem(0, new ItemStack(Items.DIAMOND, 7));
        Pig pig = EntityType.PIG.create(level);
        pig.moveTo(corner.getX() + PIG.x, corner.getY() + PIG.y, corner.getZ() + PIG.z, 0, 0);
        pig.setNoAi(true);
        pig.setCustomName(Component.literal("Passenger"));
        level.addFreshEntity(pig);
    }

    /** Takes every block and entity out of a box, quietly, so nothing drops. */
    private static void wipe(ServerLevel level, BoundingBox box) {
        for (Entity e : level.getEntities((Entity) null, AABB.of(box).inflate(2), e -> !(e instanceof Player))) {
            e.discard();
        }
        for (BlockPos p : positions(box)) {
            LevelChunk chunk = level.getChunkAt(p);
            QuietBlocks.takeBlockEntity(level, chunk, p);
            QuietBlocks.set(level, chunk, p, Blocks.AIR.defaultBlockState());
        }
    }

    private static Iterable<BlockPos> positions(BoundingBox box) {
        return BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
    }

    private static Map<BlockPos, BlockState> blocks(ServerLevel level, BoundingBox box) {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (BlockPos p : positions(box)) {
            blocks.put(p.immutable(), level.getBlockState(p));
        }
        return blocks;
    }

    /** What both ends of a move held at one moment: blocks, block entities' saved state, entities. */
    private record Snapshot(
            List<BoundingBox> boxes,
            Map<BlockPos, BlockState> blocks,
            Map<BlockPos, CompoundTag> blockEntities,
            Map<UUID, Vec3> entities) {
        static Snapshot of(ServerLevel level, BlockPos... corners) {
            List<BoundingBox> boxes = new ArrayList<>();
            Map<BlockPos, BlockState> blocks = new HashMap<>();
            Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();
            Map<UUID, Vec3> entities = new HashMap<>();
            for (BlockPos corner : corners) {
                BoundingBox box = box(corner);
                boxes.add(box);
                read(level, box, blocks, blockEntities, entities);
            }
            return new Snapshot(boxes, blocks, blockEntities, entities);
        }

        private static void read(
                ServerLevel level,
                BoundingBox box,
                Map<BlockPos, BlockState> blocks,
                Map<BlockPos, CompoundTag> blockEntities,
                Map<UUID, Vec3> entities) {
            blocks.putAll(RefusalTests.blocks(level, box));
            for (BlockPos p : positions(box)) {
                BlockEntity be = level.getBlockEntity(p);
                if (be != null) {
                    blockEntities.put(p.immutable(), be.saveWithoutMetadata(level.registryAccess()));
                }
            }
            for (Entity e : level.getEntities((Entity) null, AABB.of(box).inflate(1), e -> true)) {
                entities.put(e.getUUID(), e.position());
            }
        }

        /** Throws unless both ends hold just what they did. */
        void assertSame(GameTestHelper h, ServerLevel level) {
            Map<BlockPos, BlockState> blocksNow = new HashMap<>();
            Map<BlockPos, CompoundTag> blockEntitiesNow = new HashMap<>();
            Map<UUID, Vec3> entitiesNow = new HashMap<>();
            for (BoundingBox box : boxes) {
                read(level, box, blocksNow, blockEntitiesNow, entitiesNow);
            }
            blocks.forEach((p, state) -> h.assertTrue(
                    blocksNow.get(p) == state,
                    "expected " + state + " at " + p.toShortString() + ", found " + blocksNow.get(p)));
            h.assertTrue(
                    blockEntitiesNow.equals(blockEntities),
                    "the block entities were " + blockEntities + " and are " + blockEntitiesNow);
            h.assertTrue(entitiesNow.equals(entities), "the entities were " + entities + " and are " + entitiesNow);
        }
    }

    // ---- Assertions ---------------------------------------------------------------------

    private static void assertRefused(GameTestHelper h, MoveJob job, String code) {
        h.assertTrue(job.isDone(), "a refused move is still going");
        MoveResult result = job.result().join();
        h.assertTrue(result.refusal().equals(Optional.of(code)), "expected " + code + ", the move gave " + result);
        h.assertTrue(result.blocks() == 0 && result.entities() == 0, "the refused move says it carried " + result);
    }

    private static Set<BlockPos> blocksOf(DryRun dry, String code, Level level) {
        Set<BlockPos> found = new HashSet<>();
        for (Obstacle o : dry.obstacles(code)) {
            if (o instanceof Obstacle.AtBlock block && block.level() == level.dimension()) {
                found.add(block.pos());
            }
        }
        return found;
    }

    private static void assertNothingDropped(GameTestHelper h, ServerLevel level, BoundingBox... boxes) {
        for (BoundingBox box : boxes) {
            List<ItemEntity> dropped =
                    level.getEntitiesOfClass(ItemEntity.class, AABB.of(box).inflate(2));
            h.assertTrue(dropped.isEmpty(), "something dropped: " + dropped);
        }
    }

    // ---- A hand-over that waits until told ----------------------------------------------

    /** Begins when its future is completed, and writes down each call. */
    private static final class Waiting implements RelocationHandover {
        final List<String> events = new ArrayList<>();
        final CompletableFuture<Optional<String>> begun = new CompletableFuture<>();

        @Override
        public Ticket begin(ServerLevel from, BoundingBox box) {
            events.add("begin");
            return new Ticket() {
                @Override
                public CompletableFuture<Optional<String>> begun() {
                    return begun;
                }

                @Override
                public void complete(ServerLevel to, Vec3i offset) {
                    events.add("complete");
                }

                @Override
                public void cancel() {
                    events.add("cancel");
                }
            };
        }
    }
}
