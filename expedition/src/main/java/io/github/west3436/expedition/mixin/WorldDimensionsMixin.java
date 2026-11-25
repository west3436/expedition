package io.github.west3436.expedition.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.serialization.Lifecycle;
import io.github.west3436.expedition.planet.StableDimensions;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Counts Expedition's dimensions as stable ({@link StableDimensions}), so opening or creating a
 * world with them doesn't warn about "Experimental Settings". Vanilla decides it in two places when
 * it bakes a world's dimensions: each dimension that isn't vanilla-like is experimental, and so is a
 * world with more than three dimensions.
 */
@Mixin(WorldDimensions.class)
abstract class WorldDimensionsMixin {
    @Shadow
    @Final
    private Map<ResourceKey<LevelStem>, LevelStem> dimensions;

    @Inject(method = "checkStability", at = @At("HEAD"), cancellable = true)
    private static void expedition$stableDimension(
            ResourceKey<LevelStem> key, LevelStem stem, CallbackInfoReturnable<Lifecycle> cir) {
        if (StableDimensions.isExpeditions(key, stem)) {
            cir.setReturnValue(Lifecycle.stable());
        }
    }

    /** The number of dimensions vanilla compares with its three, less Expedition's. */
    @ModifyExpressionValue(method = "bake", at = @At(value = "INVOKE", target = "Ljava/util/List;size()I"))
    private int expedition$leaveOutOwnDimensions(int size, @Local(argsOnly = true) Registry<LevelStem> registry) {
        long own = Stream.concat(registry.registryKeySet().stream(), dimensions.keySet().stream())
                .distinct()
                .filter(key -> {
                    LevelStem stem = registry.getOptional(key).orElseGet(() -> dimensions.get(key));
                    return stem != null && StableDimensions.isExpeditions(key, stem);
                })
                .count();
        return size - (int) own;
    }
}
