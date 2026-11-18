package io.github.west3436.expedition.worldgen;

import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/**
 * The shape of a planet's land, without the game: how high the ground is in each column, and which
 * layer each block of a column is in. {@link PlanetChunkGenerator} turns the layers into blocks.
 *
 * <p>The height is a sum of {@value #OCTAVES} octaves of 2D simplex noise, each twice as fine and
 * half as tall as the last, scaled so the ground never leaves {@code groundLevel ± amplitude}. The
 * same seed and dials always give the same land, on any machine.
 */
public final class PlanetTerrain {
    /** How many octaves of noise are summed. */
    static final int OCTAVES = 4;

    /** The sum of the octaves' weights, 1 + 1/2 + 1/4 + 1/8, which brings the sum back to ±1. */
    private static final double TOTAL_WEIGHT = 2.0 - Math.pow(0.5, OCTAVES - 1);

    /** Where each block of a column lies against the ground and the sea. */
    public enum Layer {
        /** Below the filler: the bulk of the planet. */
        BASE,
        /** The few blocks under the surface block. */
        FILLER,
        /** The top block of the ground. */
        SURFACE,
        /** Above the ground and below the sea level. */
        SEA,
        /** Above the ground and at or above the sea level. */
        AIR
    }

    private final SimplexNoise[] octaves = new SimplexNoise[OCTAVES];
    private final int groundLevel;
    private final int amplitude;
    private final double frequency;

    /**
     * Land from a seed and its dials.
     *
     * @param seed the seed, which {@link PlanetChunkGenerator} takes from the world's
     * @param groundLevel the height of the ground where the noise is zero: the land's middle height
     * @param amplitude how far the ground may rise above or fall below {@code groundLevel}, at least 0
     * @param scale the width in blocks of the largest hills, at least 1
     */
    public PlanetTerrain(long seed, int groundLevel, int amplitude, int scale) {
        if (amplitude < 0 || scale < 1) {
            throw new IllegalArgumentException("amplitude " + amplitude + " or scale " + scale + " is out of range");
        }
        XoroshiroRandomSource random = new XoroshiroRandomSource(seed);
        for (int i = 0; i < OCTAVES; i++) {
            octaves[i] = new SimplexNoise(random);
        }
        this.groundLevel = groundLevel;
        this.amplitude = amplitude;
        this.frequency = 1.0 / scale;
    }

    /**
     * The height of the topmost ground block of a column, before the world's height limits clamp
     * it.
     *
     * @param x the column's block x
     * @param z the column's block z
     * @return the y of the column's surface block, within {@code groundLevel ± amplitude}
     */
    public int groundY(int x, int z) {
        if (amplitude == 0) {
            return groundLevel;
        }
        double sum = 0;
        double weight = 1;
        double f = frequency;
        for (SimplexNoise octave : octaves) {
            sum += weight * octave.getValue(x * f, z * f);
            weight *= 0.5;
            f *= 2;
        }
        double noise = Mth.clamp(sum / TOTAL_WEIGHT, -1.0, 1.0);
        return groundLevel + (int) Math.round(noise * amplitude);
    }

    /**
     * Which layer a block of a column is in.
     *
     * @param y the block's y
     * @param groundY the y of the column's surface block, from {@link #groundY}
     * @param fillerDepth how many filler blocks lie under the surface block, at least 0
     * @param seaLevel the lowest y above the sea: the sea fills the blocks above the ground and below
     *     this
     * @return the layer
     */
    public static Layer layer(int y, int groundY, int fillerDepth, int seaLevel) {
        if (y > groundY) {
            return y < seaLevel ? Layer.SEA : Layer.AIR;
        }
        if (y == groundY) {
            return Layer.SURFACE;
        }
        return y >= groundY - fillerDepth ? Layer.FILLER : Layer.BASE;
    }
}
