package io.github.west3436.expedition.api.planet;

import java.util.List;

/**
 * Which structures may and may not generate on a planet, whatever generates its dimension. Each
 * entry is a {@link StructureRef}: a structure, a structure set, or a tag of either.
 *
 * <ul>
 *   <li>{@code deny} removes every structure it matches, by the structure itself or by its set. It
 *       wins over {@code allow}.
 *   <li>{@code allow}, when given, is the whole list of what may generate: a structure it doesn't
 *       match, by the structure or by its set, never does. Left empty, everything may.
 * </ul>
 *
 * <p>The lists can only take structures away. Biomes still decide where a structure may generate,
 * so a structure whose biomes the planet doesn't have stays away whatever {@code allow} says; its
 * biome tag is where to add them. Denying one structure of a set gives its places to the rest of
 * the set: with {@code minecraft:bastion_remnant} denied, every Nether complex is a fortress. The
 * lists apply when the server starts, not on {@code /reload}.
 *
 * @param allow what may generate on the planet, in the file's order; empty for everything
 * @param deny what never generates on it, in the file's order
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
