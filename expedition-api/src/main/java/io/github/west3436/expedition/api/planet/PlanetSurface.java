package io.github.west3436.expedition.api.planet;

/**
 * Where a planet's surface meets its square in Space: the column of the planet's dimension that
 * lies under the square's centre. Space and surface map 1:1 around it, so a ship at {@code
 * dx, dz} from the square's centre in Space is over the column {@code dx, dz} from this one.
 *
 * @param centerX the X of the column, in the planet's dimension
 * @param centerZ the Z of the column, in the planet's dimension
 */
public record PlanetSurface(int centerX, int centerZ) {
    /** What a planet file gets when it leaves {@code surface} out: the dimension's origin. */
    public static final PlanetSurface DEFAULT = new PlanetSurface(0, 0);
}
