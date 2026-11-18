package io.github.west3436.expedition.planet;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/**
 * The ids a planet file may name that can be checked while it loads: blocks and entity types, whose
 * registries are fixed before any datapack loads. Dimensions, gases and structures change with the
 * datapacks, so {@link PlanetLoader#checkWorld} checks them once the server has loaded.
 */
public interface References {
    /** The game's own registries. */
    References REGISTRIES = new References() {
        @Override
        public boolean isBlock(ResourceLocation id) {
            return BuiltInRegistries.BLOCK.containsKey(id);
        }

        @Override
        public boolean isEntityType(ResourceLocation id) {
            return BuiltInRegistries.ENTITY_TYPE.containsKey(id);
        }
    };

    /**
     * Takes every id: for a client reading what its server already checked, and for tests without
     * the game.
     */
    References ANY = new References() {
        @Override
        public boolean isBlock(ResourceLocation id) {
            return true;
        }

        @Override
        public boolean isEntityType(ResourceLocation id) {
            return true;
        }
    };

    /**
     * Whether a block of this id is registered.
     *
     * @param id the id
     * @return true if it is
     */
    boolean isBlock(ResourceLocation id);

    /**
     * Whether an entity type of this id is registered.
     *
     * @param id the id
     * @return true if it is
     */
    boolean isEntityType(ResourceLocation id);
}
