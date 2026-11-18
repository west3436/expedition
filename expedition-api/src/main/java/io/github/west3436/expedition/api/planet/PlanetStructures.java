package io.github.west3436.expedition.api.planet;

import java.util.List;

/**
 * Which structures may and may not generate on a planet.
 *
 * @param allow the structures and tags that may generate on the planet, in the file's order
 * @param deny the structures and tags that never generate on it, in the file's order
 */
public record PlanetStructures(List<StructureRef> allow, List<StructureRef> deny) {
    /** What a planet file gets when it leaves {@code structures} out: no rules. */
    public static final PlanetStructures DEFAULT = new PlanetStructures(List.of(), List.of());

    /**
     * Copies the lists.
     *
     * @param allow the structures and tags that may generate on the planet
     * @param deny the structures and tags that never generate on it
     * @throws NullPointerException if a list, or anything in it, is null
     */
    public PlanetStructures {
        allow = List.copyOf(allow);
        deny = List.copyOf(deny);
    }
}
