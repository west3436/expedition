package io.github.west3436.expedition.mover;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
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
 * <p>Before a move starts, its chunks at both ends, and a block beyond, must be loaded, and the
 * entities of the boxes' own chunks too, and it must not overlap a move in progress. These are the
 * caller's to check; #6 makes them refusals.
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
     * @throws IllegalStateException if a chunk or its entities at either end aren't loaded, or the
     *     move overlaps one in progress
     */
    public static MoveJob start(MoveRequest request) {
        return start(request, MoveSettings.DEFAULT);
    }

    /**
     * Starts a move. The checks are asked first: a refusal returns a job that is already done, with
     * nothing changed and no hand-over begun. Otherwise the hand-over begins, and as much of the move
     * as this tick's budget allows is done before this returns.
     *
     * @param request what to move, and where
     * @param settings the budget, the hand-over and the checks
     * @return the move
     * @throws IllegalArgumentException if the move goes nowhere, or a box leaves its level's build
     *     height
     * @throws IllegalStateException if a chunk or its entities at either end aren't loaded, or the
     *     move overlaps one in progress
     */
    public static MoveJob start(MoveRequest request, MoveSettings settings) {
        MinecraftServer server = request.from().getServer();
        if (!server.isSameThread()) {
            throw new IllegalStateException("ShipMover.start off the server thread");
        }
        Optional<String> refusal = firstRefusal(request, settings);
        if (refusal.isEmpty()) {
            requireMovable(request);
        }
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

    /** The first refusal any check gives, or empty. */
    static Optional<String> firstRefusal(MoveRequest request, MoveSettings settings) {
        for (MoveCheck check : settings.checks()) {
            Optional<String> refusal = check.check(request);
            if (refusal.isPresent()) {
                return refusal;
            }
        }
        return Optional.empty();
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

    /** Throws if the mover can't carry out a request at all. */
    private static void requireMovable(MoveRequest request) {
        Vec3i offset = request.offset();
        if (request.sameLevel() && offset.equals(Vec3i.ZERO)) {
            throw new IllegalArgumentException("a move that goes nowhere: " + request);
        }
        requireInside(request.from(), request.box());
        requireInside(request.to(), request.destination());
        requireLoaded(request.from(), request.box());
        requireLoaded(request.to(), request.destination());
        for (MoveJob job : JOBS) {
            if (job.overlaps(request.from(), request.box()) || job.overlaps(request.to(), request.destination())) {
                throw new IllegalStateException(request + " overlaps " + job);
            }
        }
    }

    private static void requireInside(ServerLevel level, BoundingBox box) {
        if (level.isOutsideBuildHeight(box.minY()) || level.isOutsideBuildHeight(box.maxY())) {
            throw new IllegalArgumentException(
                    box + " leaves the build height of " + level.dimension().location());
        }
    }

    private static void requireLoaded(ServerLevel level, BoundingBox box) {
        for (ChunkPos pos : chunks(box)) {
            if (!level.hasChunk(pos.x, pos.z)) {
                throw new IllegalStateException(
                        "chunk " + pos + " of " + level.dimension().location() + " isn't loaded, for a move of " + box);
            }
        }
        // A chunk's entities load after its blocks; a move before then would leave them behind.
        for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) {
            for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) {
                if (!level.areEntitiesLoaded(ChunkPos.asLong(x, z))) {
                    throw new IllegalStateException("the entities of chunk [" + x + ", " + z + "] of "
                            + level.dimension().location() + " aren't loaded, for a move of " + box);
                }
            }
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
                job.refuse("server_stopping");
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
