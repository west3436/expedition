package io.github.west3436.expedition.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import io.github.west3436.expedition.planet.PlanetBorders;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Leaves a chunk empty when it lies wholly past a bounded planet's border and void margin ({@link
 * PlanetBorders#isVoid}). Every chunk of every generator goes through these steps, so this covers
 * vanilla's generators, modded ones and {@code expedition:planet} alike.
 *
 * <p>Noise, surface, carvers and the initial mobs are skipped, as are structure starts and
 * features. Biomes, heightmaps, light and the rest run as usual, so the void keeps its biome, and
 * with it the sky and fog.
 */
@Mixin(ChunkStatusTasks.class)
abstract class ChunkStatusTasksMixin {
    @Inject(
            method = {"generateNoise", "generateSurface", "generateCarvers", "generateSpawn"},
            at = @At("HEAD"),
            cancellable = true)
    private static void expedition$leaveEmpty(
            WorldGenContext context,
            ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (PlanetBorders.isVoid(context.level(), chunk.getPos())) {
            cir.setReturnValue(CompletableFuture.completedFuture(chunk));
        }
    }

    @WrapWithCondition(
            method = "generateStructureStarts",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/chunk/ChunkGenerator;createStructures(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;)V"))
    private static boolean expedition$noStructureStarts(
            ChunkGenerator generator,
            RegistryAccess registries,
            ChunkGeneratorStructureState state,
            StructureManager structures,
            ChunkAccess chunk,
            StructureTemplateManager templates,
            WorldGenContext context,
            ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess target) {
        return !PlanetBorders.isVoid(context.level(), chunk.getPos());
    }

    @WrapWithCondition(
            method = "generateFeatures",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/chunk/ChunkGenerator;applyBiomeDecoration(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/StructureManager;)V"))
    private static boolean expedition$noFeatures(
            ChunkGenerator generator,
            WorldGenLevel region,
            ChunkAccess chunk,
            StructureManager structures,
            WorldGenContext context,
            ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess target) {
        return !PlanetBorders.isVoid(context.level(), chunk.getPos());
    }
}
