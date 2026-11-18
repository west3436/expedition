package io.github.west3436.expedition.worldgen;

/**
 * The dice {@link PlanetOresFeature} rolls, without the game: how many veins a chunk gets, and which
 * ore each vein is.
 */
public final class OreRolls {
    /**
     * How many veins a chunk gets at an ore density of 1: about as many as the Overworld's common
     * ores have where they are richest.
     */
    public static final int VEINS_PER_CHUNK = 16;

    private OreRolls() {}

    /**
     * How many veins a chunk gets: {@link #VEINS_PER_CHUNK} times the density, with the fraction left
     * over as the chance of one more, so the mean is exact.
     *
     * @param density the planet's ore density, 0 or more
     * @param roll a uniform roll in [0, 1)
     * @return the number of veins
     */
    public static int veins(double density, double roll) {
        double mean = VEINS_PER_CHUNK * density;
        int whole = (int) Math.floor(mean);
        return roll < mean - whole ? whole + 1 : whole;
    }

    /**
     * Which entry a roll picks, each with a chance in proportion to its weight.
     *
     * @param weights the weights, each at least 1
     * @param roll a uniform roll in [0, the weights' sum)
     * @return the index of the entry picked
     * @throws IllegalArgumentException if the roll is outside [0, the weights' sum)
     */
    public static int pick(int[] weights, long roll) {
        long left = roll;
        if (left >= 0) {
            for (int i = 0; i < weights.length; i++) {
                left -= weights[i];
                if (left < 0) {
                    return i;
                }
            }
        }
        throw new IllegalArgumentException("roll " + roll + " is outside the weights' sum");
    }

    /**
     * The weights' sum.
     *
     * @param weights the weights
     * @return their sum
     */
    public static long total(int[] weights) {
        long sum = 0;
        for (int w : weights) {
            sum += w;
        }
        return sum;
    }
}
