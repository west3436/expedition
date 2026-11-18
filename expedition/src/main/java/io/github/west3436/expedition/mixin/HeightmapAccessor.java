package io.github.west3436.expedition.mixin;

import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Sets a heightmap's column, so the mover can lower a column whose top block it took away without
 * the position {@link Heightmap#update} makes for its search down ({@link
 * io.github.west3436.expedition.mover.QuietBlocks}).
 */
@Mixin(Heightmap.class)
public interface HeightmapAccessor {
    /**
     * Sets a column's height.
     *
     * @param x the column's x within the chunk
     * @param z its z within the chunk
     * @param height the first y above its highest block that counts, or the chunk's lowest y
     */
    @Invoker("setHeight")
    void expedition$setHeight(int x, int z, int height);
}
