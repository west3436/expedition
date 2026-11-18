package io.github.west3436.expedition.mover;

import javax.annotation.Nullable;

/**
 * What may still be spent in one server tick: work units and wall time. A move's own budget, from
 * its {@link MoveSettings}, sits within the server's, which every move in the tick shares, so many
 * moves at once cost no more a tick than one.
 */
final class TickBudget {
    /** How many steps go between two reads of the clock. */
    private static final int CLOCK_EVERY = 32;

    @Nullable
    private final TickBudget outer;

    private int work;
    private long nanos;
    private int steps;

    /**
     * A budget.
     *
     * @param work work units
     * @param nanos wall time
     * @param outer a budget this one sits within, which is spent along with it, or null
     */
    TickBudget(int work, long nanos, @Nullable TickBudget outer) {
        this.work = work;
        this.nanos = nanos;
        this.outer = outer;
    }

    /**
     * A budget that never runs out, for finishing every move when the server stops.
     *
     * @return the budget
     */
    static TickBudget unlimited() {
        return new TickBudget(Integer.MAX_VALUE, Long.MAX_VALUE, null);
    }

    /**
     * Spends work units, here and in the outer budget.
     *
     * @param units how many
     */
    void spend(int units) {
        work = units >= work ? 0 : work - units;
        if (outer != null) {
            outer.spend(units);
        }
    }

    /**
     * Spends wall time a move has used, here and in the outer budget.
     *
     * @param spent nanoseconds
     */
    void spendNanos(long spent) {
        nanos = spent >= nanos ? 0 : nanos - spent;
        if (outer != null) {
            outer.spendNanos(spent);
        }
    }

    /**
     * Whether this budget or the outer one has run out.
     *
     * @param since when the move asking began this tick's work, from {@link System#nanoTime()}; time
     *     since then counts as spent
     * @return true if no work or no time is left
     */
    boolean spent(long since) {
        if (work <= 0 || nanos <= 0) {
            return true;
        }
        if (outer != null && outer.spent(since)) {
            return true;
        }
        if (++steps % CLOCK_EVERY != 0 || nanos == Long.MAX_VALUE) {
            return false;
        }
        return System.nanoTime() - since >= nanos;
    }
}
