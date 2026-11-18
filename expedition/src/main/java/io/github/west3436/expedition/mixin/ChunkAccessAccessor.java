package io.github.west3436.expedition.mixin;

import java.util.Map;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A chunk's heightmaps by type, so the mover can update each one as {@code LevelChunk.setBlockState}
 * does, without the entry set and iterator that {@link ChunkAccess#getHeightmaps()} makes every
 * time ({@link io.github.west3436.expedition.mover.QuietBlocks}).
 */
@Mixin(ChunkAccess.class)
public interface ChunkAccessAccessor {
    /** {@return the chunk's heightmaps, which the caller must not change} */
    @Accessor("heightmaps")
    Map<Heightmap.Types, Heightmap> expedition$heightmaps();
}
