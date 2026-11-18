package io.github.west3436.expedition.api.space;

import io.github.west3436.expedition.api.ExpeditionApi;
import io.github.west3436.expedition.api.planet.Planet;
import io.github.west3436.expedition.api.planet.PlanetGravity;
import io.github.west3436.expedition.api.planet.PlanetSurface;
import io.github.west3436.expedition.api.planet.Planets;
import io.github.west3436.expedition.api.planet.SpaceSquare;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.fml.LogicalSide;

/**
 * Space, {@code expedition:space}: one void dimension with no terrain, no gravity and hard vacuum,
 * where every planet has its {@linkplain SpaceSquare square} at fixed coordinates.
 *
 * <ul>
 *   <li><b>Air.</b> Its open air is a ZAS³ ambient file, {@code
 *       data/expedition/zas/ambient/space.json}: a vacuum at {@link #TEMPERATURE} K. Anything
 *       unsealed is exposed, and a sealed build holds its air as ZAS³ simulates it, so a sealed
 *       build in Space is a station. A datapack may replace the file as any ZAS³ ambient file.
 *   <li><b>Gravity.</b> {@link #GRAVITY}: {@link PlanetGravity} gives it for the dimension, and
 *       Expedition applies it as it applies a planet's.
 *   <li><b>The plane.</b> Planets sit on one plane: a square covers columns of Space at every
 *       height, and the distance between two places in Space is {@linkplain #distance horizontal},
 *       since build height caps the vertical.
 *   <li><b>Squares and surfaces.</b> A planet's square maps 1:1 onto its surface around the
 *       {@linkplain PlanetSurface surface's centre}: a ship over a column of the square is over the
 *       same column of the planet ({@link #toSurface}, {@link #fromSurface}).
 * </ul>
 *
 * <p>Space is not a planet, and no planet file may name it.
 *
 * <p>Thread: any. The planet queries answer from {@link Planets}' snapshot of each side.
 */
public final class Space {
    /** {@code expedition:space}: the Space dimension. */
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(
            Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(ExpeditionApi.MOD_ID, "space"));

    /** Space's gravity, as a multiple of the Overworld's: none. */
    public static final double GRAVITY = 0.0;

    /**
     * The temperature of Space's open air, in kelvin, as its ZAS³ ambient file gives it: that of the
     * cosmic background.
     */
    public static final double TEMPERATURE = 2.7;

    private Space() {}

    /**
     * Whether a level is Space.
     *
     * @param level a server or client level
     * @return true if it is the Space dimension
     */
    public static boolean is(Level level) {
        return is(Objects.requireNonNull(level, "level").dimension());
    }

    /**
     * Whether a dimension is Space.
     *
     * @param dimension the dimension
     * @return true if it is {@link #DIMENSION}
     */
    public static boolean is(ResourceKey<Level> dimension) {
        return DIMENSION.equals(Objects.requireNonNull(dimension, "dimension"));
    }

    /**
     * The distance between two places in Space: horizontal, since every planet sits on one plane.
     *
     * @param x1 the first place's X
     * @param z1 the first place's Z
     * @param x2 the second place's X
     * @param z2 the second place's Z
     * @return the distance in blocks, ignoring height
     */
    public static double distance(double x1, double z1, double x2, double z2) {
        return Math.hypot(x2 - x1, z2 - z1);
    }

    /**
     * The planet a column of Space is over: the one whose square holds it. Squares never overlap,
     * so there is at most one.
     *
     * @param x the column's X in Space
     * @param z the column's Z in Space
     * @param side the side whose planets to look in
     * @return the planet, or empty if the column is over none
     */
    public static Optional<Planet> planetAt(int x, int z, LogicalSide side) {
        for (Planet planet : Planets.all(Objects.requireNonNull(side, "side"))) {
            if (planet.space().contains(x, z)) {
                return Optional.of(planet);
            }
        }
        return Optional.empty();
    }

    /**
     * Where a position in Space lies on a planet's surface: the same offset from the surface's
     * centre as it has from the square's centre, at the same height. The position need not be over
     * the square.
     *
     * @param planet the planet
     * @param inSpace a position in Space
     * @return the position in the planet's dimension
     */
    public static BlockPos toSurface(Planet planet, BlockPos inSpace) {
        SpaceSquare square = planet.space();
        PlanetSurface surface = planet.surface();
        return new BlockPos(
                inSpace.getX() - square.centerX() + surface.centerX(),
                inSpace.getY(),
                inSpace.getZ() - square.centerZ() + surface.centerZ());
    }

    /**
     * Where a position on a planet's surface lies in Space: the inverse of {@link #toSurface}.
     *
     * @param planet the planet
     * @param onSurface a position in the planet's dimension
     * @return the position in Space
     */
    public static BlockPos fromSurface(Planet planet, BlockPos onSurface) {
        SpaceSquare square = planet.space();
        PlanetSurface surface = planet.surface();
        return new BlockPos(
                onSurface.getX() - surface.centerX() + square.centerX(),
                onSurface.getY(),
                onSurface.getZ() - surface.centerZ() + square.centerZ());
    }
}
