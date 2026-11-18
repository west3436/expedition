package io.github.west3436.expedition.mover;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * One move in progress, from {@link ShipMover#start} until its {@link #result()} completes. It runs in
 * phases, each of which may span several server ticks:
 *
 * <ol>
 *   <li><b>Waiting:</b> the hand-over has begun, and the job waits for it to be ready. Then it asks
 *       its checks again; a refusal cancels the hand-over, and nothing has changed.
 *   <li><b>Carrying:</b> position by position, in {@link BoxWalk} order, each block, its block
 *       entity's saved state and its scheduled ticks go from the source to the destination in one
 *       step, with {@link QuietBlocks}. At every tick boundary each block is at exactly one end, so
 *       nothing is lost or made twice whatever the world does between ticks. When the last position
 *       is carried, in the same tick, the entities follow and the hand-over completes.
 *   <li><b>Settling:</b> position by position over the faces of both boxes, the neighbours hear of
 *       the change ({@link QuietBlocks#settle}).
 * </ol>
 *
 * <p>Both ends' chunks, and the chunks a block beyond them, are held loaded for the whole move.
 */
public final class MoveJob {
    /** Holds a move's chunks loaded; its value is the job's number, so two moves never share one. */
    private static final TicketType<Long> MOVING = TicketType.create("expedition_move", Long::compare);

    private enum Phase {
        WAITING,
        CARRYING,
        SETTLING,
        DONE
    }

    private final long id;
    private final MoveRequest request;
    private final MoveSettings settings;
    private final Vec3i offset;
    private final BoundingBox destination;
    private final CompletableFuture<MoveResult> result = new CompletableFuture<>();
    private RelocationHandover.Ticket ticket;
    private final List<ChunkPos> heldFrom = new ArrayList<>();
    private final List<ChunkPos> heldTo = new ArrayList<>();
    private final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos there = new BlockPos.MutableBlockPos();

    private Phase phase = Phase.WAITING;
    private boolean ticketOpen;
    private BoxWalk carry;
    private BoxWalk shell;
    private LevelChunk fromChunk;
    private LevelChunk toChunk;
    private int mass;
    private int blocks;
    private int blockEntities;
    private int entities;
    private int entitiesLeft;
    private TickBudget budget;
    private int budgetTick = Integer.MIN_VALUE;
    private int firstTick = -1;
    private int lastTick;
    private long nanos;
    private boolean working;
    /** How the move ended, once it has: a refusal code, or empty if it moved. */
    private Optional<String> ended;

    MoveJob(long id, MoveRequest request, MoveSettings settings) {
        this.id = id;
        this.request = request;
        this.settings = settings;
        this.offset = request.offset();
        this.destination = request.destination();
    }

    /** Holds both ends' chunks loaded and begins the hand-over: nothing has changed yet. */
    void begin() {
        hold(request.from(), request.box(), heldFrom);
        hold(request.to(), destination, heldTo);
        ticket = settings.handover().begin(request.from(), request.box());
        ticketOpen = true;
    }

    /**
     * How the move runs.
     *
     * @return the settings it was started with
     */
    public MoveSettings settings() {
        return settings;
    }

    /**
     * What is being moved.
     *
     * @return the request
     */
    public MoveRequest request() {
        return request;
    }

    /**
     * How the move ends: it completes on the server thread once the neighbours at both ends have
     * been updated, or once the move is refused. It completes exceptionally only if the move failed
     * part-way through, which is a bug.
     *
     * @return the future, the same one every time
     */
    public CompletableFuture<MoveResult> result() {
        return result;
    }

    /**
     * Whether the move is over.
     *
     * @return true once {@link #result()} has completed
     */
    public boolean isDone() {
        return phase == Phase.DONE;
    }

    /**
     * Whether the job holds a position: it is in the box or the destination box, in their levels.
     *
     * @param level the level
     * @param pos the position
     * @return true while the move isn't over
     */
    boolean holds(ServerLevel level, BlockPos pos) {
        return !isDone()
                && ((level == request.from() && request.box().isInside(pos))
                        || (level == request.to() && destination.isInside(pos)));
    }

    /**
     * Whether either of the job's boxes overlaps a box.
     *
     * @param level the box's level
     * @param box the box
     * @return true while the move isn't over
     */
    boolean overlaps(ServerLevel level, BoundingBox box) {
        return !isDone()
                && ((level == request.from() && request.box().intersects(box))
                        || (level == request.to() && destination.intersects(box)));
    }

    /** Whether the job is waiting for its hand-over, and so needs no share of the tick's budget. */
    boolean waiting() {
        return phase == Phase.WAITING && ticket != null && !ticket.begun().isDone();
    }

    /**
     * The move's own budget for a tick, within the server's: new when the tick is, so that being
     * worked twice in one tick doesn't double it.
     *
     * @param tick the server's tick count
     * @param shared the server's budget for that tick
     * @return the budget
     */
    TickBudget budget(int tick, TickBudget shared) {
        if (budget == null || budgetTick != tick) {
            budgetTick = tick;
            budget = new TickBudget(settings.workPerTick(), settings.nanosPerTick(), shared);
        }
        return budget;
    }

    /**
     * Does as much of the move as the budget allows.
     *
     * @param budget this tick's budget, shared with the other moves
     * @param tick the server's tick count
     */
    void work(TickBudget budget, int tick) {
        if (firstTick < 0) {
            firstTick = tick;
        }
        lastTick = tick;
        long start = System.nanoTime();
        working = true;
        try {
            if (phase == Phase.WAITING) {
                await();
            }
            if (phase == Phase.CARRYING) {
                carry(budget, start);
            }
            if (phase == Phase.SETTLING) {
                settle(budget, start);
            }
        } finally {
            working = false;
            long spent = System.nanoTime() - start;
            nanos += spent;
            budget.spendNanos(spent);
            // The result is completed once this tick's time is counted in it.
            if (ended != null && !result.isDone()) {
                result.complete(resultOf(ended));
            }
        }
    }

    /** Ends the move with an error, after something threw part-way through. */
    void fail(Throwable error) {
        closeTicket();
        finish();
        result.completeExceptionally(error);
    }

    /** Refuses the move before anything changed. */
    void refuse(String code) {
        closeTicket();
        finish();
        end(Optional.of(code));
    }

    /** Ends the move: the result completes now, or, inside {@link #work}, once its time is counted. */
    private void end(Optional<String> refusal) {
        ended = refusal;
        if (!working) {
            result.complete(resultOf(refusal));
        }
    }

    private MoveResult resultOf(Optional<String> refusal) {
        return refusal.isPresent()
                ? MoveResult.refused(refusal.get(), ticks(), nanos)
                : new MoveResult(Optional.empty(), mass, blocks, blockEntities, entities, entitiesLeft, ticks(), nanos);
    }

    private void await() {
        if (!ticket.begun().isDone()) {
            return;
        }
        Optional<String> refusal;
        try {
            refusal = ticket.begun().join();
        } catch (CompletionException | CancellationException e) {
            ShipMover.LOGGER.warn("The relocation hand-over for {} failed; the move is refused", request, e);
            refusal = Optional.of(MoveRefusal.HANDOVER_FAILED);
        }
        if (refusal.isEmpty()) {
            refusal = ShipMover.firstRefusal(request, settings, this);
        }
        if (refusal.isPresent()) {
            refuse(refusal.get());
            return;
        }
        // The box's own order unless the boxes overlap, in which case each destination must be
        // emptied before it is written, and the whole carry is done in this one tick. The server's
        // ceiling for a short hop (ShipCeiling.hopExceeds) keeps that tick bounded.
        carry = request.overlaps() ? BoxWalk.ahead(request.box(), offset) : BoxWalk.topDown(request.box());
        phase = Phase.CARRYING;
    }

    private void carry(TickBudget budget, long start) {
        boolean oneTick = request.overlaps();
        while (carry.hasNext() && (oneTick || !budget.spent(start))) {
            carryOne(budget, carry.next(at));
        }
        if (carry.hasNext()) {
            return;
        }
        moveEntities(budget);
        ticketOpen = false;
        ticket.complete(request.to(), offset);
        shell = BoxWalk.shell(request.box());
        phase = Phase.SETTLING;
    }

    private void carryOne(TickBudget budget, BlockPos pos) {
        budget.spend(MoveSettings.POSITION);
        there.setWithOffset(pos, offset);
        ServerLevel from = request.from();
        ServerLevel to = request.to();
        LevelChunk source = fromChunk = chunk(from, pos, fromChunk);
        BlockState state = source.getBlockState(pos);
        LevelChunk target = toChunk = chunk(to, there, toChunk);
        BlockState replaced = target.getBlockState(there);
        if (state.isAir() && replaced.isAir()) {
            return;
        }
        budget.spend(MoveSettings.BLOCK);
        CompoundTag saved = state.hasBlockEntity() ? QuietBlocks.takeBlockEntity(from, source, pos) : null;
        QuietBlocks.moveTicks(from, pos, state, to, offset);
        QuietBlocks.set(from, source, pos, Blocks.AIR.defaultBlockState());
        if (replaced.hasBlockEntity()) {
            // Only a replaceable block, such as a snow layer, was here when the move began: the checks
            // refused anything else. It is written over, without drops.
            target.removeBlockEntity(there);
        }
        QuietBlocks.set(to, target, there, state);
        if (QuietBlocks.putBlockEntity(to, target, there, state, saved) != null) {
            budget.spend(MoveSettings.BLOCK_ENTITY);
            blockEntities++;
        }
        if (!state.isAir()) {
            blocks++;
            if (ShipSurvey.isSolid(state)) {
                mass++;
            }
        }
    }

    /**
     * Carries every entity whose feet are in the box, with its passengers, by the move's offset. It
     * runs once the blocks are all in place, in the same tick, so an entity arrives standing on what
     * it stood on.
     */
    private void moveEntities(TickBudget budget) {
        ServerLevel from = request.from();
        ServerLevel to = request.to();
        BoundingBox box = request.box();
        List<Entity> riders = from.getEntities(
                (Entity) null,
                AABB.of(box),
                e -> e.getRootVehicle() == e && !e.isRemoved() && box.isInside(e.blockPosition()));
        for (Entity entity : List.copyOf(riders)) {
            budget.spend(MoveSettings.ENTITY);
            Vec3 pos = entity.position().add(offset.getX(), offset.getY(), offset.getZ());
            boolean arrived;
            if (request.sameLevel()) {
                arrived = entity.teleportTo(to, pos.x, pos.y, pos.z, Set.of(), entity.getYRot(), entity.getXRot());
            } else {
                arrived = entity.changeDimension(new DimensionTransition(
                                to,
                                pos,
                                entity.getDeltaMovement(),
                                entity.getYRot(),
                                entity.getXRot(),
                                DimensionTransition.DO_NOTHING))
                        != null;
            }
            if (arrived) {
                entities++;
            } else {
                entitiesLeft++;
                ShipMover.LOGGER.warn("{} didn't go with the move of {}: its teleport was refused", entity, request);
            }
        }
    }

    private void settle(TickBudget budget, long start) {
        while (shell.hasNext() && !budget.spent(start)) {
            budget.spend(MoveSettings.SETTLE);
            BlockPos pos = shell.next(at);
            there.setWithOffset(pos, offset);
            QuietBlocks.settle(request.from(), pos);
            QuietBlocks.settle(request.to(), there);
        }
        if (shell.hasNext()) {
            return;
        }
        finish();
        end(Optional.empty());
    }

    /** Cancels the hand-over unless it has already been completed or cancelled. */
    private void closeTicket() {
        if (ticketOpen) {
            ticketOpen = false;
            ticket.cancel();
        }
    }

    private int ticks() {
        return firstTick < 0 ? 0 : lastTick - firstTick + 1;
    }

    private void finish() {
        phase = Phase.DONE;
        release(request.from(), heldFrom);
        release(request.to(), heldTo);
    }

    /** The loaded chunk that holds a position, reusing the last one when it is the same. */
    private static LevelChunk chunk(ServerLevel level, BlockPos pos, LevelChunk last) {
        int x = pos.getX() >> 4;
        int z = pos.getZ() >> 4;
        if (last != null && last.getPos().x == x && last.getPos().z == z) {
            return last;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
        if (chunk == null) {
            throw new IllegalStateException("chunk " + x + ", " + z + " of "
                    + level.dimension().location() + " unloaded during a move, though the move held it");
        }
        return chunk;
    }

    /** Holds the chunks of a box, and of a block beyond it, loaded: neighbour updates reach that far. */
    private void hold(ServerLevel level, BoundingBox box, List<ChunkPos> held) {
        for (ChunkPos pos : ShipMover.chunks(box)) {
            level.getChunkSource().addRegionTicket(MOVING, pos, 0, id);
            held.add(pos);
        }
    }

    private void release(ServerLevel level, List<ChunkPos> held) {
        for (ChunkPos pos : held) {
            level.getChunkSource().removeRegionTicket(MOVING, pos, 0, id);
        }
        held.clear();
    }

    @Override
    public String toString() {
        return "MoveJob[" + id + ", " + phase + ", " + request + "]";
    }
}
