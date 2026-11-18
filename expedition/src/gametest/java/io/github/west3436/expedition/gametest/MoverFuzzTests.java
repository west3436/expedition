package io.github.west3436.expedition.gametest;

import io.github.west3436.expedition.ExpeditionMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The mover's integrity fuzz (#12): for each seed, a random box of random blocks, block entities
 * and entities, moved back and forth within the Overworld, into the Nether and back, and short hops
 * onto itself, some of the moves on a budget so small that they span many ticks. After every move
 * the ship must be exactly what it was, and nothing may be left, dropped or made twice. {@link
 * MoverFuzz} says what is built and what is checked.
 *
 * <p>Every PR runs one seed, {@link MoverFuzz#PR_SEED}. The nightly Integrity fuzz workflow runs
 * many. {@code -Pfuzz.seed=S} runs seed S instead, {@code -Pfuzz.seeds=N} N seeds from it, and
 * {@code -Pfuzz.moves=M} gives each seed M moves. A failure names the seed, the move and the command
 * that replays it. One seed failing doesn't stop the others: each logs its own failure, and the test
 * fails once all have run.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MoverFuzzTests {
    private static final String BATCH = "mover_fuzz";

    /**
     * The limit in game ticks. A GameTest server ticks as fast as it can, so this is no measure of
     * time: a nightly run of many seeds takes millions of ticks, and the workflow's own limit is the
     * one that counts.
     */
    private static final int TIMEOUT = 100_000_000;

    private MoverFuzzTests() {}

    /** The fuzz: one seed in every PR, or the seeds the system properties ask for. */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void the_mover_loses_nothing_under_fuzzing(GameTestHelper h) {
        long first = Long.getLong("expedition.fuzz.seed", MoverFuzz.PR_SEED);
        long seeds = Long.getLong("expedition.fuzz.seeds", 1);
        int moves = Integer.getInteger("expedition.fuzz.moves", MoverFuzz.DEFAULT_MOVES);
        List<MoverFuzz.Spec> specs = new ArrayList<>();
        for (long i = 0; i < seeds; i++) {
            specs.add(new MoverFuzz.Spec(first + i, moves, MoverFuzz.Sabotage.NONE, -1));
        }
        MoverFuzz.Driver driver = new MoverFuzz.Driver(h, 0, specs);
        h.startSequence()
                .thenWaitUntil(driver::tick)
                .thenExecute(() -> {
                    List<MoverFuzz.Failure> failures = driver.failures();
                    if (!failures.isEmpty()) {
                        throw new GameTestAssertException("Mover fuzz: seeds failed: "
                                + failures.stream().map(f -> f.seed()).toList() + ". The first:\n"
                                + failures.get(0).message());
                    }
                })
                .thenSucceed();
    }

    /**
     * The fuzz notices a broken move: a block entity whose saved state changed on the way, and an
     * item dropped where the ship was. Each failure names its seed and move and says how to replay
     * it.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void a_broken_move_fails_the_fuzz(GameTestHelper h) {
        List<MoverFuzz.Spec> specs = List.of(
                new MoverFuzz.Spec(5, 4, MoverFuzz.Sabotage.BLOCK_ENTITY, 2),
                new MoverFuzz.Spec(6, 4, MoverFuzz.Sabotage.DROP, 3));
        MoverFuzz.Driver driver = new MoverFuzz.Driver(h, 1, specs);
        h.startSequence()
                .thenWaitUntil(driver::tick)
                .thenExecute(() -> {
                    List<MoverFuzz.Failure> failures = driver.failures();
                    h.assertTrue(failures.size() == 2, "the broken moves failed " + failures.size() + " seeds, not 2");
                    assertNames(h, failures.get(0), 5, "seed 5 failed at move 2 of 4", "the block entity at");
                    assertNames(h, failures.get(1), 6, "seed 6 failed at move 3 of 4", "isn't the ship's");
                })
                .thenSucceed();
    }

    private static void assertNames(GameTestHelper h, MoverFuzz.Failure failure, long seed, String... parts) {
        String message = failure.message();
        h.assertTrue(failure.seed() == seed, "the failure is seed " + failure.seed() + "'s: " + message);
        for (String part : parts) {
            h.assertTrue(message.contains(part), "the failure doesn't say \"" + part + "\": " + message);
        }
        String replay = "Replay: ./gradlew :expedition:runGameTestServer -Pfuzz.seed=" + seed + " -Pfuzz.moves=4";
        h.assertTrue(message.contains(replay), "the failure doesn't say \"" + replay + "\": " + message);
    }
}
