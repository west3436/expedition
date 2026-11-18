package io.github.west3436.expedition.mover;

import io.github.west3436.expedition.ExpeditionMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/** The tags the mover reads, which datapacks fill. */
public final class ExpeditionTags {
    /**
     * {@code expedition:immovable}: blocks a ship may not hold, because they break when moved. A move
     * of a box holding one is refused as {@link MoveRefusal#IMMOVABLE}. Expedition ships it empty;
     * pack makers add to it.
     */
    public static final TagKey<Block> IMMOVABLE =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(ExpeditionMod.MOD_ID, "immovable"));

    private ExpeditionTags() {}
}
