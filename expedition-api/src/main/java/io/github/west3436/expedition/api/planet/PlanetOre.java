package io.github.west3436.expedition.api.planet;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * One ore a planet's ground holds. Ores are chosen by weight among the planet's ores, and how many
 * veins there are in all is the planet's {@linkplain Planet#oreDensity() ore density}.
 *
 * @param block the ore's block id
 * @param weight its share among the planet's ores, at least 1: an ore of weight 40 is chosen twice
 *     as often as one of 20
 * @param veinSize how many blocks a vein holds, from 1 to {@link #MAX_VEIN_SIZE}; {@link
 *     #DEFAULT_VEIN_SIZE} when the file leaves it out
 * @param minY the lowest Y its veins are placed at; {@link #MIN_Y} when the file gives no height
 *     range
 * @param maxY the highest Y its veins are placed at, no lower than {@code minY}; {@link #MAX_Y} when
 *     the file gives no height range
 */
public record PlanetOre(ResourceLocation block, int weight, int veinSize, int minY, int maxY) {
    /** The vein size an ore gets when its file leaves {@code vein_size} out. */
    public static final int DEFAULT_VEIN_SIZE = 8;

    /** The largest vein. */
    public static final int MAX_VEIN_SIZE = 64;

    /** The lowest Y any dimension can have: Minecraft's own limit. */
    public static final int MIN_Y = -2032;

    /** The highest Y any dimension can have: Minecraft's own limit. */
    public static final int MAX_Y = 2031;

    /**
     * Checks the ore.
     *
     * @param block the ore's block id
     * @param weight its share among the planet's ores
     * @param veinSize how many blocks a vein holds
     * @param minY the lowest Y its veins are placed at
     * @param maxY the highest Y its veins are placed at
     * @throws NullPointerException if {@code block} is null
     * @throws IllegalArgumentException if a number is out of range, or {@code minY > maxY}
     */
    public PlanetOre {
        Objects.requireNonNull(block, "block");
        if (weight < 1) {
            throw new IllegalArgumentException("weight is " + weight + ", but must be at least 1");
        }
        if (veinSize < 1 || veinSize > MAX_VEIN_SIZE) {
            throw new IllegalArgumentException("vein_size is " + veinSize + ", but must be from 1 to " + MAX_VEIN_SIZE);
        }
        if (minY < MIN_Y || maxY > MAX_Y || minY > maxY) {
            throw new IllegalArgumentException("y is [" + minY + ", " + maxY + "]");
        }
    }

    /**
     * Whether the file gave the ore no height range, so its veins may be placed at any height of
     * the dimension.
     *
     * @return true if the range is {@link #MIN_Y} to {@link #MAX_Y}
     */
    public boolean anyHeight() {
        return minY == MIN_Y && maxY == MAX_Y;
    }
}
