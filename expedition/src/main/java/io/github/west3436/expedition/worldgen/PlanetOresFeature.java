package io.github.west3436.expedition.worldgen;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.api.planet.Planet;
import io.github.west3436.expedition.api.planet.PlanetOre;
import io.github.west3436.expedition.api.planet.Planets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockStateMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;
import net.neoforged.fml.LogicalSide;

/**
 * {@code expedition:planet_ores}: a planet's ores, from its file's {@code ores} and {@code
 * ore_density} (#17). The biome modifier {@code expedition:planet_ores} adds it to every biome in
 * the underground-ores step, so it runs in every chunk of every dimension, whatever generates it,
 * and a planet's ores don't depend on its biomes. In a dimension that isn't a planet it places
 * nothing.
 *
 * <p>Each chunk gets {@value OreRolls#VEINS_PER_CHUNK} veins times the planet's ore density. Each
 * vein is one of the planet's ores, picked by weight, of the ore's vein size. It is placed at a
 * random column of the chunk and a random height within the ore's range, the dimension's height and
 * the ground: no higher than the column's highest solid block, so a vein isn't wasted on the sky.
 * Veins are vanilla's ore veins, so they look like the Overworld's.
 *
 * <p>An ore replaces only the planet's rock: the blocks in {@code #expedition:ore_replaceable}
 * (vanilla's stone and deepslate ore-replaceables, the Nether's base stone and end stone, to which
 * pack makers may add), and the dimension's own base block when its generator has one: the {@code
 * base_block} of {@code expedition:planet}, or the {@code default_block} of a vanilla noise
 * generator. A planet that wants deepslate ores lists them as ores of their own, with a height
 * range.
 *
 * <p>It reads planets from {@link Planets}, whose answers are snapshots, so it is safe on worldgen
 * threads. Only chunks generated after a planet file loads get its ores.
 */
public final class PlanetOresFeature extends Feature<NoneFeatureConfiguration> {
    /** {@code expedition:ore_replaceable}: the blocks a planet's ores may replace. */
    public static final TagKey<Block> ORE_REPLACEABLE = TagKey.create(
            Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(ExpeditionMod.MOD_ID, "ore_replaceable"));

    /** Makes the feature. */
    public PlanetOresFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        Optional<Planet> planet = Planets.forDimension(level.getLevel().dimension(), LogicalSide.SERVER);
        if (planet.isEmpty() || planet.get().ores().isEmpty()) {
            return false;
        }
        List<Vein> veins = veins(planet.get().ores(), baseBlock(context.chunkGenerator()));
        if (veins.isEmpty()) {
            return false;
        }
        int[] weights = new int[veins.size()];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = veins.get(i).ore().weight();
        }
        long total = OreRolls.total(weights);

        RandomSource random = context.random();
        ChunkPos chunk = new ChunkPos(context.origin());
        int bottom = level.getMinBuildHeight();
        int top = level.getMaxBuildHeight() - 1;
        boolean placed = false;
        int count = OreRolls.veins(planet.get().oreDensity(), random.nextDouble());
        for (int i = 0; i < count; i++) {
            Vein vein = veins.get(OreRolls.pick(weights, (long) (random.nextDouble() * total)));
            int x = chunk.getBlockX(random.nextInt(16));
            int z = chunk.getBlockZ(random.nextInt(16));
            int ground = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1;
            int lo = Math.max(vein.ore().minY(), bottom);
            int hi = Math.min(Math.min(vein.ore().maxY(), top), ground);
            if (lo > hi) {
                continue;
            }
            int y = lo + random.nextInt(hi - lo + 1);
            placed |= Feature.ORE.place(vein.config(), level, context.chunkGenerator(), random, new BlockPos(x, y, z));
        }
        return placed;
    }

    /** An ore with the vanilla vein that places it. */
    private record Vein(PlanetOre ore, OreConfiguration config) {}

    private static List<Vein> veins(List<PlanetOre> ores, Optional<BlockState> base) {
        TagMatchTest rock = new TagMatchTest(ORE_REPLACEABLE);
        List<Vein> veins = new ArrayList<>(ores.size());
        for (PlanetOre ore : ores) {
            Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(ore.block());
            if (block.isEmpty()) {
                continue;
            }
            BlockState state = block.get().defaultBlockState();
            List<OreConfiguration.TargetBlockState> targets = new ArrayList<>(2);
            targets.add(OreConfiguration.target(rock, state));
            base.ifPresent(b -> targets.add(OreConfiguration.target(new BlockStateMatchTest(b), state)));
            veins.add(new Vein(ore, new OreConfiguration(targets, ore.veinSize())));
        }
        return veins;
    }

    /** The dimension's own base block, when its generator has one. */
    private static Optional<BlockState> baseBlock(ChunkGenerator generator) {
        if (generator instanceof PlanetChunkGenerator planet) {
            return Optional.of(planet.baseBlock());
        }
        if (generator instanceof NoiseBasedChunkGenerator noise) {
            return Optional.of(noise.generatorSettings().value().defaultBlock());
        }
        return Optional.empty();
    }
}
