package io.github.west3436.expedition.planet;

import io.github.west3436.expedition.api.planet.PlanetSurface;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A bounded planet's edge, in its dimension's own blocks: the square its world border follows, and
 * how far past it terrain still generates.
 *
 * <p>The square is the planet's square in Space laid on its surface: centred on {@code
 * surface.center}, {@code space.size} blocks a side, running from {@link #minX} up to, but not
 * including, {@link #maxX}, as {@link io.github.west3436.expedition.api.planet.SpaceSquare} does.
 * Past it, terrain generates for {@link #voidMargin} blocks more. A chunk lying wholly beyond that
 * cutoff is left empty, so terrain runs on to the next chunk edge, up to 15 blocks further.
 *
 * @param minX the first X inside the square
 * @param minZ the first Z inside the square
 * @param maxX the first X past the square
 * @param maxZ the first Z past the square
 * @param voidMargin how many blocks past the square terrain still generates; -1 for terrain
 *     everywhere
 */
public record PlanetBounds(int minX, int minZ, int maxX, int maxZ, int voidMargin) {
    /** How many blocks a side a region file covers. */
    public static final int REGION_BLOCKS = 512;

    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    /**
     * A planet's bounds.
     *
     * @param surface the column under the centre of the planet's square
     * @param size the square's side, {@code space.size}
     * @param voidMargin how many blocks past the square terrain still generates; -1 for everywhere
     * @return the bounds
     */
    public static PlanetBounds of(PlanetSurface surface, int size, int voidMargin) {
        int minX = surface.centerX() - size / 2;
        int minZ = surface.centerZ() - size / 2;
        return new PlanetBounds(minX, minZ, minX + size, minZ + size, voidMargin);
    }

    /** {@return the square's side, in blocks} */
    public int size() {
        return maxX - minX;
    }

    /** {@return the X of the square's centre: where the world border is centred} */
    public double centerX() {
        return (minX + (double) maxX) / 2;
    }

    /** {@return the Z of the square's centre: where the world border is centred} */
    public double centerZ() {
        return (minZ + (double) maxZ) / 2;
    }

    /**
     * Whether a chunk lies wholly past the cutoff, the square plus {@link #voidMargin}, and so is left
     * empty.
     *
     * @param chunkX the chunk's X
     * @param chunkZ the chunk's Z
     * @return true if it is left empty
     */
    public boolean isVoid(int chunkX, int chunkZ) {
        return pastCutoff((long) chunkX << 4, (long) chunkZ << 4, 16);
    }

    /**
     * Whether a region file's 32 × 32 chunks all lie wholly past the cutoff.
     *
     * @param regionX the region's X, as in {@code r.<x>.<z>.mca}
     * @param regionZ the region's Z
     * @return true if every chunk in it would be left empty
     */
    public boolean isRegionVoid(int regionX, int regionZ) {
        return pastCutoff((long) regionX * REGION_BLOCKS, (long) regionZ * REGION_BLOCKS, REGION_BLOCKS);
    }

    /**
     * How many region files, by name, lie wholly past the cutoff. Names that aren't a region
     * file's, {@code r.<x>.<z>.mca}, are skipped.
     *
     * @param fileNames the names of a level's region files
     * @return how many are past the cutoff
     */
    public long voidRegions(Collection<String> fileNames) {
        long count = 0;
        for (String name : fileNames) {
            Matcher region = REGION_FILE.matcher(name);
            if (region.matches()) {
                try {
                    if (isRegionVoid(Integer.parseInt(region.group(1)), Integer.parseInt(region.group(2)))) {
                        count++;
                    }
                } catch (NumberFormatException e) {
                    // Not a region a level could have.
                }
            }
        }
        return count;
    }

    /** Whether a square of blocks, {@code side} wide from its lowest corner, lies wholly past the cutoff. */
    private boolean pastCutoff(long x, long z, int side) {
        if (voidMargin < 0) {
            return false;
        }
        return x + side <= (long) minX - voidMargin
                || x >= (long) maxX + voidMargin
                || z + side <= (long) minZ - voidMargin
                || z >= (long) maxZ + voidMargin;
    }
}
