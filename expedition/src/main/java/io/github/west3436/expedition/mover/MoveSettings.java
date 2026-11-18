package io.github.west3436.expedition.mover;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * How a move runs: how much work it may do in one server tick, who is told of it, and what may
 * refuse it.
 *
 * <p><b>The tick budget.</b> A move counts its work in units: {@link #POSITION} for each position of
 * the box it looks at, {@link #BLOCK} more for each block it carries, {@link #BLOCK_ENTITY} more for
 * each block entity, {@link #ENTITY} for each entity, and {@link #SETTLE} for each position on the
 * faces of the two boxes whose neighbours it updates. In each tick the moves in progress share
 * {@code workPerTick} units and {@code nanosPerTick} of wall time, whichever runs out first, and
 * always make some progress. With the defaults, a full box of 8 blocks a side moves in one tick,
 * one of 16 in about 3, one of 32 in about 10 and one of 64 in about 53, close to 3 seconds,
 * counted once the hand-over is ready; an empty box takes about half as many. The last tick of
 * carrying leaves room for the entities and the hand-over's completion (see {@link MoveJob}). #8
 * measured what the defaults cost, and #69 and #70 measured them again.
 *
 * @param workPerTick the work units all moves together may spend in one tick, at least 1
 * @param nanosPerTick the wall time all moves together may spend in one tick, at least 1
 * @param handover who else is told of each move, and may hold it until ready
 * @param checks what else may refuse a move whole, asked after the mover's own refusals ({@link
 *     MoveRefusal})
 */
public record MoveSettings(int workPerTick, long nanosPerTick, RelocationHandover handover, List<MoveCheck> checks) {
    /** Work for looking at one position of the box. */
    public static final int POSITION = 1;

    /** Work for carrying one block: a write at each end. */
    public static final int BLOCK = 4;

    /** Work for carrying one block entity's saved state. */
    public static final int BLOCK_ENTITY = 32;

    /** Work for carrying one entity, with its passengers. */
    public static final int ENTITY = 32;

    /**
     * Work for updating the neighbours of one position on the faces of both boxes. #8 measured it at
     * about 32 units' worth; since #69 tells only the neighbours outside the boxes, it costs about
     * as much as carrying a block, 5 to 8 units (#70).
     */
    public static final int SETTLE = 8;

    /**
     * 32,768 units and 10 ms a tick, a fifth of a tick's 50 ms; ZAS³'s hand-over ({@link
     * ZasRelocationHandover}), which carries the cabin air along; and no checks.
     */
    public static final MoveSettings DEFAULT =
            new MoveSettings(32_768, 10_000_000L, ZasRelocationHandover.INSTANCE, List.of());

    /**
     * Checks the arguments and copies the checks.
     *
     * @throws IllegalArgumentException if a budget is below 1
     * @throws NullPointerException if an argument is null
     */
    public MoveSettings {
        if (workPerTick < 1 || nanosPerTick < 1) {
            throw new IllegalArgumentException("a tick budget is below 1: " + workPerTick + ", " + nanosPerTick);
        }
        Objects.requireNonNull(handover, "handover");
        checks = List.copyOf(checks);
    }

    /**
     * These settings with another budget.
     *
     * @param workPerTick work units a tick
     * @param nanosPerTick wall time a tick
     * @return the settings
     */
    public MoveSettings withBudget(int workPerTick, long nanosPerTick) {
        return new MoveSettings(workPerTick, nanosPerTick, handover, checks);
    }

    /**
     * These settings with another hand-over.
     *
     * @param handover the hand-over
     * @return the settings
     */
    public MoveSettings withHandover(RelocationHandover handover) {
        return new MoveSettings(workPerTick, nanosPerTick, handover, checks);
    }

    /**
     * These settings for a move a player started: with a {@link ClaimCheck} for them added to the
     * checks, so the move is refused with {@link MoveRefusal#CLAIMED} where they may not build. A
     * move with no player, such as one from the console, has no such check.
     *
     * @param player who started the move, or null if no player did
     * @return the settings, or these settings if {@code player} is null
     */
    public MoveSettings startedBy(@Nullable ServerPlayer player) {
        if (player == null) {
            return this;
        }
        List<MoveCheck> withClaims = new ArrayList<>(checks);
        withClaims.add(new ClaimCheck(player));
        return withChecks(withClaims);
    }

    /**
     * These settings with other checks.
     *
     * @param checks the checks
     * @return the settings
     */
    public MoveSettings withChecks(List<MoveCheck> checks) {
        return new MoveSettings(workPerTick, nanosPerTick, handover, checks);
    }
}
