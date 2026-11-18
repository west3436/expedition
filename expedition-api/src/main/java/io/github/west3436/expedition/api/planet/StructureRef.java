package io.github.west3436.expedition.api.planet;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * An entry in a planet's {@linkplain PlanetStructures structure rules}: a structure or a structure
 * set, such as {@code minecraft:igloo} or {@code minecraft:villages}, or a tag of either, which a
 * file writes as {@code #namespace:path}. An id that names both a structure and a set, as {@code
 * minecraft:trial_chambers} does, matches both.
 *
 * @param id the structure's or structure set's id, or the tag's
 * @param tag whether {@code id} names a tag
 */
public record StructureRef(ResourceLocation id, boolean tag) {
    /**
     * Checks the id.
     *
     * @param id the structure's or structure set's id, or the tag's
     * @param tag whether {@code id} names a tag
     * @throws NullPointerException if {@code id} is null
     */
    public StructureRef {
        Objects.requireNonNull(id, "id");
    }

    /**
     * The reference as a file writes it.
     *
     * @return {@code #namespace:path} for a tag, {@code namespace:path} for a structure
     */
    @Override
    public String toString() {
        return tag ? "#" + id : id.toString();
    }
}
