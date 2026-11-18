package io.github.west3436.expedition.api.planet;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * A structure, or a tag of structures, in a planet's {@linkplain PlanetStructures structure rules}.
 * A file writes a tag as {@code #namespace:path}.
 *
 * @param id the structure's id, or the tag's
 * @param tag whether {@code id} names a structure tag
 */
public record StructureRef(ResourceLocation id, boolean tag) {
    /**
     * Checks the id.
     *
     * @param id the structure's id, or the tag's
     * @param tag whether {@code id} names a structure tag
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
