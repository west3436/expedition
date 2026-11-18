package io.github.west3436.expedition.gametest;

import com.mojang.logging.LogUtils;
import io.github.west3436.expedition.mover.MoveJob;
import io.github.west3436.expedition.mover.MoveRequest;
import io.github.west3436.expedition.mover.MoveResult;
import io.github.west3436.expedition.mover.MoveSettings;
import io.github.west3436.expedition.mover.QuietBlocks;
import io.github.west3436.expedition.mover.ShipMover;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

/**
 * The move benchmark (#8): what a move costs by the size of its box. Boxes of 8, 16, 32 and 64
 * blocks a side, empty and full, are moved within the Overworld and from the Overworld into the
 * Nether with the mover's default settings, one move at a time, and for each the harness records
 * the ticks the move took, its wall time, the mover's work in its worst tick, the worst whole server
 * tick and the memory the server thread allocated.
 *
 * <p>A full box is a ship packed solid: stone bricks with some glass, iron and planks, a chest or a
 * furnace holding items at every fourth position along each axis (one position in 64), and a pig
 * for every four blocks of side, each in a two-block cavity.
 *
 * <p>The benchmark is in its own GameTest namespace, {@value #NAMESPACE}, which an ordinary GameTest
 * run never enables, so it costs CI's run nothing. {@code ./gradlew runGameTestServer -Pbench=true}
 * runs it alone, and the Move benchmark workflow does that on a CI runner. Each result is logged as
 * a line starting {@value #LINE}, comma-separated under the header {@link #HEADER}, and the whole
 * table is written to {@code move-bench.csv} in the run's game directory.
 *
 * <p>System properties: {@code expedition.bench.reps}, how many times each case runs (3), and
 * {@code expedition.bench.sides}, the box sides, comma-separated (8,16,32,64).
 */
@GameTestHolder(MoveBench.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class MoveBench {
    /** The benchmark's GameTest namespace, enabled only by {@code -Pbench=true}. */
    static final String NAMESPACE = "expedition_bench";

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The prefix of each result's log line. */
    static final String LINE = "MOVE_BENCH ";

    /** The columns of each result. Times are in milliseconds and memory in MiB. */
    static final String HEADER = "case,side,fill,dims,rep,positions,blocks,block_entities,entities,ticks,wall_ms,"
            + "work_ms,max_tick_work_ms,max_server_tick_ms,idle_server_tick_ms,alloc_mib,idle_alloc_mib_per_tick,"
            + "heap_delta_mib,gc_ms,worst_tick_at,worst_tick_gc_ms,max_tick_work_no_gc_ms";

    /** The prefix of the log line that lists a move's ticks: the mover's work and the GC pauses in each. */
    static final String TICKS = "MOVE_BENCH_TICKS ";

    /** The test's limit in game ticks: far more than the benchmark needs, which is a few thousand. */
    private static final int TIMEOUT = 400_000;

    /** Keeps the benchmark's areas loaded and their entities ticking until it releases them. */
    private static final TicketType<ChunkPos> HELD =
            TicketType.create("expedition_bench_held", Comparator.comparingLong(ChunkPos::toLong));

    /** Where each box's lowest corner is built, far from the GameTest grid. */
    private static final BlockPos SOURCE = new BlockPos(20_480, 100, 20_480);

    /** Where a move within the Overworld takes it: 256 blocks east. */
    private static final BlockPos SAME = SOURCE.offset(256, 0, 0);

    /** Where a move into the Nether takes it, clear of the Nether's floor and roof. */
    private static final BlockPos NETHER = new BlockPos(20_480, 40, 20_480);

    /** Ticks between building a box and moving it, for the light and the garbage collector to settle. */
    private static final int IDLE = 10;

    /** The idle ticks the baseline is measured over: the last ones, after the collection. */
    private static final int BASELINE = 6;

    private static final double MS = 1.0e6;
    private static final double MIB = 1024.0 * 1024.0;

    /** The bench running now, which the tick listeners drive; null between runs. */
    private static volatile Bench current;

    private static boolean listening;

    private MoveBench() {}

    /**
     * Runs every case in turn, a warm-up first, and succeeds once the results are written. A move
     * that doesn't carry what was built fails it.
     */
    @GameTest(template = "empty", batch = "move_bench", timeoutTicks = TIMEOUT)
    public static void move_cost_by_box_size(GameTestHelper h) {
        listen();
        Bench bench = new Bench(
                h.getLevel().getServer(),
                h.getLevel(),
                level(h, Level.NETHER),
                sides(),
                Integer.getInteger("expedition.bench.reps", 3));
        current = bench;
        h.succeedWhen(() -> {
            if (bench.failure != null) {
                current = null;
                throw new GameTestAssertException("the benchmark failed: " + bench.failure);
            }
            h.assertTrue(bench.finished, "the benchmark is at " + bench.progress());
        });
    }

    private static int[] sides() {
        String sides = System.getProperty("expedition.bench.sides", "8,16,32,64");
        return Arrays.stream(sides.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .mapToInt(Integer::parseInt)
                .toArray();
    }

    private static ServerLevel level(GameTestHelper h, ResourceKey<Level> dimension) {
        ServerLevel level = h.getLevel().getServer().getLevel(dimension);
        if (level == null) {
            throw new GameTestAssertException("no " + dimension.location());
        }
        return level;
    }

    /** Listens for the server's ticks, once: the first listener runs first, the last one last. */
    private static synchronized void listen() {
        if (listening) {
            return;
        }
        listening = true;
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ServerTickEvent.Pre.class, e -> {
            Bench bench = current;
            if (bench != null) {
                bench.tickStart = System.nanoTime();
            }
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ServerTickEvent.Post.class, e -> {
            Bench bench = current;
            if (bench == null) {
                return;
            }
            try {
                bench.tick();
            } catch (RuntimeException ex) {
                LOGGER.error("The move benchmark failed", ex);
                bench.failure = ex.toString();
            }
            if (bench.finished || bench.failure != null) {
                bench.release();
                current = null;
            }
        });
    }

    // ---- The cases ----------------------------------------------------------------------

    /** One move to measure. */
    private record Case(int side, boolean full, boolean nether, int rep, boolean warmup) {
        String name() {
            return (warmup ? "warmup-" : "") + side + "-" + (full ? "full" : "empty") + "-"
                    + (nether ? "nether" : "same");
        }

        BoundingBox box() {
            return cube(SOURCE, side);
        }

        BoundingBox destination() {
            return cube(nether ? NETHER : SAME, side);
        }
    }

    private static BoundingBox cube(BlockPos corner, int side) {
        return BoundingBox.fromCorners(corner, corner.offset(side - 1, side - 1, side - 1));
    }

    private static List<Case> cases(int[] sides, int reps) {
        List<Case> cases = new ArrayList<>();
        // The warm-up brings the JIT to the mover's code; its rows are logged but not summarised.
        for (int i = 0; i < 2; i++) {
            cases.add(new Case(32, true, false, i, true));
            cases.add(new Case(32, true, true, i, true));
        }
        for (int side : sides) {
            for (boolean full : new boolean[] {false, true}) {
                for (boolean nether : new boolean[] {false, true}) {
                    for (int rep = 1; rep <= reps; rep++) {
                        cases.add(new Case(side, full, nether, rep, false));
                    }
                }
            }
        }
        return cases;
    }

    // ---- The run ------------------------------------------------------------------------

    private enum Phase {
        LOADING,
        BUILD,
        IDLE,
        MOVING,
        DONE
    }

    /** The benchmark as it goes, worked at the end of every server tick. */
    private static final class Bench {
        private final MinecraftServer server;
        private final ServerLevel overworld;
        private final ServerLevel nether;
        private final List<Case> cases;
        private final List<String> rows = new ArrayList<>();
        private final int largest;
        private final List<ChunkPos> heldOverworld = new ArrayList<>();
        private final List<ChunkPos> heldNether = new ArrayList<>();

        volatile boolean finished;
        volatile String failure;
        long tickStart;

        private Phase phase = Phase.LOADING;
        private int index;
        private int idle;
        private int built;
        private MoveJob job;
        private long startedAt;
        private long lastWork;
        private long maxTickWork;
        private long lastGc;
        private final List<Long> tickWork = new ArrayList<>();
        private final List<Long> tickGc = new ArrayList<>();
        private long maxServerTick;
        private long allocAtStart;
        private long heapAtStart;
        private final List<Long> idleTicks = new ArrayList<>();
        private final List<Long> idleAlloc = new ArrayList<>();
        private long lastAlloc;

        Bench(MinecraftServer server, ServerLevel overworld, ServerLevel nether, int[] sides, int reps) {
            this.server = server;
            this.overworld = overworld;
            this.nether = nether;
            this.cases = cases(sides, reps);
            this.largest = Math.max(32, Arrays.stream(sides).max().orElse(32));
        }

        String progress() {
            return phase
                    + (index < cases.size()
                            ? " " + cases.get(index).name() + " (" + (index + 1) + "/" + cases.size() + ")"
                            : "");
        }

        /** One server tick's work, at the very end of the tick. */
        void tick() {
            long now = System.nanoTime();
            long serverTick = now - tickStart;
            switch (phase) {
                case LOADING -> {
                    if (hold(overworld, cube(SOURCE, largest), heldOverworld)
                            & hold(overworld, cube(SAME, largest), heldOverworld)
                            & hold(nether, cube(NETHER, largest), heldNether)) {
                        LOGGER.info(
                                "Move benchmark: {} cases on Java {}, {} processors, {} MiB of heap",
                                cases.size(),
                                System.getProperty("java.version"),
                                Runtime.getRuntime().availableProcessors(),
                                Runtime.getRuntime().maxMemory() / (1024 * 1024));
                        LOGGER.info("{}{}", LINE, HEADER);
                        phase = Phase.BUILD;
                    }
                }
                case BUILD -> {
                    Case c = cases.get(index);
                    wipe(overworld, c.box());
                    wipe(c.nether ? nether : overworld, c.destination());
                    built = c.full ? build(overworld, SOURCE, c.side) : 0;
                    idle = 0;
                    idleTicks.clear();
                    idleAlloc.clear();
                    phase = Phase.IDLE;
                }
                case IDLE -> {
                    idle++;
                    if (idle == IDLE - BASELINE) {
                        System.gc();
                    } else if (idle > IDLE - BASELINE) {
                        idleTicks.add(serverTick);
                        long alloc = allocated();
                        idleAlloc.add(alloc - lastAlloc);
                    }
                    lastAlloc = allocated();
                    if (idle >= IDLE) {
                        begin(cases.get(index), now);
                    }
                }
                case MOVING -> {
                    maxServerTick = Math.max(maxServerTick, serverTick);
                    sample();
                    if (job.isDone()) {
                        end(cases.get(index), System.nanoTime());
                    }
                }
                case DONE -> {}
            }
        }

        /** Starts the case's move, and counts what the call did as its first tick. */
        private void begin(Case c, long tickEnd) {
            ServerLevel to = c.nether ? nether : overworld;
            BlockPos origin = c.nether ? NETHER : SAME;
            Runtime rt = Runtime.getRuntime();
            heapAtStart = rt.totalMemory() - rt.freeMemory();
            allocAtStart = allocated();
            lastWork = 0;
            maxTickWork = 0;
            tickWork.clear();
            tickGc.clear();
            lastGc = gcPauses();
            startedAt = System.nanoTime();
            job = ShipMover.start(new MoveRequest(overworld, c.box(), to, origin), MoveSettings.DEFAULT);
            // The start tick: the server's tick so far, and the work the call did.
            maxServerTick = System.nanoTime() - tickStart;
            sample();
            phase = Phase.MOVING;
            if (job.isDone()) {
                end(c, System.nanoTime());
            }
        }

        /** The mover's work since the last sample: one tick's. */
        private void sample() {
            long work = workSoFar(job);
            long gc = gcPauses();
            maxTickWork = Math.max(maxTickWork, work - lastWork);
            tickWork.add(work - lastWork);
            tickGc.add(gc - lastGc);
            lastWork = work;
            lastGc = gc;
        }

        private void end(Case c, long now) {
            long wall = now - startedAt;
            long alloc = allocated() - allocAtStart;
            Runtime rt = Runtime.getRuntime();
            long heap = rt.totalMemory() - rt.freeMemory() - heapAtStart;
            MoveResult result = job.result().join();
            if (!result.moved()) {
                throw new IllegalStateException(c.name() + " was refused: " + result.refusal());
            }
            if (result.blocks() != built || result.entitiesLeft() != 0) {
                throw new IllegalStateException(c.name() + " built " + built + " blocks but carried " + result);
            }
            int worst = 0;
            long maxWithoutGc = 0;
            for (int i = 0; i < tickWork.size(); i++) {
                if (tickWork.get(i) > tickWork.get(worst)) {
                    worst = i;
                }
                if (tickGc.get(i) == 0) {
                    maxWithoutGc = Math.max(maxWithoutGc, tickWork.get(i));
                }
            }
            String row = String.join(
                    ",",
                    c.name(),
                    Integer.toString(c.side),
                    c.full ? "full" : "empty",
                    c.nether ? "overworld-nether" : "overworld",
                    Integer.toString(c.rep),
                    Integer.toString(c.side * c.side * c.side),
                    Integer.toString(result.blocks()),
                    Integer.toString(result.blockEntities()),
                    Integer.toString(result.entities()),
                    Integer.toString(result.ticks()),
                    fmt(wall / MS),
                    fmt(result.nanos() / MS),
                    fmt(maxTickWork / MS),
                    fmt(maxServerTick / MS),
                    fmt(median(idleTicks) / MS),
                    fmt(alloc / MIB),
                    fmt(median(idleAlloc) / MIB),
                    fmt(heap / MIB),
                    fmt(sum(tickGc) / MS),
                    Integer.toString(worst + 1),
                    fmt(tickGc.get(worst) / MS),
                    fmt(maxWithoutGc / MS));
            LOGGER.info("{}{}", LINE, row);
            StringBuilder ticks =
                    new StringBuilder(c.name()).append(',').append(c.rep).append(',');
            for (int i = 0; i < tickWork.size(); i++) {
                ticks.append(i == 0 ? "" : ";").append(fmt(tickWork.get(i) / MS));
                if (tickGc.get(i) > 0) {
                    ticks.append("(gc ").append(fmt(tickGc.get(i) / MS)).append(')');
                }
            }
            LOGGER.info("{}{}", TICKS, ticks);
            if (!c.warmup) {
                rows.add(row);
            }
            job = null;
            index++;
            if (index < cases.size()) {
                phase = Phase.BUILD;
            } else {
                Case last = cases.get(cases.size() - 1);
                wipe(overworld, cube(SOURCE, largest));
                wipe(overworld, cube(SAME, largest));
                wipe(nether, cube(NETHER, largest));
                write();
                phase = Phase.DONE;
                finished = true;
                LOGGER.info("Move benchmark: done after {}", last.name());
            }
        }

        private void write() {
            Path file = FMLPaths.GAMEDIR.get().resolve("move-bench.csv");
            List<String> lines = new ArrayList<>();
            lines.add(HEADER);
            lines.addAll(rows);
            try {
                Files.write(file, lines, StandardCharsets.UTF_8);
                LOGGER.info("Move benchmark: wrote {} rows to {}", rows.size(), file.toAbsolutePath());
            } catch (IOException e) {
                throw new IllegalStateException("couldn't write " + file, e);
            }
        }

        void release() {
            for (ChunkPos pos : heldOverworld) {
                overworld.getChunkSource().removeRegionTicket(HELD, pos, 2, pos);
            }
            for (ChunkPos pos : heldNether) {
                nether.getChunkSource().removeRegionTicket(HELD, pos, 2, pos);
            }
            heldOverworld.clear();
            heldNether.clear();
        }

        /** Asks for the chunks around a box to be loaded; true once they are, with their entities. */
        private boolean hold(ServerLevel level, BoundingBox box, List<ChunkPos> held) {
            boolean ready = true;
            for (int x = (box.minX() - 1) >> 4; x <= (box.maxX() + 1) >> 4; x++) {
                for (int z = (box.minZ() - 1) >> 4; z <= (box.maxZ() + 1) >> 4; z++) {
                    ChunkPos c = new ChunkPos(x, z);
                    if (!held.contains(c)) {
                        level.getChunkSource().addRegionTicket(HELD, c, 2, c);
                        held.add(c);
                    }
                    ready &= level.hasChunk(x, z) && level.areEntitiesLoaded(c.toLong());
                }
            }
            return ready;
        }
    }

    // ---- Building and clearing ----------------------------------------------------------

    /**
     * Builds a full ship with its lowest corner at {@code corner}, quietly.
     *
     * @return the blocks built that aren't air
     */
    private static int build(ServerLevel level, BlockPos corner, int side) {
        List<BlockPos> cavities = new ArrayList<>();
        int pigs = Math.max(2, side / 4);
        for (int i = 0; i < pigs; i++) {
            cavities.add(corner.offset(
                    1 + (i * 5) % (side - 2), 1 + (i * 3) % (side - 3), 1 + (i * 7 + side / 2) % (side - 2)));
        }
        int blocks = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                LevelChunk chunk = level.getChunk((corner.getX() + x) >> 4, (corner.getZ() + z) >> 4);
                for (int y = 0; y < side; y++) {
                    p.set(corner.getX() + x, corner.getY() + y, corner.getZ() + z);
                    if (cavities.contains(p) || cavities.contains(p.below())) {
                        continue;
                    }
                    BlockState state;
                    boolean container = x % 4 == 2 && y % 4 == 2 && z % 4 == 2;
                    if (container) {
                        state = ((x + y + z) / 4) % 2 == 0
                                ? Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH)
                                : Blocks.FURNACE
                                        .defaultBlockState()
                                        .setValue(AbstractFurnaceBlock.FACING, Direction.SOUTH);
                    } else if ((x + z) % 7 == 0) {
                        state = Blocks.GLASS.defaultBlockState();
                    } else if (y % 5 == 0) {
                        state = Blocks.IRON_BLOCK.defaultBlockState();
                    } else if ((x * 3 + y + z) % 11 == 0) {
                        state = Blocks.OAK_PLANKS.defaultBlockState();
                    } else {
                        state = Blocks.STONE_BRICKS.defaultBlockState();
                    }
                    QuietBlocks.set(level, chunk, p, state);
                    blocks++;
                    if (container) {
                        BlockEntity be = QuietBlocks.putBlockEntity(level, chunk, p.immutable(), state, null);
                        if (be instanceof ChestBlockEntity chest) {
                            chest.setItem(0, new ItemStack(Items.DIAMOND, 7));
                            chest.setItem(4, new ItemStack(Items.IRON_SWORD));
                            chest.setItem(26, new ItemStack(Items.COBBLESTONE, 32));
                        } else if (be instanceof FurnaceBlockEntity furnace) {
                            furnace.setItem(0, new ItemStack(Items.RAW_IRON, 5));
                            furnace.setItem(1, new ItemStack(Items.COAL, 3));
                        }
                    }
                }
            }
        }
        for (BlockPos cavity : cavities) {
            Pig pig = EntityType.PIG.create(level);
            pig.moveTo(cavity.getX() + 0.5, cavity.getY(), cavity.getZ() + 0.5, 90, 0);
            pig.setNoAi(true);
            pig.setInvulnerable(true);
            pig.setCustomName(Component.literal("Passenger"));
            level.addFreshEntity(pig);
        }
        return blocks;
    }

    /** Takes every block and entity out of a box, quietly, so nothing drops. */
    private static void wipe(ServerLevel level, BoundingBox box) {
        for (Entity e : level.getEntities((Entity) null, AABB.of(box).inflate(2), e -> !(e instanceof Player))) {
            e.discard();
        }
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                LevelChunk chunk = level.getChunk(x >> 4, z >> 4);
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    p.set(x, y, z);
                    QuietBlocks.takeBlockEntity(level, chunk, p);
                    QuietBlocks.set(level, chunk, p, Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    // ---- Measuring ----------------------------------------------------------------------

    private static final Field NANOS;
    private static final Method ALLOCATED;

    static {
        try {
            NANOS = MoveJob.class.getDeclaredField("nanos");
            NANOS.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
        Method allocated;
        try {
            allocated = Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes");
        } catch (ReflectiveOperationException | LinkageError e) {
            allocated = null;
        }
        ALLOCATED = allocated;
    }

    /** The wall time a move has spent working so far, summed over its ticks. */
    private static long workSoFar(MoveJob job) {
        try {
            return NANOS.getLong(job);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The bytes the server thread has allocated so far, or 0 if the JVM can't say. */
    private static long allocated() {
        if (ALLOCATED == null) {
            return 0;
        }
        try {
            return (long) ALLOCATED.invoke(ManagementFactory.getThreadMXBean());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    /** The time the JVM has spent paused for garbage collection so far, leaving out concurrent work. */
    private static long gcPauses() {
        long millis = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (!gc.getName().contains("Concurrent") && gc.getCollectionTime() > 0) {
                millis += gc.getCollectionTime();
            }
        }
        return millis * 1_000_000L;
    }

    private static long sum(List<Long> values) {
        long total = 0;
        for (long v : values) {
            total += v;
        }
        return total;
    }

    private static double median(List<Long> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
