package io.github.west3436.expedition.planet;

import com.mojang.logging.LogUtils;
import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.api.planet.Planet;
import io.github.west3436.expedition.api.planet.PlanetAtmosphere;
import io.github.west3436.zas.api.environment.AmbientDefinition;
import io.github.west3436.zas.api.environment.AmbientProviders;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

/**
 * Hands each planet's {@code atmosphere} to ZAS³ as its dimension's open air (#15), through one
 * {@link AmbientProviders ambient provider} per planet dimension.
 *
 * <p>ZAS³ asks a provider once per atmos tick of its dimension, so the air follows the planet as
 * it is now: its gases at their partial pressures, or a vacuum, at {@link
 * PlanetAtmosphere#temperatureAt the temperature for the level's time of day}. A planet without an
 * {@code atmosphere} gets no provider, and its dimension's air stays ZAS³'s.
 *
 * <p>The providers follow the planets: {@link #update} registers one for each dimension that gained
 * an atmosphere and removes the provider of each that lost it, on every load. A planet whose values
 * changed keeps its provider, which reads the new values from its next call.
 *
 * <p>What wins, as ZAS³ decides it (its ENV-014 and ENV-019): a ZAS³ ambient file in a datapack for
 * the same dimension ({@code data/<namespace>/zas/ambient/<path>.json}) beats every provider, so
 * pack makers keep the last word. Among providers the highest priority applies; Expedition's are
 * {@link #PRIORITY}, above ZAS³'s default, so a planet file beats a mod that supplies the same
 * dimension's air from code.
 */
public final class PlanetAir {
    /**
     * The priority of a planet's provider: above {@link AmbientProviders#DEFAULT_PRIORITY}, since a
     * pack maker who writes a planet file for a dimension means its air to be the planet's.
     */
    public static final int PRIORITY = 100;

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The planets with an atmosphere, by dimension; replaced whole on each load. */
    private static volatile Map<ResourceKey<Level>, Planet> planets = Map.of();

    /** The planet dimensions that also have a ZAS³ ambient file, which wins. */
    private static volatile Set<ResourceKey<Level>> zasFiles = Set.of();

    private PlanetAir() {}

    /**
     * The id of the provider for a dimension.
     *
     * @param dimension the dimension
     * @return {@code expedition:planet/<namespace>/<path>} of the dimension
     */
    public static ResourceLocation providerId(ResourceKey<Level> dimension) {
        ResourceLocation id = dimension.location();
        return ResourceLocation.fromNamespaceAndPath(
                ExpeditionMod.MOD_ID, "planet/" + id.getNamespace() + "/" + id.getPath());
    }

    /**
     * Brings the providers in line with a load's planets.
     *
     * @param loaded the planets now loaded
     * @param withZasFiles the dimensions among them that also have a ZAS³ ambient file
     */
    public static synchronized void update(Collection<Planet> loaded, Set<ResourceKey<Level>> withZasFiles) {
        Map<ResourceKey<Level>, Planet> next = new HashMap<>();
        for (Planet planet : loaded) {
            if (planet.atmosphere().isPresent()) {
                next.put(planet.dimension(), planet);
            }
        }
        Map<ResourceKey<Level>, Planet> last = planets;
        planets = Map.copyOf(next);
        zasFiles = Set.copyOf(withZasFiles);
        for (ResourceKey<Level> dimension : last.keySet()) {
            if (!next.containsKey(dimension)) {
                AmbientProviders.unregister(providerId(dimension), dimension);
            }
        }
        for (Map.Entry<ResourceKey<Level>, Planet> entry : next.entrySet()) {
            ResourceKey<Level> dimension = entry.getKey();
            if (!last.containsKey(dimension)) {
                AmbientProviders.register(providerId(dimension), dimension, PRIORITY, PlanetAir::ambient);
            }
            if (zasFiles.contains(dimension)) {
                LOGGER.info(
                        "{} has a ZAS³ ambient file, which wins over the atmosphere of the planet {}",
                        dimension.location(),
                        entry.getValue().id());
            }
        }
    }

    /**
     * Removes every provider, as the server stops.
     */
    public static void clear() {
        update(List.of(), Set.of());
    }

    /**
     * The dimensions among some planets' that have a ZAS³ ambient file in a set of resources.
     *
     * @param loaded the planets
     * @param resources the resources the planets loaded from
     * @return the dimensions whose ZAS³ ambient file exists
     */
    public static Set<ResourceKey<Level>> zasFiles(Collection<Planet> loaded, ResourceManager resources) {
        return loaded.stream()
                .map(Planet::dimension)
                .filter(d -> resources
                        .getResource(ResourceLocation.fromNamespaceAndPath(
                                d.location().getNamespace(),
                                "zas/ambient/" + d.location().getPath() + ".json"))
                        .isPresent())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The dimensions with a ZAS³ ambient file as the last load found them.
     *
     * @return the dimensions
     */
    public static Set<ResourceKey<Level>> zasFiles() {
        return zasFiles;
    }

    /**
     * Whether a dimension's open air is its planet's: the planet has an atmosphere, no ZAS³ ambient
     * file overrides it, and Expedition's provider is the one ZAS³ applies. For native mobs (#18)
     * and anything else that needs to know whether the air they meet outdoors is what the planet
     * file says.
     *
     * <p>Thread: any.
     *
     * @param dimension the dimension
     * @return true if ZAS³ takes the dimension's open air from its planet file
     */
    public static boolean appliesTo(ResourceKey<Level> dimension) {
        if (!planets.containsKey(dimension) || zasFiles.contains(dimension)) {
            return false;
        }
        List<AmbientProviders.Registration> providers = AmbientProviders.registered(dimension);
        return !providers.isEmpty() && providers.getFirst().id().equals(providerId(dimension));
    }

    /**
     * The open air of a planet's atmosphere at a time of day.
     *
     * @param air the atmosphere
     * @param timeOfDay the time of day, as {@link Level#getTimeOfDay} gives it
     * @return the ambient ZAS³ is given
     */
    public static AmbientDefinition definition(PlanetAtmosphere air, double timeOfDay) {
        double temperature = air.temperatureAt(timeOfDay);
        return air.vacuum()
                ? AmbientDefinition.vacuum(temperature)
                : new AmbientDefinition(air.partialPressures(), temperature);
    }

    /** ZAS³'s call, once per atmos tick of a planet's dimension, on the server thread. */
    @Nullable
    private static AmbientDefinition ambient(ServerLevel level) {
        Planet planet = planets.get(level.dimension());
        // Between a load and the next update the provider may outlive its planet: let ZAS³'s next
        // source apply.
        if (planet == null || planet.atmosphere().isEmpty()) {
            return null;
        }
        return definition(planet.atmosphere().get(), level.getTimeOfDay(1));
    }
}
