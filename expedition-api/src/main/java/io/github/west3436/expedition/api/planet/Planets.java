package io.github.west3436.expedition.api.planet;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.fml.LogicalSide;

/**
 * Which planets are loaded, and the planet of a dimension.
 *
 * <p>The logical server's planets are its datapacks' planet files, loaded when the server starts
 * and again on every {@code /reload}. A file with a problem doesn't load, and the server's log names
 * the file and the field. The logical client's planets are the ones its server last sent: when it
 * joins, and after every {@code /reload}. Without Expedition, or before a server has loaded any,
 * there are none.
 *
 * <p>Thread: any. Each answer is a snapshot: a {@code /reload} swaps every planet at once, so
 * values read from one {@link Planet} always belong together.
 */
public final class Planets {
    private static final PlanetLookup NONE = side -> List.of();

    private static volatile PlanetLookup lookup = NONE;

    private Planets() {}

    /**
     * The planet a level is.
     *
     * @param level a server or client level
     * @return the planet whose dimension is the level's, from the level's own side, or empty if the
     *     level isn't a planet
     */
    public static Optional<Planet> forLevel(Level level) {
        return forDimension(level.dimension(), level.isClientSide() ? LogicalSide.CLIENT : LogicalSide.SERVER);
    }

    /**
     * The planet a dimension is.
     *
     * @param dimension the dimension
     * @param side the side whose planets to look in
     * @return the planet whose dimension it is, or empty if it isn't a planet
     */
    public static Optional<Planet> forDimension(ResourceKey<Level> dimension, LogicalSide side) {
        Objects.requireNonNull(dimension, "dimension");
        for (Planet planet : lookup.all(side)) {
            if (planet.dimension().equals(dimension)) {
                return Optional.of(planet);
            }
        }
        return Optional.empty();
    }

    /**
     * A planet by its id.
     *
     * @param id the planet's id, its file's namespace and path
     * @param side the side whose planets to look in
     * @return the planet, or empty if none of that id is loaded
     */
    public static Optional<Planet> byId(ResourceLocation id, LogicalSide side) {
        Objects.requireNonNull(id, "id");
        for (Planet planet : lookup.all(side)) {
            if (planet.id().equals(id)) {
                return Optional.of(planet);
            }
        }
        return Optional.empty();
    }

    /**
     * Every planet loaded on a side.
     *
     * @param side the side whose planets to list
     * @return the planets, sorted by id; immutable
     */
    public static List<Planet> all(LogicalSide side) {
        return lookup.all(Objects.requireNonNull(side, "side"));
    }

    /**
     * Installs what answers these queries. Expedition calls this once, as it loads; other mods never
     * do.
     *
     * @param impl the lookup
     * @throws NullPointerException if {@code impl} is null
     * @throws IllegalStateException if a lookup is already installed
     */
    public static synchronized void install(PlanetLookup impl) {
        Objects.requireNonNull(impl, "impl");
        if (lookup != NONE) {
            throw new IllegalStateException("a planet lookup is already installed: " + lookup);
        }
        lookup = impl;
    }
}
