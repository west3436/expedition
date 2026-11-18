package io.github.west3436.expedition.mover;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A tick's budget, without the game. */
class TickBudgetTest {
    @Test
    void runsOutOfWork() {
        TickBudget budget = new TickBudget(10, Long.MAX_VALUE / 2, null);
        long now = System.nanoTime();
        budget.spend(4);
        assertFalse(budget.spent(now));
        budget.spend(6);
        assertTrue(budget.spent(now));
        budget.spend(100);
        assertTrue(budget.spent(now));
    }

    @Test
    void runsOutOfTimeSpent() {
        TickBudget budget = new TickBudget(1_000, 1_000, null);
        budget.spendNanos(999);
        assertFalse(budget.spent(System.nanoTime()));
        budget.spendNanos(5);
        assertTrue(budget.spent(System.nanoTime()));
    }

    @Test
    void runsOutOfTimeWhileWorking() {
        TickBudget budget = new TickBudget(Integer.MAX_VALUE, 1, null);
        long longAgo = System.nanoTime() - 1_000_000_000L;
        boolean spent = false;
        // The clock is read every few steps, not every step.
        for (int i = 0; i < 100 && !spent; i++) {
            spent = budget.spent(longAgo);
        }
        assertTrue(spent);
    }

    @Test
    void costlyStepsReadTheClockSooner() {
        TickBudget budget = new TickBudget(Integer.MAX_VALUE, 1, null);
        long longAgo = System.nanoTime() - 1_000_000_000L;
        // A step worth a block entity's carrying reads the clock within a few steps (#70).
        boolean spent = false;
        int steps = 0;
        while (!spent) {
            budget.spend(MoveSettings.BLOCK_ENTITY);
            spent = budget.spent(longAgo);
            steps++;
        }
        assertTrue(steps <= 2, "read the clock after " + steps + " steps");
    }

    @Test
    void anInnerBudgetSpendsTheOuterOneAndStopsWithIt() {
        TickBudget server = new TickBudget(100, Long.MAX_VALUE / 2, null);
        TickBudget first = new TickBudget(60, Long.MAX_VALUE / 2, server);
        long now = System.nanoTime();
        first.spend(60);
        assertTrue(first.spent(now));
        assertFalse(server.spent(now));
        TickBudget second = new TickBudget(60, Long.MAX_VALUE / 2, server);
        second.spend(40);
        assertTrue(server.spent(now));
        assertTrue(second.spent(now), "the server's budget ran out first");
    }

    @Test
    void anUnlimitedBudgetNeverRunsOut() {
        TickBudget budget = TickBudget.unlimited();
        long longAgo = System.nanoTime() - 1_000_000_000L;
        for (int i = 0; i < 1_000; i++) {
            budget.spend(1_000_000);
            assertFalse(budget.spent(longAgo));
        }
    }
}
