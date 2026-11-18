package io.github.west3436.expedition.api.planet;

import java.util.List;
import net.neoforged.fml.LogicalSide;

/**
 * What answers {@link Planets}: Expedition installs its own when it loads. Other mods call {@link
 * Planets}, not this.
 */
public interface PlanetLookup {
    /**
     * The planets loaded on one side.
     *
     * <p>Thread: any. The list is immutable, and the same object until the planets next change.
     *
     * @param side the logical server, whose planets come from its datapacks, or the logical client,
     *     whose planets the server last sent it
     * @return every planet, sorted by id
     */
    List<Planet> all(LogicalSide side);
}
