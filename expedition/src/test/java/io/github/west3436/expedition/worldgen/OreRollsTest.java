package io.github.west3436.expedition.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** The dice the planet ore feature rolls, without the game. */
class OreRollsTest {
    @Test
    void aWholeDensityGivesAnExactCount() {
        assertEquals(16, OreRolls.veins(1.0, 0.0));
        assertEquals(16, OreRolls.veins(1.0, 0.999));
        assertEquals(32, OreRolls.veins(2.0, 0.5));
        assertEquals(0, OreRolls.veins(0.0, 0.0));
    }

    @Test
    void theFractionIsTheChanceOfOneMore() {
        // 1.5 × 16 = 24 exactly; 1.03125 × 16 = 16.5.
        assertEquals(24, OreRolls.veins(1.5, 0.99));
        assertEquals(17, OreRolls.veins(1.03125, 0.49));
        assertEquals(16, OreRolls.veins(1.03125, 0.5));
    }

    @Test
    void theMeanCountIsTheDensitysShare() {
        Random random = new Random(5);
        long sum = 0;
        int rolls = 100_000;
        for (int i = 0; i < rolls; i++) {
            sum += OreRolls.veins(0.1, random.nextDouble());
        }
        assertEquals(1.6, (double) sum / rolls, 0.02);
    }

    @Test
    void picksFollowTheWeights() {
        int[] weights = {40, 20};
        assertEquals(60, OreRolls.total(weights));
        assertEquals(0, OreRolls.pick(weights, 0));
        assertEquals(0, OreRolls.pick(weights, 39));
        assertEquals(1, OreRolls.pick(weights, 40));
        assertEquals(1, OreRolls.pick(weights, 59));
    }

    @Test
    void hugeWeightsDontOverflow() {
        int[] weights = {Integer.MAX_VALUE, Integer.MAX_VALUE, 1};
        assertEquals(2L * Integer.MAX_VALUE + 1, OreRolls.total(weights));
        assertEquals(2, OreRolls.pick(weights, 2L * Integer.MAX_VALUE));
    }

    @Test
    void aRollOutsideTheSumIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> OreRolls.pick(new int[] {1, 2}, 3));
        assertThrows(IllegalArgumentException.class, () -> OreRolls.pick(new int[] {1, 2}, -1));
    }
}
