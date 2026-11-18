package io.github.west3436.expedition.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.west3436.expedition.planet.PlanetSpawns;
import net.minecraft.util.random.WeightedRandomList;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.biome.MobSpawnSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Gives a new chunk on a planet the planet's creatures. Vanilla spawns a new chunk's creatures from
 * its biome's list directly, without NeoForge's potential-spawns event, so the planet's list is put
 * in where that list is read.
 */
@Mixin(NaturalSpawner.class)
abstract class NaturalSpawnerMixin {
    @WrapOperation(
            method = "spawnMobsForChunkGeneration",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/biome/MobSpawnSettings;getMobs(Lnet/minecraft/world/entity/MobCategory;)Lnet/minecraft/util/random/WeightedRandomList;"))
    private static WeightedRandomList<MobSpawnSettings.SpawnerData> expedition$planetCreatures(
            MobSpawnSettings settings,
            MobCategory category,
            Operation<WeightedRandomList<MobSpawnSettings.SpawnerData>> original,
            @Local(argsOnly = true) ServerLevelAccessor level) {
        WeightedRandomList<MobSpawnSettings.SpawnerData> biome = original.call(settings, category);
        return category == MobCategory.CREATURE ? PlanetSpawns.creaturesForNewChunk(level, biome) : biome;
    }
}
