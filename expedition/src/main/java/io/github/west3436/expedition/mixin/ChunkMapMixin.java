package io.github.west3436.expedition.mixin;

import io.github.west3436.expedition.planet.StructureFilter;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies a planet's structure lists to its level. The {@code ChunkMap} constructor is where a
 * level's structure state is built, by whatever generator the dimension has, and the
 * {@code ServerLevel} constructor works out its placements right after, before any chunk generates.
 */
@Mixin(ChunkMap.class)
abstract class ChunkMapMixin {
    @Shadow
    @Final
    ServerLevel level;

    @Shadow
    @Final
    private ChunkGeneratorStructureState chunkGeneratorState;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void expedition$applyStructureFilter(CallbackInfo ci) {
        StructureFilter.apply(this.level, this.chunkGeneratorState);
    }
}
