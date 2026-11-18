package io.github.west3436.expedition.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.west3436.expedition.ExpeditionConfig;
import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.command.MoveCommand;
import io.github.west3436.expedition.mover.MoveRefusal;
import io.github.west3436.expedition.mover.Obstacle;
import io.github.west3436.expedition.mover.QuietBlocks;
import io.github.west3436.expedition.mover.ShipMover;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * {@code /expedition move} (#7), run through the server's own command dispatcher as an operator
 * would type it, with a command source that writes down what it is told. The messages are checked
 * by their translation keys, which a dedicated server doesn't translate.
 *
 * <p>The ship is a floor of stone bricks with a chest of diamonds on it, in a box 3 blocks a side.
 * Each test builds at its own chunk-aligned area, far from the GameTest grid and from the other
 * tests' areas, and clears both ends when it is done.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CommandTests {
    private static final String BATCH = "commands";

    /** As in {@link MoverTests}: a far chunk's entities load in real time, which can be slow. */
    private static final int TIMEOUT = 20_000;

    private static final TicketType<ChunkPos> HELD =
            TicketType.create("expedition_gametest_commands", Comparator.comparingLong(ChunkPos::toLong), TIMEOUT);

    private static final Vec3i SIZE = new Vec3i(3, 3, 3);

    /** How far a ship goes within a level: two chunks east, clear of its own box. */
    private static final Vec3i EAST = new Vec3i(32, 0, 0);

    private static final Vec3i CHEST = new Vec3i(1, 1, 1);

    /** What the ship is: 9 floor blocks and the chest, all solid, and the chest's block entity. */
    private static final int BLOCKS = 10;

    /** What the ship weighs (#54), as the messages give it: the floor's 9 at 1 each, the chest 0.5. */
    private static final String MASS = "9.5";

    private static final String KEY = "expedition.command.move.";

    private CommandTests() {}

    // ---- The tests ----------------------------------------------------------------------

    /** An operator moves the ship from the Overworld into the Nether, and is told what went. */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void an_operator_moves_a_box_into_the_nether(GameTestHelper h) {
        ServerLevel overworld = h.getLevel();
        ServerLevel nether = level(h, Level.NETHER);
        BlockPos a = area(0);
        // The Nether is netherrack at y 64, which the test clears: a move never writes over it.
        BlockPos c = new BlockPos(a.getX(), 64, a.getZ());
        Chat chat = new Chat();
        h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, overworld, box(a));
                    hold(h, nether, box(c));
                })
                .thenExecute(() -> {
                    build(overworld, a);
                    wipe(nether, box(c));
                    int ran =
                            run(h, chat, overworld, MoveCommand.PERMISSION_LEVEL, a, "minecraft:the_nether", c, false);
                    h.assertTrue(ran == 1, "the command returned " + ran);
                })
                .thenWaitUntil(() -> h.assertTrue(
                        chat.has(KEY + "moved") || chat.has(KEY + "refused") || chat.has(KEY + "failed"),
                        "no result yet: " + chat))
                .thenExecute(() -> {
                    h.assertFalse(chat.has(KEY + "refused"), "the move was refused: " + chat);
                    TranslatableContents moved = chat.first(KEY + "moved");
                    // The blocks, block entities, entities and mass, in the message's order.
                    List<Object> counts = List.of(moved.getArgs()).subList(2, 6);
                    h.assertTrue(counts.equals(List.of(BLOCKS, 1, 0, MASS)), "the counts are " + counts);
                    h.assertTrue(((Integer) moved.getArgs()[6]) >= 1, "the ticks are " + moved.getArgs()[6]);
                    assertShip(h, nether, c);
                    for (BlockPos p : positions(box(a))) {
                        h.assertTrue(overworld.getBlockState(p).isAir(), "the source isn't empty at " + p);
                    }
                    wipe(overworld, box(a));
                    wipe(nether, box(c));
                })
                .thenSucceed();
    }

    /**
     * A stone block at the destination refuses the move: the operator is told it was blocked, and
     * where, and neither end changes.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void a_refused_move_says_why(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos a = area(1);
        BlockPos b = a.offset(EAST);
        BlockPos stone = b.offset(2, 2, 2);
        Chat chat = new Chat();
        h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, level, box(a));
                    hold(h, level, box(b));
                })
                .thenExecute(() -> {
                    build(level, a);
                    wipe(level, box(b));
                    level.setBlock(stone, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    Map<BlockPos, BlockState> before = blocks(level, a, b);

                    int ran = run(h, chat, level, MoveCommand.PERMISSION_LEVEL, a, "minecraft:overworld", b, false);
                    h.assertTrue(ran == 0, "the refused command returned " + ran);
                    h.assertTrue(chat.has(KEY + "refused"), "no refusal: " + chat);
                    h.assertTrue(
                            chat.has(MoveRefusal.translationKey(MoveRefusal.BLOCKED)),
                            "the refusal isn't blocked: " + chat);
                    h.assertTrue(chat.has(KEY + "reason"), "no reason: " + chat);
                    TranslatableContents obstacle = chat.first(KEY + "obstacle.block");
                    h.assertTrue(
                            obstacle != null && obstacle.getArgs()[1] instanceof Component at && names(at, stone),
                            "the stone isn't named: " + chat);
                    h.assertFalse(chat.has(KEY + "moved"), "the refused move says it moved: " + chat);
                    h.assertTrue(blocks(level, a, b).equals(before), "the refused move changed something");
                    assertShip(h, level, a);
                    wipe(level, box(a));
                    wipe(level, box(b));
                })
                .thenSucceed();
    }

    /**
     * A dry run moves nothing. With obstacles, it names every reason and lists the first {@link
     * MoveCommand#OBSTACLES_SHOWN} obstacles of each, and how many more; with the way clear, it says
     * what the move would carry.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void a_dry_run_changes_nothing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos a = area(2);
        BlockPos b = a.offset(EAST);
        BlockPos deepslate = a.offset(2, 2, 2);
        List<BlockPos> stones = new ArrayList<>();
        for (BlockPos p : positions(box(b))) {
            if (stones.size() < MoveCommand.OBSTACLES_SHOWN + 2) {
                stones.add(p.immutable());
            }
        }
        h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, level, box(a));
                    hold(h, level, box(b));
                })
                .thenExecute(() -> {
                    build(level, a);
                    wipe(level, box(b));
                    // The GameTests' datapack (TestPacks) puts reinforced deepslate in expedition:immovable.
                    level.setBlock(deepslate, Blocks.REINFORCED_DEEPSLATE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    for (BlockPos p : stones) {
                        level.setBlock(p, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                    Map<BlockPos, BlockState> before = blocks(level, a, b);

                    Chat chat = new Chat();
                    int ran = run(h, chat, level, MoveCommand.PERMISSION_LEVEL, a, "minecraft:overworld", b, true);
                    h.assertTrue(ran == 0, "the blocked dry run returned " + ran);
                    h.assertTrue(chat.has(KEY + "dry_run.refused"), "no dry run result: " + chat);
                    h.assertTrue(chat.count(KEY + "reason") == 2, "not two reasons: " + chat);
                    h.assertTrue(
                            chat.has(MoveRefusal.translationKey(MoveRefusal.IMMOVABLE))
                                    && chat.has(MoveRefusal.translationKey(MoveRefusal.BLOCKED)),
                            "a reason is missing: " + chat);
                    int listed = chat.count(KEY + "obstacle.block");
                    h.assertTrue(listed == 1 + MoveCommand.OBSTACLES_SHOWN, listed + " blocks listed: " + chat);
                    TranslatableContents more = chat.first(KEY + "more");
                    h.assertTrue(more != null && Integer.valueOf(2).equals(more.getArgs()[0]), "not 2 more: " + chat);
                    h.assertFalse(chat.has(KEY + "moved") || chat.has(KEY + "started"), "the dry run moved: " + chat);
                    h.assertTrue(blocks(level, a, b).equals(before), "the dry run changed something");
                    h.assertFalse(ShipMover.isMoving(level, a.offset(CHEST)), "the dry run started a move");

                    level.setBlock(deepslate, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    for (BlockPos p : stones) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                    before = blocks(level, a, b);
                    Chat clear = new Chat();
                    ran = run(h, clear, level, MoveCommand.PERMISSION_LEVEL, a, "minecraft:overworld", b, true);
                    h.assertTrue(ran == 1, "the clear dry run returned " + ran);
                    TranslatableContents would = clear.first(KEY + "dry_run.clear");
                    h.assertTrue(
                            would != null && List.of(would.getArgs()).equals(List.of(BLOCKS, 1, MASS)),
                            "the clear dry run said " + clear);
                    h.assertTrue(blocks(level, a, b).equals(before), "the clear dry run changed something");
                    h.assertFalse(ShipMover.isMoving(level, a.offset(CHEST)), "the clear dry run started a move");
                    assertShip(h, level, a);
                    wipe(level, box(a));
                    wipe(level, box(b));
                })
                .thenSucceed();
    }

    /** A source below the command's permission level can't run it, and nothing moves. */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void a_non_operator_is_denied(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos a = area(3);
        BlockPos b = a.offset(EAST);
        h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, level, box(a));
                    hold(h, level, box(b));
                })
                .thenExecute(() -> {
                    build(level, a);
                    wipe(level, box(b));
                    Map<BlockPos, BlockState> before = blocks(level, a, b);
                    for (int permission = 0; permission < MoveCommand.PERMISSION_LEVEL; permission++) {
                        Chat chat = new Chat();
                        try {
                            run(h, chat, level, permission, a, "minecraft:overworld", b, false);
                            h.fail("a source of permission level " + permission + " ran the command");
                        } catch (GameTestAssertException e) {
                            throw e;
                        } catch (RuntimeException e) {
                            if (!(e.getCause() instanceof CommandSyntaxException)) {
                                throw e;
                            }
                        }
                        h.assertTrue(chat.messages.isEmpty(), "the denied command said " + chat);
                    }
                    h.assertTrue(blocks(level, a, b).equals(before), "the denied command changed something");
                    h.assertFalse(ShipMover.isMoving(level, a.offset(CHEST)), "the denied command started a move");
                    assertShip(h, level, a);
                    wipe(level, box(a));
                    wipe(level, box(b));
                })
                .thenSucceed();
    }

    /**
     * A ship over the server's ceiling (#71): a dry run and a move both name each limit it broke,
     * with the ship's size, the limit and how far over, for the volume, the longest side and a short
     * hop. In a batch of its own, since it changes the server's settings.
     */
    @GameTest(template = "empty", batch = "commands_ceiling", timeoutTicks = TIMEOUT)
    public static void a_ship_too_large_is_told_the_limits(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos a = area(4);
        BlockPos b = a.offset(EAST);
        BlockPos hop = a.offset(1, 0, 0);
        h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, level, box(a));
                    hold(h, level, box(b));
                })
                .thenExecute(() -> {
                    build(level, a);
                    wipe(level, box(b));
                    Map<BlockPos, BlockState> before = blocks(level, a, b);
                    int volume = ExpeditionConfig.MAX_VOLUME.getAsInt();
                    int side = ExpeditionConfig.MAX_LONGEST_SIDE.getAsInt();
                    int hopVolume = ExpeditionConfig.MAX_HOP_VOLUME.getAsInt();
                    try {
                        // The ship is 3 × 3 × 3: 27 blocks, 3 along its longest side.
                        ExpeditionConfig.MAX_VOLUME.set(26);
                        ExpeditionConfig.MAX_LONGEST_SIDE.set(2);
                        ExpeditionConfig.MAX_HOP_VOLUME.set(4_096);
                        for (boolean dryRun : new boolean[] {true, false}) {
                            Chat chat = new Chat();
                            int ran = run(
                                    h, chat, level, MoveCommand.PERMISSION_LEVEL, a, "minecraft:overworld", b, dryRun);
                            h.assertTrue(ran == 0, "the command over the ceiling returned " + ran);
                            h.assertTrue(
                                    chat.has(MoveRefusal.translationKey(MoveRefusal.TOO_LARGE)),
                                    "the refusal isn't too_large: " + chat);
                            assertLimit(h, chat, Obstacle.OverLimit.VOLUME, "27", "26", "1");
                            assertLimit(h, chat, Obstacle.OverLimit.LONGEST_SIDE, "3", "2", "1");
                            h.assertTrue(chat.count(KEY + "obstacle.limit") == 2, "not two limits: " + chat);
                        }

                        ExpeditionConfig.MAX_VOLUME.set(131_072);
                        ExpeditionConfig.MAX_LONGEST_SIDE.set(64);
                        ExpeditionConfig.MAX_HOP_VOLUME.set(26);
                        for (boolean dryRun : new boolean[] {true, false}) {
                            Chat chat = new Chat();
                            int ran = run(
                                    h,
                                    chat,
                                    level,
                                    MoveCommand.PERMISSION_LEVEL,
                                    a,
                                    "minecraft:overworld",
                                    hop,
                                    dryRun);
                            h.assertTrue(ran == 0, "the hop over its limit returned " + ran);
                            h.assertTrue(
                                    chat.has(MoveRefusal.translationKey(MoveRefusal.HOP_TOO_LARGE)),
                                    "the refusal isn't hop_too_large: " + chat);
                            assertLimit(h, chat, Obstacle.OverLimit.HOP_VOLUME, "27", "26", "1");
                        }
                    } finally {
                        ExpeditionConfig.MAX_VOLUME.set(volume);
                        ExpeditionConfig.MAX_LONGEST_SIDE.set(side);
                        ExpeditionConfig.MAX_HOP_VOLUME.set(hopVolume);
                    }
                    h.assertTrue(blocks(level, a, b).equals(before), "a refused command changed something");
                    assertShip(h, level, a);
                    wipe(level, box(a));
                    wipe(level, box(b));
                })
                .thenSucceed();
    }

    /** The chat names a limit, with the ship's size, the limit and how far over, as written. */
    private static void assertLimit(GameTestHelper h, Chat chat, String limit, String size, String max, String over) {
        TranslatableContents said = chat.first(Obstacle.OverLimit.KEY_PREFIX + limit);
        h.assertTrue(
                said != null && List.of(said.getArgs()).equals(List.of(size, max, over)),
                "the " + limit + " limit isn't told as " + size + " over " + max + ": " + chat);
    }

    // ---- Running the command ------------------------------------------------------------

    /**
     * Runs {@code /expedition move} through the server's dispatcher, from a source in a level with a
     * permission level, for the box at {@code a}.
     *
     * @throws RuntimeException wrapping the {@link CommandSyntaxException} if the dispatcher refuses
     *     the command
     */
    private static int run(
            GameTestHelper h,
            Chat chat,
            ServerLevel level,
            int permission,
            BlockPos a,
            String dimension,
            BlockPos destination,
            boolean dryRun) {
        BlockPos far = a.offset(SIZE.getX() - 1, SIZE.getY() - 1, SIZE.getZ() - 1);
        String command = String.format(
                Locale.ROOT,
                "expedition move %d %d %d %d %d %d %s %d %d %d%s",
                // The corners the other way round: the box is the same whichever comes first.
                far.getX(),
                far.getY(),
                far.getZ(),
                a.getX(),
                a.getY(),
                a.getZ(),
                dimension,
                destination.getX(),
                destination.getY(),
                destination.getZ(),
                dryRun ? " dry-run" : "");
        CommandSourceStack source = level.getServer()
                .createCommandSourceStack()
                .withSource(chat)
                .withLevel(level)
                .withPosition(Vec3.atCenterOf(a))
                .withPermission(permission);
        h.assertTrue(source.hasPermission(permission), "the source has the wrong permission level");
        try {
            return level.getServer().getCommands().getDispatcher().execute(command, source);
        } catch (CommandSyntaxException e) {
            throw new RuntimeException(command + ": " + e.getMessage(), e);
        }
    }

    /** Whether a message shows a position, as vanilla's coordinates. */
    private static boolean names(Component message, BlockPos pos) {
        Chat chat = new Chat();
        chat.sendSystemMessage(message);
        TranslatableContents coordinates = chat.first("chat.coordinates");
        return coordinates != null
                && List.of(coordinates.getArgs()).equals(List.of(pos.getX(), pos.getY(), pos.getZ()));
    }

    /** A command source that writes down every message, and tells no one else. */
    private static final class Chat implements CommandSource {
        final List<Component> messages = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            messages.add(message);
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        /** Every translation in every message, arguments and siblings included, in order. */
        List<TranslatableContents> translations() {
            List<TranslatableContents> found = new ArrayList<>();
            for (Component message : messages) {
                collect(message, found);
            }
            return found;
        }

        private static void collect(Component component, List<TranslatableContents> found) {
            if (component.getContents() instanceof TranslatableContents t) {
                found.add(t);
                for (Object arg : t.getArgs()) {
                    if (arg instanceof Component c) {
                        collect(c, found);
                    }
                }
            }
            for (Component sibling : component.getSiblings()) {
                collect(sibling, found);
            }
        }

        boolean has(String key) {
            return first(key) != null;
        }

        TranslatableContents first(String key) {
            return translations().stream()
                    .filter(t -> t.getKey().equals(key))
                    .findFirst()
                    .orElse(null);
        }

        int count(String key) {
            return (int)
                    translations().stream().filter(t -> t.getKey().equals(key)).count();
        }

        @Override
        public String toString() {
            return messages.stream().map(Component::getString).toList().toString();
        }
    }

    // ---- The world ----------------------------------------------------------------------

    /** A test's area: chunk-aligned, far from the GameTest grid and from every other test's. */
    private static BlockPos area(int index) {
        return new BlockPos(-20_480 + 512 * index, 100, -30_720);
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

    /** A floor of stone bricks and a chest of diamonds on it. */
    private static void build(ServerLevel level, BlockPos corner) {
        wipe(level, box(corner));
        for (int x = 0; x < SIZE.getX(); x++) {
            for (int z = 0; z < SIZE.getZ(); z++) {
                level.setBlock(corner.offset(x, 0, z), Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        BlockPos chestAt = corner.offset(CHEST);
        level.setBlock(chestAt, Blocks.CHEST.defaultBlockState(), Block.UPDATE_CLIENTS);
        ((ChestBlockEntity) level.getBlockEntity(chestAt)).setItem(0, new ItemStack(Items.DIAMOND, 7));
    }

    /** Throws unless the ship stands at a corner, its chest still holding its diamonds. */
    private static void assertShip(GameTestHelper h, ServerLevel level, BlockPos corner) {
        for (int x = 0; x < SIZE.getX(); x++) {
            for (int z = 0; z < SIZE.getZ(); z++) {
                BlockPos p = corner.offset(x, 0, z);
                h.assertTrue(level.getBlockState(p).is(Blocks.STONE_BRICKS), "no floor at " + p + " in " + level);
            }
        }
        BlockPos chestAt = corner.offset(CHEST);
        h.assertTrue(
                level.getBlockEntity(chestAt) instanceof ChestBlockEntity chest
                        && chest.getItem(0).is(Items.DIAMOND)
                        && chest.getItem(0).getCount() == 7,
                "no chest of diamonds at " + chestAt + " in " + level);
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

    /** The blocks of the boxes at some corners. */
    private static Map<BlockPos, BlockState> blocks(ServerLevel level, BlockPos... corners) {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (BlockPos corner : corners) {
            for (BlockPos p : positions(box(corner))) {
                blocks.put(p.immutable(), level.getBlockState(p));
            }
        }
        return blocks;
    }
}
