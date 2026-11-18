package io.github.west3436.expedition.api.planet;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * One kind of mob that spawns on a planet, as an entry of a vanilla biome's spawn list gives one.
 *
 * @param type the mob's entity type id
 * @param weight its share among the mobs of its category, at least 1
 * @param minGroup the fewest that spawn together, at least 1; 1 when the file leaves {@code group}
 *     out
 * @param maxGroup the most that spawn together, from {@code minGroup} to {@link #MAX_GROUP}; 1 when
 *     the file leaves {@code group} out
 */
public record PlanetMob(ResourceLocation type, int weight, int minGroup, int maxGroup) {
    /** The largest group. */
    public static final int MAX_GROUP = 64;

    /**
     * Checks the mob.
     *
     * @param type the mob's entity type id
     * @param weight its share among the mobs of its category
     * @param minGroup the fewest that spawn together
     * @param maxGroup the most that spawn together
     * @throws NullPointerException if {@code type} is null
     * @throws IllegalArgumentException if a number is out of range, or {@code minGroup > maxGroup}
     */
    public PlanetMob {
        Objects.requireNonNull(type, "type");
        if (weight < 1) {
            throw new IllegalArgumentException("weight is " + weight + ", but must be at least 1");
        }
        if (minGroup < 1 || maxGroup > MAX_GROUP || minGroup > maxGroup) {
            throw new IllegalArgumentException("group is [" + minGroup + ", " + maxGroup + "]");
        }
    }
}
