package io.github.west3436.expedition.api.planet;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * A planet: a dimension bound to a square in Space, with the properties its planet file gives it.
 * Planet files are datapack files at {@code data/<namespace>/expedition/planet/<path>.json}, and
 * {@link Planets} answers which planets are loaded.
 *
 * <p>Every value here is the file's, or the default the file format gives a field the file leaves
 * out. A planet is immutable.
 *
 * @param id the planet's id: its file's namespace and path, so {@code
 *     data/mypack/expedition/planet/kepler.json} is {@code mypack:kepler}
 * @param dimension the dimension the planet is; any dimension, vanilla, modded or a datapack's
 * @param space where the planet sits in Space
 * @param surface the column of the dimension under the centre of {@code space}
 * @param atmosphere the surface's open air, or empty to leave the dimension's air to ZAS³
 * @param gravity the surface's gravity as a multiple of the Overworld's, from 0 to {@link
 *     #MAX_GRAVITY}; {@link #DEFAULT_GRAVITY} when the file leaves it out
 * @param ores the ores the ground holds, in the file's order, each block once; empty when the file
 *     leaves {@code ores} out
 * @param oreDensity how many ore veins there are, as a multiple of the usual number, from 0 to
 *     {@link #MAX_ORE_DENSITY}; {@link #DEFAULT_ORE_DENSITY} when the file leaves it out
 * @param mobs the mobs that spawn on the planet
 * @param structures which structures may and may not generate on the planet
 * @param access who may find and reach the planet
 */
public record Planet(
        ResourceLocation id,
        ResourceKey<Level> dimension,
        SpaceSquare space,
        PlanetSurface surface,
        Optional<PlanetAtmosphere> atmosphere,
        double gravity,
        List<PlanetOre> ores,
        double oreDensity,
        PlanetMobs mobs,
        PlanetStructures structures,
        PlanetAccess access) {
    /** The gravity a planet file gets when it leaves {@code gravity} out: the Overworld's. */
    public static final double DEFAULT_GRAVITY = 1.0;

    /** The strongest gravity a planet may have. */
    public static final double MAX_GRAVITY = 10.0;

    /** The ore density a planet file gets when it leaves {@code ore_density} out. */
    public static final double DEFAULT_ORE_DENSITY = 1.0;

    /** The highest ore density a planet may have. */
    public static final double MAX_ORE_DENSITY = 10.0;

    /**
     * Checks the planet, and copies its ores.
     *
     * @param id the planet's id
     * @param dimension the dimension the planet is
     * @param space where the planet sits in Space
     * @param surface the column of the dimension under the centre of {@code space}
     * @param atmosphere the surface's open air, or empty
     * @param gravity the surface's gravity as a multiple of the Overworld's
     * @param ores the ores the ground holds
     * @param oreDensity how many ore veins there are, as a multiple of the usual number
     * @param mobs the mobs that spawn on the planet
     * @param structures which structures may and may not generate on the planet
     * @param access who may find and reach the planet
     * @throws NullPointerException if anything but a number is null
     * @throws IllegalArgumentException if {@code dimension} isn't a dimension key, a number is out
     *     of range or not finite, or two ores are the same block
     */
    public Planet {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(space, "space");
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(atmosphere, "atmosphere");
        Objects.requireNonNull(mobs, "mobs");
        Objects.requireNonNull(structures, "structures");
        Objects.requireNonNull(access, "access");
        if (!dimension.isFor(Registries.DIMENSION)) {
            throw new IllegalArgumentException(dimension + " isn't a dimension key");
        }
        if (!(gravity >= 0 && gravity <= MAX_GRAVITY)) {
            throw new IllegalArgumentException("gravity is " + gravity + ", but must be from 0 to " + MAX_GRAVITY);
        }
        if (!(oreDensity >= 0 && oreDensity <= MAX_ORE_DENSITY)) {
            throw new IllegalArgumentException(
                    "ore_density is " + oreDensity + ", but must be from 0 to " + MAX_ORE_DENSITY);
        }
        ores = List.copyOf(ores);
        if (ores.stream().map(PlanetOre::block).distinct().count() != ores.size()) {
            throw new IllegalArgumentException("an ore's block is listed more than once");
        }
    }
}
