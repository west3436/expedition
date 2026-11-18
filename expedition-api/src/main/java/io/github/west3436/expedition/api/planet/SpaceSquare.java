package io.github.west3436.expedition.api.planet;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * Where a planet sits in Space: a square of blocks at fixed coordinates, mapped 1:1 onto the
 * planet's surface. Its centre in Space is its surface's {@linkplain PlanetSurface centre}.
 *
 * <p>The square runs from {@link #minX()} up to, but not including, {@link #maxX()}, and the same
 * along Z. A square of an odd size reaches one block further on the positive side than the
 * negative.
 *
 * @param centerX the X coordinate in Space of the square's centre
 * @param centerZ the Z coordinate in Space of the square's centre
 * @param size how many blocks a side the square is, from {@link #MIN_SIZE} to {@link #MAX_SIZE}
 * @param image the texture that draws the planet in Space, if it names one, such as {@code
 *     mypack:textures/planet/kepler.png}: a square image, which a client in Space over the square
 *     sees lying flat far below, covering the square, with north at the image's top
 * @param parent the planet this one belongs to, if any, such as a moon's planet: the id of another
 *     planet file
 */
public record SpaceSquare(
        int centerX, int centerZ, int size, Optional<ResourceLocation> image, Optional<ResourceLocation> parent) {
    /** The size a planet file gets when it leaves {@code size} out: Expedition's own planets' size. */
    public static final int DEFAULT_SIZE = 8192;

    /** The smallest square: one chunk a side. */
    public static final int MIN_SIZE = 16;

    /** The largest square. */
    public static final int MAX_SIZE = 1 << 20;

    /**
     * How far Space reaches from its centre along X and Z: the edge of the game's largest world
     * border. Every square lies inside it.
     */
    public static final int LIMIT = 29_999_984;

    /**
     * Checks the square.
     *
     * @param centerX the X coordinate in Space of the square's centre
     * @param centerZ the Z coordinate in Space of the square's centre
     * @param size how many blocks a side the square is
     * @param image the texture that draws the planet in Space, if any
     * @param parent the planet this one belongs to, if any
     * @throws NullPointerException if {@code image} or {@code parent} is null
     * @throws IllegalArgumentException if the size is out of range, or the square reaches past
     *     {@link #LIMIT}
     */
    public SpaceSquare {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(parent, "parent");
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new IllegalArgumentException(
                    "size is " + size + ", but must be from " + MIN_SIZE + " to " + MAX_SIZE);
        }
        long minX = (long) centerX - size / 2;
        long minZ = (long) centerZ - size / 2;
        if (minX < -LIMIT || minZ < -LIMIT || minX + size > LIMIT || minZ + size > LIMIT) {
            throw new IllegalArgumentException("the square reaches past the edge of Space, ±" + LIMIT);
        }
    }

    /**
     * A square with no image and no parent.
     *
     * @param centerX the X coordinate in Space of the square's centre
     * @param centerZ the Z coordinate in Space of the square's centre
     * @param size how many blocks a side the square is
     * @return the square
     * @throws IllegalArgumentException as the canonical constructor does
     */
    public static SpaceSquare of(int centerX, int centerZ, int size) {
        return new SpaceSquare(centerX, centerZ, size, Optional.empty(), Optional.empty());
    }

    /**
     * The square's lowest X in Space.
     *
     * @return the first X inside the square
     */
    public int minX() {
        return centerX - size / 2;
    }

    /**
     * The square's lowest Z in Space.
     *
     * @return the first Z inside the square
     */
    public int minZ() {
        return centerZ - size / 2;
    }

    /**
     * Where the square ends along X.
     *
     * @return the first X past the square
     */
    public int maxX() {
        return minX() + size;
    }

    /**
     * Where the square ends along Z.
     *
     * @return the first Z past the square
     */
    public int maxZ() {
        return minZ() + size;
    }

    /**
     * Whether a column of Space is inside the square.
     *
     * @param x the column's X in Space
     * @param z the column's Z in Space
     * @return true if it is inside
     */
    public boolean contains(int x, int z) {
        return x >= minX() && x < maxX() && z >= minZ() && z < maxZ();
    }

    /**
     * Whether two squares share any column of Space. Squares that only touch along an edge don't.
     *
     * @param other the other square
     * @return true if they overlap
     */
    public boolean overlaps(SpaceSquare other) {
        return minX() < other.maxX() && other.minX() < maxX() && minZ() < other.maxZ() && other.minZ() < maxZ();
    }
}
