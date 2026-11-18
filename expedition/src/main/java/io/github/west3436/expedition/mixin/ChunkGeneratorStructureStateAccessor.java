package io.github.west3436.expedition.mixin;

import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets {@link io.github.west3436.expedition.planet.PlanetStructures} replace a level's structure
 * sets. The list drives both structure generation ({@code ChunkGenerator#createStructures}) and
 * locating ({@code generatePositions}, which {@code findNearestMapStructure} reads).
 */
@Mixin(ChunkGeneratorStructureState.class)
public interface ChunkGeneratorStructureStateAccessor {
    /**
     * Replaces the structure sets the level may generate.
     *
     * @param sets the sets
     */
    @Mutable
    @Accessor("possibleStructureSets")
    void expedition$setPossibleStructureSets(List<Holder<StructureSet>> sets);

    /**
     * Whether the placements and stronghold rings were already worked out from the old list.
     *
     * @return true once {@code ensureStructuresGenerated} has run
     */
    @Accessor("hasGeneratedPositions")
    boolean expedition$hasGeneratedPositions();
}
