package io.github.west3436.expedition.mover;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * The mover: moves everything inside a box, every block, every block entity with its saved state,
 * and every entity, players included, to another place in the same level or in another one, and
 * leaves air behind. Ships don't turn.
 *
 * <p>{@link #start} begins a move and returns its {@link MoveJob}, whose {@link MoveJob#result()}
 * says how it ended. A small box moves within the call; a large one is carried over several server
 * ticks (see {@link MoveJob} for the phases). The moves in progress are worked at the end of every
 * server tick, first started first, and all of them are finished at once if the server stops.
 *
 * <p>In one tick a move spends no more than its own {@link MoveSettings} allow, and all moves
 * together no more than the server's budget, {@link #SERVER_WORK_PER_TICK} work units and {@link
 * #SERVER_NANOS_PER_TICK} of wall time, so many moves at once cost a tick no more than one.
 *
 * <p>A move that can't be honoured is refused whole, with a stable code ({@link MoveRefusal}), and
 * changes nothing at either end: a box over the server's {@link #ceiling()}, or a short hop over its
 * ceiling for one, an overlap with a move in progress, a destination outside its level's world
 * border, a chunk at either end (or a block beyond) or the entities of a box's chunks not loaded, a
 * block in the {@link ExpeditionTags#IMMOVABLE immovable} tag in the box, or something in the way at
 * the destination. A move never destroys a block: only air and replaceable blocks, such as grass and
 * snow layers, are written over. {@link #dryRun} lists every obstacle without moving anything.
 *
 * <p>Thread: server thread only.
 */
public final class ShipMover {
    static final Logger LOGGER = LogUtils.getLogger();

    /** The work units every move together may spend in one server tick: the default move's. */
    public static final int SERVER_WORK_PER_TICK = MoveSettings.DEFAULT.workPerTick();

    /** The wall time every move together may spend in one server tick: the default move's. */
    public static final long SERVER_NANOS_PER_TICK = MoveSettings.DEFAULT.nanosPerTick();

    private static final List<MoveJob> JOBS = new ArrayList<>();
    private static long nextId = 1;
    private static int budgetTick = Integer.MIN_VALUE;
    private static TickBudget budget;
    private static ShipCeiling ceiling = ShipCeiling.NONE;

    private ShipMover() {}

    /**
     * Listens for the server ticks that work the moves in progress.
     *
     * @param gameBus NeoForge's game event bus
     */
    public static void register(IEventBus gameBus) {
        gameBus.addListener(ShipMover::onServerTick);
        gameBus.addListener(ShipMover::onServerStopping);
    }

    /**
     * Starts a move with the default settings.
     *
     * @param request what to move, and where
     * @return the move
     * @throws IllegalArgumentException if the move goes nowhere, or a box leaves its level's build
     *     height
     * @throws IllegalStateException if called off the server thread
     */
    public static MoveJob start(MoveRequest request) {
        return start(request, MoveSettings.DEFAULT);
    }

    /**
     * Starts a move. The mover's own checks and then the settings' are asked first: a refusal
     * returns a job that is already done, with nothing changed and no hand-over begun. Otherwise the
     * hand-over begins, and as much of the move as this tick's budget allows is done before this
     * returns.
     *
     * @param request what to move, and where
     * @param settings the budget, the hand-over and the checks
     * @return the move
     * @throws IllegalArgumentException if the move goes nowhere, or a box leaves its level's build
     *     height
     * @throws IllegalStateException if called off the server thread
     */
    public static MoveJob start(MoveRequest request, MoveSettings settings) {
        MinecraftServer server = request.from().getServer();
        requireServerThread(server, "start");
        requireMovable(request);
        Optional<String> refusal = firstRefusal(request, settings, null);
        MoveJob job = new MoveJob(nextId++, request, settings);
        if (refusal.isPresent()) {
            job.refuse(refusal.get());
            return job;
        }
        job.begin();
        JOBS.add(job);
        TickBudget shared = serverBudget(server);
        if (!shared.spent(System.nanoTime())) {
            run(job, job.budget(server.getTickCount(), shared), server.getTickCount());
        }
        return job;
    }

    /**
     * Whether a position is in a move in progress, at either end.
     *
     * @param level the level
     * @param pos the position
     * @return true if a move holds it
     */
    public static boolean isMoving(ServerLevel level, BlockPos pos) {
        for (MoveJob job : JOBS) {
            if (job.holds(level, pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The moves in progress, first started first.
     *
     * @return a copy of the list
     */
    public static List<MoveJob> inProgress() {
        return List.copyOf(JOBS);
    }

    /**
     * A dry run of a move with the default settings: see {@link #dryRun(MoveRequest, MoveSettings)}.
     *
     * @param request what to move, and where
     * @return every obstacle, empty if the move would go ahead
     * @throws IllegalArgumentException if the move goes nowhere, or a box leaves its level's build
     *     height
     * @throws IllegalStateException if called off the server thread
     */
    public static DryRun dryRun(MoveRequest request) {
        return dryRun(request, MoveSettings.DEFAULT);
    }

    /**
     * Asks every check {@link #start} would, and lists everything that would refuse the move: each
     * block and entity in the way, each immovable block, each chunk not loaded. It changes nothing,
     * loads no chunk and begins no hand-over. Its first obstacle's code is the refusal {@link #start}
     * would give now; the move may still be refused later, if the world changes before it starts.
     *
     * @param request what to move, and where
     * @param settings the settings whose checks are asked after the mover's own
     * @return every obstacle, empty if the move would go ahead
     * @throws IllegalArgumentException if the move goes nowhere, or a box leaves its level's build
     *     height
     * @throws IllegalStateException if called off the server thread
     */
    public static DryRun dryRun(MoveRequest request, MoveSettings settings) {
        requireServerThread(request.from().getServer(), "dryRun");
        requireMovable(request);
        return new DryRun(ObstacleScan.find(request, settings, JOBS, null, true));
    }

    /**
     * The server's ceiling on a ship's box. The mod sets it from its server settings when it loads.
     *
     * @return the ceiling; {@link ShipCeiling#NONE} until one is set
     */
    public static ShipCeiling ceiling() {
        return ceiling;
    }

    /**
     * Sets the server's ceiling on a ship's box, which every move from now on is checked against. The
     * mod sets the one made from its server settings, which follows a reloaded config by itself; a
     * caller that sets another, such as a benchmark lifting the ceiling, puts the old one back when it
     * is done.
     *
     * @param ceiling the ceiling, or {@link ShipCeiling#NONE}
     */
    public static void setCeiling(ShipCeiling ceiling) {
        ShipMover.ceiling = Objects.requireNonNull(ceiling, "ceiling");
    }

    /**
     * The first refusal of the mover's own checks or the settings', or empty.
     *
     * @param self the job being checked again once its hand-over is ready, so it doesn't count as a
     *     move it overlaps; null when the move is starting
     */
    static Optional<String> firstRefusal(MoveRequest request, MoveSettings settings, MoveJob self) {
        List<Obstacle> first = ObstacleScan.find(request, settings, JOBS, self, false);
        return first.isEmpty() ? Optional.empty() : Optional.of(first.get(0).code());
    }

    /** The chunks a box spans, and those a block beyond it. */
    static List<ChunkPos> chunks(BoundingBox box) {
        List<ChunkPos> chunks = new ArrayList<>();
        for (int x = (box.minX() - 1) >> 4; x <= (box.maxX() + 1) >> 4; x++) {
            for (int z = (box.minZ() - 1) >> 4; z <= (box.maxZ() + 1) >> 4; z++) {
                chunks.add(new ChunkPos(x, z));
            }
        }
        return chunks;
    }

    private static void requireServerThread(MinecraftServer server, String what) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("ShipMover." + what + " off the server thread");
        }
    }

    /** Throws if the request makes no sense: no world could let it go ahead. */
    private static void requireMovable(MoveRequest request) {
        Vec3i offset = request.offset();
        if (request.sameLevel() && offset.equals(Vec3i.ZERO)) {
            throw new IllegalArgumentException("a move that goes nowhere: " + request);
        }
        requireInside(request.from(), request.box());
        requireInside(request.to(), request.destination());
    }

    private static void requireInside(ServerLevel level, BoundingBox box) {
        if (level.isOutsideBuildHeight(box.minY()) || level.isOutsideBuildHeight(box.maxY())) {
            throw new IllegalArgumentException(
                    box + " leaves the build height of " + level.dimension().location());
        }
    }

    /** This tick's server budget, shared by every move worked in it; new when the tick is. */
    private static TickBudget serverBudget(MinecraftServer server) {
        if (budgetTick != server.getTickCount() || budget == null) {
            budgetTick = server.getTickCount();
            budget = new TickBudget(SERVER_WORK_PER_TICK, SERVER_NANOS_PER_TICK, null);
        }
        return budget;
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (JOBS.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        TickBudget shared = serverBudget(server);
        for (MoveJob job : List.copyOf(JOBS)) {
            if (shared.spent(System.nanoTime())) {
                break;
            }
            if (!job.waiting()) {
                run(job, job.budget(server.getTickCount(), shared), server.getTickCount());
            }
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        // A move left half done would be saved half done, so every move ends now, whatever it costs.
        int tick = event.getServer().getTickCount();
        for (MoveJob job : List.copyOf(JOBS)) {
            if (job.waiting()) {
                job.refuse(MoveRefusal.SERVER_STOPPING);
                JOBS.remove(job);
                continue;
            }
            while (!job.isDone()) {
                run(job, TickBudget.unlimited(), tick);
            }
        }
        JOBS.clear();
    }

    private static void run(MoveJob job, TickBudget budget, int tick) {
        try {
            job.work(budget, tick);
        } catch (RuntimeException e) {
            LOGGER.error("The move {} failed part-way through", job, e);
            job.fail(e);
        }
        if (job.isDone()) {
            JOBS.remove(job);
        }
    }
}
