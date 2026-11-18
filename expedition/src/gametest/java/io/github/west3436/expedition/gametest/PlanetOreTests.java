package io.github.west3436.expedition.gametest;

import com.mojang.logging.LogUtils;
import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.worldgen.ExpeditionWorldgen;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

/**
 * A planet's ores (#17), on {@code expedition_test:crust} from the {@code planet_test} datapack: a
 * flat dimension of 124 blocks of stone, with features on and a biome with none of its own, so the
 * only ores in it are the ones {@code expedition:planet_ores} places from its planet file: iron of
 * weight 40, veins of 9, from y -32 to 64, and copper of weight 20, veins of 8, at any height, at a
 * density of 1.5. Outside a planet the feature places nothing.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PlanetOreTests {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceKey<Level> CRUST = ResourceKey.create(
            Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(TestDimensions.NAMESPACE, "crust"));

    private static final ResourceKey<Biome> BARE = ResourceKey.create(
            Registries.BIOME, ResourceLocation.fromNamespaceAndPath(TestDimensions.NAMESPACE, "bare"));

    private static final ResourceKey<PlacedFeature> PLANET_ORES = ResourceKey.create(
            Registries.PLACED_FEATURE, ResourceLocation.fromNamespaceAndPath(ExpeditionMod.MOD_ID, "planet_ores"));

    /** The chunks the ores are counted in: four by four, far from the origin. */
    private static final int FIRST_CHUNK = 30;

    private static final int CHUNKS = 4;

    private PlanetOreTests() {}

    // ---- The tests ----------------------------------------------------------------------

    /**
     * Sixteen generated chunks hold both ores, iron about 2.25 times as much as copper (twice the
     * veins, of 9 blocks against 8), iron only near its height range, and about as many ore blocks as
     * the density asks for.
     */
    @GameTest(template = "empty")
    public static void a_planets_ores_are_in_its_ratio(GameTestHelper h) {
        ServerLevel level = level(h, CRUST);
        int iron = 0;
        int copper = 0;
        int lowestIron = Integer.MAX_VALUE;
        int highestIron = Integer.MIN_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int cx = 0; cx < CHUNKS; cx++) {
            for (int cz = 0; cz < CHUNKS; cz++) {
                LevelChunk chunk = level.getChunk(FIRST_CHUNK + cx, FIRST_CHUNK + cz);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = level.getMinBuildHeight(); y < 64; y++) {
                            BlockState state = chunk.getBlockState(pos.set(x, y, z));
                            if (state.is(Blocks.IRON_ORE)) {
                                iron++;
                                lowestIron = Math.min(lowestIron, y);
                                highestIron = Math.max(highestIron, y);
                            } else if (state.is(Blocks.COPPER_ORE)) {
                                copper++;
                            }
                        }
                    }
                }
            }
        }
        LOGGER.info(
                "Planet ores on {}: {} iron (y {} to {}) and {} copper in {} chunks",
                CRUST.location(),
                iron,
                lowestIron,
                highestIron,
                copper,
                CHUNKS * CHUNKS);
        h.assertTrue(iron > 0 && copper > 0, "iron " + iron + ", copper " + copper);
        double ratio = (double) iron / copper;
        h.assertTrue(ratio > 1.6 && ratio < 3.2, "iron to copper is " + ratio + ", not about 2.25");
        // A vein spreads a few blocks from its centre, which is within the range.
        h.assertTrue(lowestIron >= -32 - 6, "iron as low as y " + lowestIron);
        // 16 veins a chunk at density 1, so 24 here: in 16 chunks, about 256 of iron and 128 of copper.
        int veins = CHUNKS * CHUNKS * 24;
        int most = veins * 9;
        h.assertTrue(
                iron + copper > most / 4 && iron + copper <= most,
                (iron + copper) + " ore blocks for about " + veins + " veins of 8 or 9");
        h.succeed();
    }

    /**
     * The feature is in every biome, the Overworld's included, but outside a planet it places
     * nothing: in the Overworld and the Nether it returns at once.
     */
    @GameTest(template = "empty")
    public static void the_feature_places_nothing_outside_a_planet(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        Registry<Biome> biomes = server.registryAccess().registryOrThrow(Registries.BIOME);
        for (ResourceKey<Biome> key : List.of(Biomes.PLAINS, Biomes.NETHER_WASTES, BARE)) {
            h.assertTrue(hasPlanetOres(biomes.getHolderOrThrow(key)), key.location() + " doesn't have the feature");
        }
        for (ResourceKey<Level> key : List.of(Level.OVERWORLD, Level.NETHER)) {
            ServerLevel level = level(h, key);
            BlockPos origin = new BlockPos(16 * 600, level.getMinBuildHeight(), 16 * 600);
            boolean placed = ExpeditionWorldgen.PLANET_ORES
                    .get()
                    .place(
                            NoneFeatureConfiguration.INSTANCE,
                            level,
                            level.getChunkSource().getGenerator(),
                            RandomSource.create(17),
                            origin);
            h.assertFalse(placed, "the feature placed ores in " + key.location());
        }
        h.succeed();
    }

    // ---- Helpers ------------------------------------------------------------------------

    private static boolean hasPlanetOres(Holder<Biome> biome) {
        for (HolderSet<PlacedFeature> step :
                biome.value().getGenerationSettings().features()) {
            for (Holder<PlacedFeature> feature : step) {
                if (feature.is(PLANET_ORES)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ServerLevel level(GameTestHelper h, ResourceKey<Level> key) {
        ServerLevel level = h.getLevel().getServer().getLevel(key);
        if (level == null) {
            throw new GameTestAssertException("no " + key.location());
        }
        return level;
    }
}
