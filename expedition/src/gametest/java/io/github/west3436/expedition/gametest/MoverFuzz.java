package io.github.west3436.expedition.gametest;

import com.mojang.logging.LogUtils;
import io.github.west3436.expedition.mover.MoveJob;
import io.github.west3436.expedition.mover.MoveRequest;
import io.github.west3436.expedition.mover.MoveResult;
import io.github.west3436.expedition.mover.MoveSettings;
import io.github.west3436.expedition.mover.QuietBlocks;
import io.github.west3436.expedition.mover.ShipMover;
import io.github.west3436.expedition.ship.BlockWeights;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Rotations;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;

/**
 * The mover's integrity fuzz (#12), which {@link MoverFuzzTests} runs.
 *
 * <ul>
 *   <li><b>The ship.</b> Each seed builds one box, from 2 to {@link #MAX} blocks a side, of random
 *       blocks: solid ones, blocks whose state has properties (stairs, slabs, logs, trapdoors, fence
 *       gates, end rods, chains), blocks whose shape fits their neighbours (fences, walls, panes,
 *       bars), blocks that need support (ladders, torches, lanterns, carpets, buttons), containers
 *       of random items, with names, lore, damage, enchantments, dye, potions, custom data and
 *       shulker boxes of items, double chests, furnaces and signs, and a few entities: pigs, cows,
 *       sheep, armour stands in armour, item frames holding items and dropped items.
 *   <li><b>A ship that keeps still.</b> The fuzz checks that nothing changed, so nothing on the ship
 *       may change by itself: no block ticks at random, nothing is powered or lit, no furnace has
 *       fuel, every shape already fits its neighbours, and the entities have no AI and no gravity,
 *       never despawn and, if items, are never picked up or merged. Then any change is the mover's.
 *   <li><b>The moves.</b> Each seed makes {@link #DEFAULT_MOVES} moves between five slots, three in
 *       the Overworld and two above the Nether's roof, or short hops of up to {@link #DRIFT} blocks
 *       a way, which may land on the ship's own box. About a third run on a budget of 8 to 256 work
 *       units a tick, so they span many ticks. Every destination is clear and loaded, and no ship
 *       holds an immovable block such as reinforced deepslate, so a refusal fails the seed with what
 *       a dry run then finds.
 *   <li><b>The checks.</b> After each move, and again before the next: every block state, every
 *       block entity's saved state but for its position, and every entity, by its UUID, with its
 *       saved state but for its position and motion, and its place in the box, must be as built;
 *       where the ship was must be air with no block entity; and no other entity may be anywhere in
 *       the slots, so nothing dropped, and nothing was left behind or made twice. The move's own
 *       counts must agree. After the last move the ship sits for {@link #FINAL_DWELL} ticks, past
 *       an item frame's check of its support, and is checked once more.
 * </ul>
 */
final class MoverFuzz {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** The seed every PR runs. */
    static final long PR_SEED = 1;

    /** A seed's moves, unless {@code -Pfuzz.moves} says otherwise. */
    static final int DEFAULT_MOVES = 24;

    /** The largest box, on each axis. */
    static final Vec3i MAX = new Vec3i(9, 7, 9);

    /** How far a hop goes on each axis, at most, and how far from its slot's base a box may sit. */
    static final int DRIFT = 3;

    /** Ticks the ship sits after its last move: an item frame checks its support every 101. */
    static final int FINAL_DWELL = 105;

    /** How long one move may take, in ticks, before the fuzz calls it stuck. */
    private static final int MOVE_TICK_LIMIT = 50_000;

    /** How many of the last moves a failure lists. */
    private static final int TRACE = 12;

    private static final int SLOT_SPACING = 32;
    private static final int OVERWORLD_Y = 100;

    /** Above the Nether's bedrock roof, where it is always air. */
    private static final int NETHER_Y = 160;

    /** Keeps the slots loaded and their entities ticking until the run lets them go. */
    private static final TicketType<ChunkPos> HELD =
            TicketType.create("expedition_fuzz_held", Comparator.comparingLong(ChunkPos::toLong));

    /** Saved keys that say where an entity is or how it moves, not what it is. */
    private static final List<String> ENTITY_PLACE_KEYS = List.of("Pos", "Motion", "TileX", "TileY", "TileZ");

    private static final List<BlockState> SOLID = states(
            Blocks.STONE,
            Blocks.COBBLESTONE,
            Blocks.OAK_PLANKS,
            Blocks.BRICKS,
            Blocks.STONE_BRICKS,
            Blocks.DEEPSLATE_BRICKS,
            Blocks.OAK_LOG,
            Blocks.QUARTZ_PILLAR,
            Blocks.WHITE_WOOL,
            Blocks.GLASS,
            Blocks.TINTED_GLASS,
            Blocks.IRON_BLOCK,
            Blocks.WAXED_COPPER_BLOCK,
            Blocks.OBSIDIAN);

    private static final List<BlockState> SHAPED = states(
            Blocks.OAK_FENCE,
            Blocks.NETHER_BRICK_FENCE,
            Blocks.COBBLESTONE_WALL,
            Blocks.GLASS_PANE,
            Blocks.IRON_BARS,
            Blocks.OAK_STAIRS,
            Blocks.STONE_BRICK_STAIRS,
            Blocks.OAK_SLAB,
            Blocks.OAK_TRAPDOOR,
            Blocks.IRON_TRAPDOOR,
            Blocks.OAK_FENCE_GATE,
            Blocks.END_ROD,
            Blocks.CHAIN,
            Blocks.COBWEB,
            Blocks.LADDER,
            Blocks.FLOWER_POT,
            Blocks.POTTED_POPPY,
            Blocks.WHITE_CARPET,
            Blocks.LANTERN,
            Blocks.TORCH,
            Blocks.WALL_TORCH,
            Blocks.STONE_BUTTON);

    private static final List<BlockState> CONTAINERS = states(
            Blocks.CHEST,
            Blocks.BARREL,
            Blocks.FURNACE,
            Blocks.DISPENSER,
            Blocks.DROPPER,
            Blocks.SHULKER_BOX,
            Blocks.RED_SHULKER_BOX);

    private static final List<BlockState> SIGNS =
            states(Blocks.OAK_SIGN, Blocks.OAK_WALL_SIGN, Blocks.SPRUCE_SIGN, Blocks.OAK_HANGING_SIGN);

    private static final List<Item> ITEMS = List.of(
            Items.DIAMOND,
            Items.IRON_INGOT,
            Items.COBBLESTONE,
            Items.OAK_LOG,
            Items.PAPER,
            Items.ARROW,
            Items.REDSTONE,
            Items.TORCH,
            Items.IRON_SWORD,
            Items.DIAMOND_PICKAXE,
            Items.BOW,
            Items.LEATHER_CHESTPLATE,
            Items.LEATHER_HELMET,
            Items.POTION,
            Items.SHULKER_BOX);

    private static final List<ResourceKey<Enchantment>> ENCHANTMENTS =
            List.of(Enchantments.SHARPNESS, Enchantments.UNBREAKING, Enchantments.EFFICIENCY, Enchantments.MENDING);

    /** Properties that would make a block act by itself or on its neighbours: always off. */
    private static final List<BooleanProperty> OFF = List.of(
            BlockStateProperties.WATERLOGGED,
            BlockStateProperties.POWERED,
            BlockStateProperties.LIT,
            BlockStateProperties.TRIGGERED);

    private MoverFuzz() {}

    /** A 64-bit finalizer (SplitMix64's), so that seeds 1, 2, 3 make unlike ships. */
    private static long mix(long seed) {
        long z = seed + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static List<BlockState> states(Block... blocks) {
        List<BlockState> states = new ArrayList<>();
        for (Block block : blocks) {
            BlockState state = block.defaultBlockState();
            if (state.isRandomlyTicking()) {
                throw new IllegalStateException(block + " ticks at random, so a ship of it wouldn't keep still");
            }
            states.add(state);
        }
        return List.copyOf(states);
    }

    // ---- What a run is ------------------------------------------------------------------

    /** What a broken move does, to show that the fuzz notices. */
    enum Sabotage {
        NONE,
        /** A container's first slot changes on the way. */
        BLOCK_ENTITY,
        /** An item is dropped where the ship was. */
        DROP
    }

    /**
     * One seed's run.
     *
     * @param seed the seed
     * @param moves how many moves it makes
     * @param sabotage what goes wrong, for the fuzz's own test
     * @param sabotageAt the move, from 1, that it goes wrong in
     */
    record Spec(long seed, int moves, Sabotage sabotage, int sabotageAt) {}

    /**
     * A seed that failed.
     *
     * @param seed the seed
     * @param message what failed, where, and how to replay it
     */
    record Failure(long seed, String message) {}

    /** A place a ship can be: a slot's base, which a ship may sit up to {@link #DRIFT} from. */
    private record Slot(String name, ServerLevel level, BlockPos base) {
        /** Everything a ship at this slot can touch, and a block beyond. */
        BoundingBox region() {
            int far = DRIFT + 2;
            return new BoundingBox(
                    base.getX() - far,
                    base.getY() - far,
                    base.getZ() - far,
                    base.getX() + MAX.getX() + far,
                    base.getY() + MAX.getY() + far,
                    base.getZ() + MAX.getZ() + far);
        }
    }

    /** Where a ship is: its slot, and its box's lowest corner. */
    private record Place(Slot slot, BlockPos corner) {
        ServerLevel level() {
            return slot.level;
        }

        @Override
        public String toString() {
            return slot.name + " " + corner.toShortString();
        }
    }

    /** One entity as a snapshot holds it: its type, its place in the box and its saved state. */
    private record Thing(String type, Vec3 at, CompoundTag data) {}

    /** What a ship holds, relative to its box's lowest corner. */
    private record Snapshot(
            Map<Vec3i, BlockState> blocks,
            Map<Vec3i, CompoundTag> blockEntities,
            Map<UUID, Thing> entities,
            List<String> problems) {
        double mass() {
            return blocks.values().stream().mapToDouble(BlockWeights::weight).sum();
        }

        int nonAir() {
            return (int) blocks.values().stream().filter(s -> !s.isAir()).count();
        }
    }

    // ---- The world the runs share --------------------------------------------------------

    /** The slots of one area, and the chunks held loaded around them. */
    private static final class World {
        final List<Slot> slots = new ArrayList<>();
        final Map<ServerLevel, Set<ChunkPos>> held = new IdentityHashMap<>();

        World(GameTestHelper h, int area) {
            ServerLevel overworld = h.getLevel();
            ServerLevel nether = overworld.getServer().getLevel(Level.NETHER);
            if (nether == null) {
                throw new GameTestAssertException("no Nether");
            }
            // Far from the GameTest grid and from MoverTests' areas, each area its own.
            int x = 24_576 + 4_096 * area;
            int z = 24_576;
            slots.add(new Slot("overworld slot 0", overworld, new BlockPos(x, OVERWORLD_Y, z)));
            slots.add(new Slot("overworld slot 1", overworld, new BlockPos(x + SLOT_SPACING, OVERWORLD_Y, z)));
            slots.add(new Slot("overworld slot 2", overworld, new BlockPos(x, OVERWORLD_Y, z + SLOT_SPACING)));
            slots.add(new Slot("nether slot 0", nether, new BlockPos(x, NETHER_Y, z)));
            slots.add(new Slot("nether slot 1", nether, new BlockPos(x + SLOT_SPACING, NETHER_Y, z)));
            for (Slot slot : slots) {
                BoundingBox r = slot.region();
                Set<ChunkPos> chunks = held.computeIfAbsent(slot.level, l -> new HashSet<>());
                for (int cx = r.minX() >> 4; cx <= r.maxX() >> 4; cx++) {
                    for (int cz = r.minZ() >> 4; cz <= r.maxZ() >> 4; cz++) {
                        chunks.add(new ChunkPos(cx, cz));
                    }
                }
            }
        }

        /**
         * Holds the slots' chunks, and says whether they and their entities have all loaded. They are
         * force-loaded too: a level with no player and no forced chunk stops ticking its entities and
         * block entities 300 ticks after it empties, and region tickets don't count. Only the
         * Overworld has the GameTest grid's forced chunks, and the ship has to tick in the Nether
         * too, to settle and to sit out its last dwell.
         */
        boolean load() {
            boolean loaded = true;
            for (Map.Entry<ServerLevel, Set<ChunkPos>> e : held.entrySet()) {
                ServerLevel level = e.getKey();
                for (ChunkPos c : e.getValue()) {
                    level.getChunkSource().addRegionTicket(HELD, c, 2, c);
                    level.setChunkForced(c.x, c.z, true);
                    level.getChunk(c.x, c.z);
                    loaded &= level.areEntitiesLoaded(c.toLong());
                }
            }
            return loaded;
        }

        void release() {
            held.forEach((level, chunks) -> {
                for (ChunkPos c : chunks) {
                    level.getChunkSource().removeRegionTicket(HELD, c, 2, c);
                    level.setChunkForced(c.x, c.z, false);
                }
            });
        }

        /** Takes every block and entity out of every slot, quietly, so nothing drops. */
        void wipe() {
            for (Slot slot : slots) {
                BoundingBox r = slot.region();
                for (Entity e : slot.level.getEntities((Entity) null, AABB.of(r), e -> !(e instanceof Player))) {
                    e.discard();
                }
                for (BlockPos p : BlockPos.betweenClosed(r.minX(), r.minY(), r.minZ(), r.maxX(), r.maxY(), r.maxZ())) {
                    LevelChunk chunk = slot.level.getChunkAt(p);
                    QuietBlocks.takeBlockEntity(slot.level, chunk, p);
                    QuietBlocks.set(slot.level, chunk, p, Blocks.AIR.defaultBlockState());
                }
                slot.level.getBlockTicks().clearArea(r);
                slot.level.getFluidTicks().clearArea(r);
            }
        }

        /** Every entity in any slot but players, each once. */
        List<Entity> entities() {
            Map<Entity, Boolean> seen = new IdentityHashMap<>();
            List<Entity> all = new ArrayList<>();
            for (Slot slot : slots) {
                for (Entity e :
                        slot.level.getEntities((Entity) null, AABB.of(slot.region()), e -> !(e instanceof Player))) {
                    if (seen.put(e, Boolean.TRUE) == null) {
                        all.add(e);
                    }
                }
            }
            return all;
        }
    }

    // ---- Running the seeds --------------------------------------------------------------

    /** Runs seeds one after another in one area, a tick at a time, from a GameTest sequence. */
    static final class Driver {
        private final GameTestHelper h;
        private final int area;
        private final List<Spec> specs;
        private final List<Failure> failures = new ArrayList<>();
        private World world;
        private boolean loaded;
        private int next;
        private Run run;
        private boolean done;

        Driver(GameTestHelper h, int area, List<Spec> specs) {
            this.h = h;
            this.area = area;
            this.specs = List.copyOf(specs);
        }

        /**
         * Works the runs for a tick.
         *
         * @throws GameTestAssertException until every seed has run, so that a sequence waits
         */
        void tick() {
            if (!done) {
                step();
            }
            if (!done) {
                throw new GameTestAssertException("the mover fuzz is still running");
            }
        }

        List<Failure> failures() {
            return List.copyOf(failures);
        }

        private void step() {
            if (world == null) {
                world = new World(h, area);
            }
            if (!loaded) {
                loaded = world.load();
                if (!loaded) {
                    return;
                }
            }
            if (run == null) {
                if (next == specs.size()) {
                    world.release();
                    done = true;
                    return;
                }
                run = new Run(world, specs.get(next++));
            }
            if (run.tick()) {
                if (run.failure != null && run.spec.sabotage() != Sabotage.NONE) {
                    // The fuzz's own test, whose failures are meant: not logged as a seed's.
                    LOGGER.info("Mover fuzz self-test: a {} sabotage failed as it should", run.spec.sabotage());
                    failures.add(new Failure(run.spec.seed(), run.failure));
                } else if (run.failure != null) {
                    LOGGER.error(run.failure);
                    failures.add(new Failure(run.spec.seed(), run.failure));
                } else {
                    LOGGER.info(run.summary());
                }
                run = null;
            }
        }
    }

    /** The phases of a run. */
    private enum State {
        BUILD,
        SETTLE,
        IDLE,
        MOVING,
        DWELL,
        FINAL_DWELL,
        CLEAN_UP,
        DONE
    }

    /** A move about to be made or being made. */
    private record Move(int number, Place from, Place to, int budget, String kind) {
        @Override
        public String toString() {
            return "move " + number + ", " + kind + ": " + from + " to " + to + ", "
                    + (budget > 0 ? "on a budget of " + budget + " units a tick" : "on the default budget");
        }
    }

    /** One seed's run, a tick at a time. */
    private static final class Run {
        final World w;
        final Spec spec;
        final Random random;
        final ArrayDeque<String> trace = new ArrayDeque<>();
        final long started = System.nanoTime();
        State state = State.BUILD;
        Vec3i size;
        Place at;
        Snapshot expected;
        Move move;
        MoveJob job;
        int moved;
        int wait;
        int moveTicks;
        String failure;

        int within;
        int across;
        int hops;
        int sliced;
        int ticks;

        Run(World w, Spec spec) {
            this.w = w;
            this.spec = spec;
            // Scrambled first: java.util.Random's first values from seeds close together are close too.
            this.random = new Random(mix(spec.seed()));
        }

        /** Works the run for a tick; true once it is over, passed or failed. */
        boolean tick() {
            try {
                work();
            } catch (Throwable t) {
                fail(t);
            }
            if (state == State.CLEAN_UP && (job == null || job.isDone())) {
                // A move still going would be wiped under it, so a failed run waits for it first.
                w.wipe();
                state = State.DONE;
            }
            return state == State.DONE;
        }

        private void work() {
            switch (state) {
                case BUILD -> {
                    w.wipe();
                    size = new Vec3i(
                            2 + random.nextInt(MAX.getX() - 1),
                            2 + random.nextInt(MAX.getY() - 1),
                            2 + random.nextInt(MAX.getZ() - 1));
                    at = new Place(w.slots.get(0), w.slots.get(0).base.offset(drift()));
                    new Builder(at.level(), at.corner(), size, random).build();
                    // Entities settle into their first tick before the ship is taken as the standard.
                    wait = 2;
                    state = State.SETTLE;
                }
                case SETTLE -> {
                    if (--wait <= 0) {
                        expected = snapshot(at, size);
                        if (!expected.problems.isEmpty()) {
                            throw new AssertionError("the ship as built: " + expected.problems);
                        }
                        if (expected.blockEntities.isEmpty() || expected.entities.isEmpty()) {
                            throw new AssertionError("the builder made a ship with "
                                    + expected.blockEntities.size() + " block entities and "
                                    + expected.entities.size() + " entities, and every ship has some of both");
                        }
                        state = State.IDLE;
                    }
                }
                case IDLE -> {
                    check("before " + (moved < spec.moves() ? "move " + (moved + 1) : "the end"));
                    if (moved == spec.moves()) {
                        wait = FINAL_DWELL;
                        state = State.FINAL_DWELL;
                        return;
                    }
                    start();
                    if (job.isDone()) {
                        arrive();
                    }
                }
                case MOVING -> {
                    if (job.isDone()) {
                        arrive();
                    } else if (++moveTicks > MOVE_TICK_LIMIT) {
                        throw new AssertionError("the move is still going after " + MOVE_TICK_LIMIT + " ticks");
                    }
                }
                case DWELL -> {
                    if (--wait <= 0) {
                        state = State.IDLE;
                    }
                }
                case FINAL_DWELL -> {
                    if (--wait <= 0) {
                        check(FINAL_DWELL + " ticks after the last move");
                        state = State.CLEAN_UP;
                    }
                }
                case CLEAN_UP, DONE -> {}
            }
        }

        /** A drift from a slot's base. */
        private Vec3i drift() {
            return new Vec3i(
                    random.nextInt(2 * DRIFT + 1) - DRIFT,
                    random.nextInt(2 * DRIFT + 1) - DRIFT,
                    random.nextInt(2 * DRIFT + 1) - DRIFT);
        }

        private void start() {
            Place to;
            String kind;
            if (random.nextInt(4) == 0) {
                Vec3i drift;
                do {
                    drift = drift();
                } while (at.slot.base.offset(drift).equals(at.corner));
                to = new Place(at.slot, at.slot.base.offset(drift));
                BoundingBox box = box(at.corner, size);
                kind = box.intersects(box(to.corner, size)) ? "a hop onto its own box" : "a short hop";
                hops++;
            } else {
                Slot slot;
                do {
                    slot = w.slots.get(random.nextInt(w.slots.size()));
                } while (slot == at.slot);
                to = new Place(slot, slot.base.offset(drift()));
                if (slot.level == at.level()) {
                    kind = "within " + slot.level.dimension().location();
                    within++;
                } else {
                    kind = "into " + slot.level.dimension().location();
                    across++;
                }
            }
            int budget = random.nextInt(3) == 0 ? 8 + random.nextInt(249) : 0;
            move = new Move(moved + 1, at, to, budget, kind);
            trace.addLast(move.toString());
            if (trace.size() > TRACE) {
                trace.removeFirst();
            }
            MoveSettings settings = budget > 0
                    ? MoveSettings.DEFAULT.withBudget(budget, ShipMover.SERVER_NANOS_PER_TICK)
                    : MoveSettings.DEFAULT;
            MoveRequest request = new MoveRequest(at.level(), box(at.corner, size), to.level(), to.corner);
            moveTicks = 0;
            state = State.MOVING;
            job = ShipMover.start(request, settings);
        }

        /** The move is over: everything must have arrived, as it was, and nothing be left. */
        private void arrive() {
            MoveResult result;
            try {
                result = job.result().join();
            } catch (CompletionException e) {
                throw new AssertionError("the move failed part-way through", e.getCause());
            }
            if (spec.sabotage() != Sabotage.NONE && move.number() == spec.sabotageAt()) {
                sabotage();
            }
            if (!result.moved()) {
                // Every destination is clear and loaded, so a refusal is the mover's error or the fuzz's.
                throw new AssertionError(
                        "the move was refused as " + result.refusal().get() + "; a dry run of it now finds "
                                + ShipMover.dryRun(job.request()).obstacles());
            }
            Place from = at;
            at = move.to();
            if (result.ticks() > 1) {
                sliced++;
            }
            ticks += result.ticks();
            moved++;
            job = null;
            checkLeft(from);
            check("after the move");
            // After the full checks, which say what differs, not only that a count does.
            String counts = "carried " + result.mass() + " mass, " + result.blocks() + " blocks, "
                    + result.blockEntities() + " block entities and " + result.entities() + " entities, and left "
                    + result.entitiesLeft() + "; the ship has " + expected.mass() + ", " + expected.nonAir() + ", "
                    + expected.blockEntities.size() + " and " + expected.entities.size();
            if (Math.abs(result.mass() - expected.mass()) > 1e-6
                    || result.blocks() != expected.nonAir()
                    || result.blockEntities() != expected.blockEntities.size()
                    || result.entities() != expected.entities.size()
                    || result.entitiesLeft() != 0) {
                throw new AssertionError("the move " + counts);
            }
            wait = 1 + random.nextInt(3);
            state = State.DWELL;
        }

        /** The ship is where it should be, as it was built, and no entity is anywhere else. */
        private void check(String when) {
            Snapshot now = snapshot(at, size);
            List<String> problems = new ArrayList<>(now.problems);
            diff(expected, now, problems);
            for (Entity e : w.entities()) {
                Thing thing = expected.entities.get(e.getUUID());
                if (thing == null) {
                    problems.add("an entity that isn't the ship's: " + describe(e));
                } else if (e.level() != at.level() || !box(at.corner, size).isInside(e.blockPosition())) {
                    problems.add("the ship's " + describe(e) + " is outside the ship, which is at " + at);
                }
            }
            // Where any of the ship's entities missing from the slots went.
            for (UUID id : expected.entities.keySet()) {
                if (now.entities.containsKey(id)) {
                    continue;
                }
                for (ServerLevel level : at.level().getServer().getAllLevels()) {
                    Entity e = level.getEntity(id);
                    if (e != null) {
                        problems.add("the ship's " + describe(e) + " is far from the ship");
                    }
                }
            }
            if (!problems.isEmpty()) {
                throw new AssertionError(when + ", with the ship at " + at + ": " + summarise(problems));
            }
        }

        /** Where the ship was is air, with no block entity, unless the ship is there now. */
        private void checkLeft(Place from) {
            BoundingBox left = box(from.corner, size);
            BoundingBox now = box(at.corner, size);
            List<String> problems = new ArrayList<>();
            for (BlockPos p : BlockPos.betweenClosed(
                    left.minX(), left.minY(), left.minZ(), left.maxX(), left.maxY(), left.maxZ())) {
                if (from.level() == at.level() && now.isInside(p)) {
                    continue;
                }
                BlockState state = from.level().getBlockState(p);
                BlockEntity be = from.level().getChunkAt(p).getBlockEntity(p, LevelChunk.EntityCreationType.CHECK);
                if (!state.isAir() || be != null) {
                    problems.add("where the ship was, " + p.toShortString() + " holds " + state
                            + (be != null ? " and a block entity" : ""));
                }
            }
            if (!problems.isEmpty()) {
                throw new AssertionError("after the move, the ship left behind: " + summarise(problems));
            }
        }

        /** What the fuzz's own test breaks, as a broken mover would. */
        private void sabotage() {
            switch (spec.sabotage()) {
                case BLOCK_ENTITY -> {
                    BoundingBox box = box(move.to().corner, size);
                    for (BlockPos p : BlockPos.betweenClosed(
                            box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
                        if (move.to().level().getBlockEntity(p) instanceof BaseContainerBlockEntity c) {
                            c.setItem(0, new ItemStack(Items.DIRT));
                            return;
                        }
                    }
                    throw new AssertionError("no container to sabotage");
                }
                case DROP -> {
                    BlockPos p = move.from().corner;
                    ItemEntity dropped = new ItemEntity(
                            move.from().level(),
                            p.getX() + 0.5,
                            p.getY() + 0.5,
                            p.getZ() + 0.5,
                            new ItemStack(Items.STONE));
                    dropped.setNoGravity(true);
                    move.from().level().addFreshEntity(dropped);
                }
                case NONE -> {}
            }
        }

        private void fail(Throwable t) {
            String where = switch (state) {
                case BUILD, SETTLE -> "while building the ship";
                case MOVING -> "at move " + move.number() + " of " + spec.moves();
                case IDLE, DWELL ->
                    moved == 0 ? "before move 1" : "in the ticks after move " + moved + " of " + spec.moves();
                default -> "after the last move";
            };
            String replay = "./gradlew :expedition:runGameTestServer -Pfuzz.seed=" + spec.seed()
                    + (spec.moves() != DEFAULT_MOVES ? " -Pfuzz.moves=" + spec.moves() : "");
            StringBuilder message = new StringBuilder("Mover fuzz: seed ")
                    .append(spec.seed())
                    .append(" failed ")
                    .append(where)
                    .append(": ")
                    .append(t);
            Throwable cause = t.getCause();
            if (cause != null) {
                message.append("\nCaused by: ").append(cause);
                for (StackTraceElement e : cause.getStackTrace()) {
                    if (e.getClassName().startsWith("io.github.west3436")) {
                        message.append("\n    at ").append(e);
                    }
                }
            }
            message.append("\nThe ship: a box of ")
                    .append(size == null ? "?" : size.getX() + "x" + size.getY() + "x" + size.getZ())
                    .append(
                            expected == null
                                    ? ""
                                    : ", " + expected.nonAir() + " blocks, " + expected.blockEntities.size()
                                            + " block entities, " + expected.entities.size() + " entities")
                    .append("\nReplay: ")
                    .append(replay)
                    .append("\nThe last moves:\n  ")
                    .append(trace.isEmpty() ? "(none)" : String.join("\n  ", trace));
            failure = message.toString();
            state = State.CLEAN_UP;
        }

        String summary() {
            return "Mover fuzz: seed " + spec.seed() + " passed " + moved + " moves (" + within + " within a level, "
                    + across + " across levels, " + hops + " hops, " + sliced + " over several ticks, " + ticks
                    + " ticks of moving) of a " + size.getX() + "x" + size.getY() + "x" + size.getZ() + " ship of "
                    + expected.nonAir() + " blocks, " + expected.blockEntities.size() + " block entities and "
                    + expected.entities.size() + " entities, in " + (System.nanoTime() - started) / 1_000_000
                    + " ms";
        }
    }

    // ---- Building a ship ----------------------------------------------------------------

    /** Builds one random ship that keeps still. */
    private static final class Builder {
        final ServerLevel level;
        final BlockPos corner;
        final Vec3i size;
        final Random random;
        final HolderLookup.Provider registries;
        final BlockState[][][] plan;
        final Set<BlockPos> taken = new HashSet<>();

        Builder(ServerLevel level, BlockPos corner, Vec3i size, Random random) {
            this.level = level;
            this.corner = corner;
            this.size = size;
            this.random = random;
            this.registries = level.registryAccess();
            this.plan = new BlockState[size.getX()][size.getY()][size.getZ()];
        }

        void build() {
            plan();
            BoundingBox box = box(corner, size);
            forEach((x, y, z) -> level.setBlock(
                    corner.offset(x, y, z), plan[x][y][z], Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
            fit(box);
            fill(box);
            addEntities(box);
        }

        /** Chooses every block, from the bottom up, so that a sign knows what it stands on. */
        private void plan() {
            BlockState air = Blocks.AIR.defaultBlockState();
            for (int y = 0; y < size.getY(); y++) {
                for (int x = 0; x < size.getX(); x++) {
                    for (int z = 0; z < size.getZ(); z++) {
                        if (plan[x][y][z] != null) {
                            continue;
                        }
                        int roll = random.nextInt(100);
                        BlockState state;
                        if (roll < 40) {
                            state = air;
                        } else if (roll < 65) {
                            state = randomise(pick(SOLID));
                        } else if (roll < 82) {
                            state = randomise(pick(SHAPED));
                        } else if (roll < 92) {
                            state = randomise(pick(CONTAINERS));
                        } else if (roll < 96) {
                            state = randomise(pick(SIGNS));
                        } else {
                            state = doubleChest(x, y, z);
                        }
                        plan[x][y][z] = state;
                    }
                }
            }
            // Every ship has a container and an air cell for an entity.
            plan[random.nextInt(size.getX())][0][random.nextInt(size.getZ())] =
                    randomise(Blocks.CHEST.defaultBlockState());
            plan[random.nextInt(size.getX())][size.getY() - 1][random.nextInt(size.getZ())] = air;
        }

        /** Half of a double chest, and its other half beside it if there's room; or a single chest. */
        private BlockState doubleChest(int x, int y, int z) {
            Direction facing = Direction.from2DDataValue(random.nextInt(4));
            Direction toOther = facing.getClockWise();
            int ox = x + toOther.getStepX();
            int oz = z + toOther.getStepZ();
            BlockState chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing);
            if (ox < 0 || ox >= size.getX() || oz < 0 || oz >= size.getZ() || plan[ox][y][oz] != null) {
                return chest;
            }
            plan[ox][y][oz] = chest.setValue(ChestBlock.TYPE, ChestType.RIGHT);
            return chest.setValue(ChestBlock.TYPE, ChestType.LEFT);
        }

        private BlockState pick(List<BlockState> states) {
            return states.get(random.nextInt(states.size()));
        }

        /** Random values for every property, but none that would have a block act by itself. */
        private BlockState randomise(BlockState state) {
            for (Property<?> property : state.getProperties()) {
                state = withRandom(state, property);
            }
            for (BooleanProperty off : OFF) {
                if (state.hasProperty(off)) {
                    state = state.setValue(off, false);
                }
            }
            // A barrel that thinks it is open closes itself.
            if (state.is(Blocks.BARREL)) {
                state = state.setValue(BlockStateProperties.OPEN, false);
            }
            if (state.hasProperty(ChestBlock.TYPE)) {
                state = state.setValue(ChestBlock.TYPE, ChestType.SINGLE);
            }
            return state;
        }

        private <T extends Comparable<T>> BlockState withRandom(BlockState state, Property<T> property) {
            List<T> values = List.copyOf(property.getPossibleValues());
            return state.setValue(property, values.get(random.nextInt(values.size())));
        }

        /**
         * Fits every block to its neighbours, as the world would on its first update: fences join,
         * stairs turn their corners, and a torch, sign or ladder with nothing to hold it goes. Done
         * until nothing changes, so that a move's neighbour updates find nothing to change either.
         */
        private void fit(BoundingBox box) {
            for (int pass = 0; pass < 16; pass++) {
                boolean changed = false;
                for (BlockPos p : BlockPos.betweenClosed(
                        box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
                    BlockState now = level.getBlockState(p);
                    BlockState fitted = Block.updateFromNeighbourShapes(now, level, p);
                    if (fitted != now) {
                        if (now.hasBlockEntity()
                                && !now.is(fitted.getBlock())
                                && !(now.getBlock() instanceof SignBlock)) {
                            throw new IllegalStateException("fitting " + now + " at " + p.toShortString()
                                    + " would lose its block entity, as " + fitted);
                        }
                        level.setBlock(p, fitted, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                        changed = true;
                    }
                }
                if (!changed) {
                    return;
                }
            }
            throw new IllegalStateException("the ship's shapes didn't settle in 16 passes");
        }

        /** Fills the containers and writes on the signs. */
        private void fill(BoundingBox box) {
            for (BlockPos p :
                    BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
                BlockEntity be = level.getBlockEntity(p);
                if (be instanceof AbstractFurnaceBlockEntity furnace) {
                    furnace.setItem(0, maybe(randomItem(false)));
                    // Nothing that burns: a furnace with no fuel keeps still.
                    ItemStack fuel = randomItem(false);
                    furnace.setItem(1, fuel.getBurnTime(RecipeType.SMELTING) == 0 ? fuel : ItemStack.EMPTY);
                    furnace.setItem(2, maybe(randomItem(false)));
                } else if (be instanceof Container container) {
                    boolean shulker = be instanceof ShulkerBoxBlockEntity;
                    int slots = container.getContainerSize();
                    int items = 1 + random.nextInt(Math.min(slots, 8));
                    for (int i = 0; i < items; i++) {
                        container.setItem(random.nextInt(slots), randomItem(shulker));
                    }
                } else if (be instanceof SignBlockEntity sign) {
                    sign.setText(randomText(), true);
                    if (random.nextBoolean()) {
                        sign.setText(randomText(), false);
                    }
                    sign.setWaxed(random.nextBoolean());
                }
                if (be instanceof BaseContainerBlockEntity && random.nextInt(3) == 0) {
                    CompoundTag tag = be.saveWithoutMetadata(registries);
                    tag.putString("CustomName", Component.Serializer.toJson(Component.literal(word()), registries));
                    be.loadWithComponents(tag, registries);
                }
                if (be != null) {
                    be.setChanged();
                }
            }
        }

        private ItemStack maybe(ItemStack stack) {
            return random.nextBoolean() ? stack : ItemStack.EMPTY;
        }

        private SignText randomText() {
            SignText text = new SignText();
            for (int line = 0; line < 4; line++) {
                if (random.nextBoolean()) {
                    text = text.setMessage(line, Component.literal(word() + " " + random.nextInt(1000)));
                }
            }
            text = text.setColor(DyeColor.byId(random.nextInt(16)));
            return text.setHasGlowingText(random.nextBoolean());
        }

        private String word() {
            String[] words = {"Keepsake", "Cargo", "Spare", "Log", "Fuel", "Ration", "Map", "Toolkit", "Ore"};
            return words[random.nextInt(words.length)];
        }

        /** A random stack, with random components. */
        private ItemStack randomItem(boolean inShulkerBox) {
            Item item;
            do {
                item = ITEMS.get(random.nextInt(ITEMS.size()));
            } while (inShulkerBox && item == Items.SHULKER_BOX);
            ItemStack stack = new ItemStack(item);
            stack.setCount(1 + random.nextInt(stack.getMaxStackSize()));
            if (random.nextInt(3) == 0) {
                stack.set(DataComponents.CUSTOM_NAME, Component.literal(word() + " " + random.nextInt(100)));
            }
            if (random.nextInt(6) == 0) {
                stack.set(
                        DataComponents.LORE,
                        new ItemLore(List.of(Component.literal(word()), Component.literal("No. " + random.nextInt()))));
            }
            if (random.nextInt(5) == 0) {
                CompoundTag data = new CompoundTag();
                data.putInt("serial", random.nextInt());
                data.putString("owner", word());
                CompoundTag nested = new CompoundTag();
                nested.putLong("stamp", random.nextLong());
                data.put("nested", nested);
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
            }
            if (random.nextInt(8) == 0) {
                stack.set(DataComponents.REPAIR_COST, random.nextInt(40));
            }
            if (stack.isDamageableItem() && random.nextBoolean()) {
                stack.setDamageValue(random.nextInt(stack.getMaxDamage()));
            }
            if (stack.isDamageableItem() && random.nextInt(3) == 0) {
                ResourceKey<Enchantment> key = ENCHANTMENTS.get(random.nextInt(ENCHANTMENTS.size()));
                stack.enchant(registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key), 1 + random.nextInt(3));
            }
            if (item == Items.LEATHER_CHESTPLATE || item == Items.LEATHER_HELMET) {
                stack.set(
                        DataComponents.DYED_COLOR, new DyedItemColor(random.nextInt(0x1000000), random.nextBoolean()));
            }
            if (item == Items.POTION) {
                stack.set(
                        DataComponents.POTION_CONTENTS,
                        new PotionContents(random.nextBoolean() ? Potions.HEALING : Potions.SWIFTNESS));
            }
            if (item == Items.SHULKER_BOX) {
                List<ItemStack> inside = new ArrayList<>();
                int n = 1 + random.nextInt(4);
                for (int i = 0; i < n; i++) {
                    inside.add(randomItem(true));
                }
                stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(inside));
            }
            return stack;
        }

        /** A few entities, each in air cells of its own, which nothing in them collides with. */
        private void addEntities(BoundingBox box) {
            int count = 1 + random.nextInt(4);
            int placed = 0;
            for (int attempt = 0; attempt < 200 && placed < count; attempt++) {
                BlockPos cell = corner.offset(
                        random.nextInt(size.getX()), random.nextInt(size.getY()), random.nextInt(size.getZ()));
                int kind = random.nextInt(7);
                if (switch (kind) {
                    case 0, 1, 2 -> addMob(box, cell, kind);
                    case 3 -> addArmorStand(box, cell);
                    case 4, 5 -> addFrame(box, cell, kind == 5);
                    default -> addItem(box, cell);
                }) {
                    placed++;
                }
            }
            if (placed == 0) {
                // The plan keeps one top cell clear, and an item fits any clear cell.
                for (BlockPos p : BlockPos.betweenClosed(
                        box.minX(), box.maxY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
                    if (addItem(box, p.immutable())) {
                        return;
                    }
                }
                throw new IllegalStateException("no room for an entity on the ship");
            }
        }

        /** Whether a cell is in the box, air, not yet taken, and clear of what's below it. */
        private boolean free(BoundingBox box, BlockPos cell) {
            if (!box.isInside(cell)
                    || taken.contains(cell)
                    || !level.getBlockState(cell).isAir()) {
                return false;
            }
            BlockPos below = cell.below();
            VoxelShape shape = level.getBlockState(below).getCollisionShape(level, below);
            // A fence or wall below reaches up into the cell.
            return shape.isEmpty() || shape.max(Direction.Axis.Y) <= 1.0;
        }

        private boolean addMob(BoundingBox box, BlockPos cell, int kind) {
            EntityType<? extends Mob> type = switch (kind) {
                case 0 -> EntityType.PIG;
                case 1 -> EntityType.COW;
                default -> EntityType.SHEEP;
            };
            if (!free(box, cell) || !free(box, cell.above())) {
                return false;
            }
            Mob mob = type.create(level);
            mob.moveTo(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5, random.nextFloat() * 360, 0);
            mob.setNoAi(true);
            mob.setNoGravity(true);
            mob.setPersistenceRequired();
            mob.setHealth(1 + random.nextInt((int) mob.getMaxHealth()));
            if (random.nextBoolean()) {
                mob.setCustomName(Component.literal(word()));
            }
            if (mob instanceof Sheep sheep) {
                sheep.setColor(DyeColor.byId(random.nextInt(16)));
                sheep.setSheared(random.nextBoolean());
            }
            if (random.nextInt(3) == 0) {
                mob.setItemSlot(EquipmentSlot.HEAD, randomItem(false));
            }
            return add(mob, cell, cell.above());
        }

        private boolean addArmorStand(BoundingBox box, BlockPos cell) {
            if (!free(box, cell) || !free(box, cell.above())) {
                return false;
            }
            ArmorStand stand = new ArmorStand(level, cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5);
            stand.setYRot(random.nextFloat() * 360);
            stand.setNoGravity(true);
            CompoundTag tag = stand.saveWithoutId(new CompoundTag());
            tag.putBoolean("ShowArms", random.nextBoolean());
            tag.putBoolean("Small", random.nextBoolean());
            tag.putBoolean("NoBasePlate", random.nextBoolean());
            CompoundTag pose = new CompoundTag();
            pose.put(
                    "Head",
                    new Rotations(random.nextFloat() * 90, random.nextFloat() * 90, random.nextFloat() * 90).save());
            tag.put("Pose", pose);
            stand.load(tag);
            for (EquipmentSlot slot :
                    new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.MAINHAND}) {
                if (random.nextBoolean()) {
                    stand.setItemSlot(slot, randomItem(false));
                }
            }
            return add(stand, cell, cell.above());
        }

        private boolean addFrame(BoundingBox box, BlockPos cell, boolean glow) {
            if (!free(box, cell)) {
                return false;
            }
            List<Direction> faces = new ArrayList<>();
            for (Direction d : Direction.values()) {
                BlockPos support = cell.relative(d.getOpposite());
                if (box.isInside(support) && level.getBlockState(support).isSolid()) {
                    faces.add(d);
                }
            }
            if (faces.isEmpty()) {
                return false;
            }
            Direction facing = faces.get(random.nextInt(faces.size()));
            ItemFrame frame = glow ? new GlowItemFrame(level, cell, facing) : new ItemFrame(level, cell, facing);
            if (!frame.survives()) {
                return false;
            }
            if (random.nextInt(4) != 0) {
                frame.setItem(randomItem(false), false);
                frame.setRotation(random.nextInt(8));
            }
            return add(frame, cell);
        }

        private boolean addItem(BoundingBox box, BlockPos cell) {
            if (!free(box, cell)) {
                return false;
            }
            ItemEntity item = new ItemEntity(
                    level, cell.getX() + 0.5, cell.getY() + 0.25, cell.getZ() + 0.5, randomItem(false), 0, 0, 0);
            item.setNoGravity(true);
            item.setNeverPickUp();
            item.setUnlimitedLifetime();
            return add(item, cell);
        }

        private boolean add(Entity entity, BlockPos... cells) {
            if (!level.addFreshEntity(entity)) {
                return false;
            }
            for (BlockPos cell : cells) {
                taken.add(cell.immutable());
            }
            return true;
        }

        private interface Cell {
            void at(int x, int y, int z);
        }

        private void forEach(Cell cell) {
            for (int x = 0; x < size.getX(); x++) {
                for (int y = 0; y < size.getY(); y++) {
                    for (int z = 0; z < size.getZ(); z++) {
                        cell.at(x, y, z);
                    }
                }
            }
        }
    }

    // ---- Snapshots ----------------------------------------------------------------------

    private static BoundingBox box(BlockPos corner, Vec3i size) {
        return BoundingBox.fromCorners(corner, corner.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1));
    }

    /** What the ship at a place holds now, and anything amiss in how the level holds it. */
    private static Snapshot snapshot(Place place, Vec3i size) {
        ServerLevel level = place.level();
        BlockPos c = place.corner();
        BoundingBox box = box(c, size);
        HolderLookup.Provider registries = level.registryAccess();
        Map<Vec3i, BlockState> blocks = new HashMap<>();
        Map<Vec3i, CompoundTag> blockEntities = new HashMap<>();
        Map<UUID, Thing> entities = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (BlockPos p :
                BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            BlockPos at = p.immutable();
            Vec3i rel = new Vec3i(at.getX() - c.getX(), at.getY() - c.getY(), at.getZ() - c.getZ());
            BlockState state = level.getBlockState(at);
            blocks.put(rel, state);
            // CHECK: never make a block entity that isn't there, which would hide one lost.
            BlockEntity be = level.getChunkAt(at).getBlockEntity(at, LevelChunk.EntityCreationType.CHECK);
            if (be != null) {
                if (!be.getBlockPos().equals(at)) {
                    problems.add("the block entity at " + at.toShortString() + " thinks it is at "
                            + be.getBlockPos().toShortString());
                }
                if (be.getBlockState() != state) {
                    problems.add("the block entity at " + at.toShortString() + " thinks its block is "
                            + be.getBlockState() + ", not " + state);
                }
                if (be.isRemoved()) {
                    problems.add("the block entity at " + at.toShortString() + " is removed");
                }
                CompoundTag tag = be.saveWithFullMetadata(registries);
                tag.remove("x");
                tag.remove("y");
                tag.remove("z");
                blockEntities.put(rel, tag);
            }
        }
        Vec3 origin = Vec3.atLowerCornerOf(c);
        for (Entity e : level.getEntities(
                (Entity) null,
                AABB.of(box).inflate(1),
                e -> !(e instanceof Player) && box.isInside(e.blockPosition()))) {
            CompoundTag tag = e.saveWithoutId(new CompoundTag());
            ENTITY_PLACE_KEYS.forEach(tag::remove);
            // A living entity's attributes are saved from a hash map, so in no set order.
            if (tag.get("attributes") instanceof ListTag attributes) {
                List<Tag> sorted = new ArrayList<>(attributes);
                sorted.sort(Comparator.comparing(a -> ((CompoundTag) a).getString("id")));
                ListTag ordered = new ListTag();
                ordered.addAll(sorted);
                tag.put("attributes", ordered);
            }
            Thing thing = new Thing(
                    BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(),
                    e.position().subtract(origin),
                    tag);
            if (entities.put(e.getUUID(), thing) != null) {
                problems.add("two entities on the ship share the UUID " + e.getUUID() + ": " + describe(e));
            }
        }
        return new Snapshot(blocks, blockEntities, entities, problems);
    }

    /** Every way two snapshots of one ship differ. */
    private static void diff(Snapshot want, Snapshot got, List<String> problems) {
        want.blocks.forEach((rel, state) -> {
            BlockState now = got.blocks.get(rel);
            if (now != state) {
                problems.add("the block at " + str(rel) + " is " + now + ", not " + state);
            }
        });
        Set<Vec3i> positions = new TreeSet<>(Comparator.comparingInt(Vec3i::getY)
                .thenComparingInt(Vec3i::getX)
                .thenComparingInt(Vec3i::getZ));
        positions.addAll(want.blockEntities.keySet());
        positions.addAll(got.blockEntities.keySet());
        for (Vec3i rel : positions) {
            CompoundTag before = want.blockEntities.get(rel);
            CompoundTag now = got.blockEntities.get(rel);
            if (now == null) {
                problems.add("the block entity at " + str(rel) + " is gone: it was " + cut(before));
            } else if (before == null) {
                problems.add("the block entity at " + str(rel) + " wasn't there before: " + cut(now));
            } else if (!before.equals(now)) {
                problems.add("the block entity at " + str(rel) + " changed: " + tagDiff(before, now));
            }
        }
        Set<UUID> ids = new HashSet<>(want.entities.keySet());
        ids.addAll(got.entities.keySet());
        for (UUID id : ids) {
            Thing before = want.entities.get(id);
            Thing now = got.entities.get(id);
            if (now == null) {
                problems.add("the " + before.type + " " + id + " isn't on the ship");
            } else if (before == null) {
                problems.add("the " + now.type + " " + id + " wasn't on the ship before");
            } else {
                if (now.at.distanceTo(before.at) > 1.0E-6) {
                    problems.add("the " + now.type + " " + id + " is at " + now.at + " in the box, not " + before.at);
                }
                if (!before.data.equals(now.data)) {
                    problems.add("the " + now.type + " " + id + " changed: " + tagDiff(before.data, now.data));
                }
            }
        }
    }

    /** The keys of two compounds whose values differ, with both values. */
    private static String tagDiff(CompoundTag before, CompoundTag now) {
        Set<String> keys = new TreeSet<>(before.getAllKeys());
        keys.addAll(now.getAllKeys());
        List<String> changes = new ArrayList<>();
        for (String key : keys) {
            Tag a = before.get(key);
            Tag b = now.get(key);
            if (!Objects.equals(a, b)) {
                changes.add(key + " was " + cut(a) + ", is " + cut(b));
            }
        }
        return String.join("; ", changes);
    }

    private static String cut(Tag tag) {
        if (tag == null) {
            return "absent";
        }
        String s = tag.toString();
        return s.length() > 400 ? s.substring(0, 400) + "..." : s;
    }

    private static String str(Vec3i v) {
        return "[" + v.getX() + ", " + v.getY() + ", " + v.getZ() + "]";
    }

    private static String describe(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()) + " " + e.getUUID() + " at "
                + e.position() + " in " + e.level().dimension().location()
                + (e instanceof ItemEntity item ? ", holding " + item.getItem() : "");
    }

    private static String summarise(List<String> problems) {
        int shown = Math.min(problems.size(), 8);
        String s = String.join("\n  ", problems.subList(0, shown));
        return problems.size() + " problem" + (problems.size() == 1 ? "" : "s") + ":\n  " + s
                + (problems.size() > shown ? "\n  ..." : "");
    }
}
