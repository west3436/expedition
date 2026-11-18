package io.github.west3436.expedition.mover;

import java.util.List;
import java.util.Objects;

/**
 * How a move runs: how much work it may do in one server tick, who is told of it, and what may
 * refuse it.
 *
 * <p><b>The tick budget.</b> A move counts its work in units: {@link #POSITION} for each position of
 * the box it looks at, {@link #BLOCK} more for each block it carries, {@link #BLOCK_ENTITY} more for
 * each block entity, {@link #ENTITY} for each entity, and {@link #SETTLE} for each position on the
 * faces of the two boxes whose neighbours it updates. In each tick the moves in progress share
 * {@code workPerTick} units and {@code nanosPerTick} of wall time, whichever runs out first, and
 * always make some progress. The defaults move a box of 16 blocks a side in one tick and one of 64
 * a side, full, in about two seconds; #8 measures what they cost.
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

    /** Work for updating the neighbours of one position on a face of either box. */
    public static final int SETTLE = 8;

    /** 32,768 units and 10 ms a tick, a fifth of a tick's 50 ms; no hand-over and no checks. */
    public static final MoveSettings DEFAULT =
            new MoveSettings(32_768, 10_000_000L, RelocationHandover.NONE, List.of());

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
     * These settings with other checks.
     *
     * @param checks the checks
     * @return the settings
     */
    public MoveSettings withChecks(List<MoveCheck> checks) {
        return new MoveSettings(workPerTick, nanosPerTick, handover, checks);
    }
}
