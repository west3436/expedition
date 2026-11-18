package io.github.west3436.expedition.planet;

import io.github.west3436.expedition.api.planet.Planet;
import io.github.west3436.expedition.api.planet.PlanetLookup;
import io.github.west3436.expedition.api.planet.Planets;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import net.neoforged.fml.LogicalSide;

/**
 * The planets loaded on each side, which {@link Planets} answers from. The server's come from its
 * datapacks through {@link PlanetReloadListener}, the client's from its server through {@link
 * PlanetsPayload}. Each side's planets are swapped whole, so a reader never sees half of a reload.
 */
public final class PlanetRegistry implements PlanetLookup {
    private static final PlanetRegistry INSTANCE = new PlanetRegistry();

    private volatile PlanetLoader.Result server = PlanetLoader.Result.EMPTY;
    private volatile List<Planet> serverPlanets = List.of();
    private volatile List<Planet> clientPlanets = List.of();

    private PlanetRegistry() {}

    /** Installs the registry as the API's planet lookup. Called once, as the mod loads. */
    public static void install() {
        Planets.install(INSTANCE);
    }

    @Override
    public List<Planet> all(LogicalSide side) {
        return side == LogicalSide.SERVER ? serverPlanets : clientPlanets;
    }

    /**
     * The server's last load, with its file labels and problems.
     *
     * @return the result; {@link PlanetLoader.Result#EMPTY} while no server has loaded
     */
    public static PlanetLoader.Result server() {
        return INSTANCE.server;
    }

    /**
     * Replaces the server's planets.
     *
     * @param result what the server's datapacks loaded
     */
    public static void setServer(PlanetLoader.Result result) {
        INSTANCE.server = result;
        INSTANCE.serverPlanets = sorted(result.planets().values());
    }

    /**
     * Replaces the client's planets.
     *
     * @param planets what the server sent
     */
    public static void setClient(Collection<Planet> planets) {
        INSTANCE.clientPlanets = sorted(planets);
    }

    private static List<Planet> sorted(Collection<Planet> planets) {
        return planets.stream().sorted(Comparator.comparing(Planet::id)).toList();
    }
}
