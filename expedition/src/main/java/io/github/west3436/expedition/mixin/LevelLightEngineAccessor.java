package io.github.west3436.expedition.mixin;

import javax.annotation.Nullable;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A level's block and sky light engines, which a batch of the mover's light checks ({@link
 * io.github.west3436.expedition.mover.LightChecks}) hands its positions to on the light thread, as
 * {@link LevelLightEngine#checkBlock} does for one.
 */
@Mixin(LevelLightEngine.class)
public interface LevelLightEngineAccessor {
    /** {@return the block light engine, or null if the level has none} */
    @Nullable
    @Accessor("blockEngine")
    LightEngine<?, ?> expedition$blockEngine();

    /** {@return the sky light engine, or null if the level has no sky light} */
    @Nullable
    @Accessor("skyEngine")
    LightEngine<?, ?> expedition$skyEngine();
}
