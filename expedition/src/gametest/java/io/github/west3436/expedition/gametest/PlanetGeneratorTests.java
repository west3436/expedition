package io.github.west3436.expedition.gametest;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.api.planet.Planet;
import io.github.west3436.expedition.api.planet.Planets;
import io.github.west3436.expedition.worldgen.PlanetChunkGenerator;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.neoforged.fml.LogicalSide;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * {@code expedition:planet}, the simple terrain generator (#20), on {@code expedition_test:generated}
 * from the {@code planet_test} datapack ({@link TestPacks}): stone under two blocks of tuff under
 * calcite, a bedrock floor, land at 66 ± 8 and a sea of water below 63. The dimension loads with the
 * generator ({@link TestDimensions} makes it a level), its planet file makes it a planet, it
 * generates walkable ground, and it answers {@link ChunkGenerator#getBaseHeight} and {@link
 * ChunkGenerator#getBaseColumn} with what it places.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PlanetGeneratorTests {
    private static final ResourceKey<Level> PLANET = ResourceKey.create(
            Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(TestDimensions.NAMESPACE, "generated"));

    private static final ResourceKey<Biome> PLANET_BIOME = ResourceKey.create(
            Registries.BIOME, ResourceLocation.fromNamespaceAndPath(TestDimensions.NAMESPACE, "generated"));

    /** The chunks the ground is checked in: two by two, far from the origin. */
    private static final int FIRST_CHUNK = 40;

    private static final int CHUNKS = 2;

    private PlanetGeneratorTests() {}

    // ---- The tests ----------------------------------------------------------------------

    /** The dimension loads, made by the generator with the dials its file gives. */
    @GameTest(template = "empty")
    public static void the_planet_dimension_loads_with_the_generator(GameTestHelper h) {
        ServerLevel level = planet(h);
        PlanetChunkGenerator g = generator(level);
        h.assertValueEqual(g.baseBlock(), Blocks.STONE.defaultBlockState(), "base block");
        h.assertValueEqual(g.surfaceBlock(), Blocks.CALCITE.defaultBlockState(), "surface block");
        h.assertValueEqual(g.fillerBlock(), Blocks.TUFF.defaultBlockState(), "filler block");
        h.assertValueEqual(g.fillerDepth(), 2, "filler depth");
        h.assertValueEqual(g.floorBlock(), Optional.of(Blocks.BEDROCK.defaultBlockState()), "floor block");
        h.assertValueEqual(g.groundLevel(), 66, "ground level");
        h.assertValueEqual(g.amplitude(), 8, "amplitude");
        h.assertValueEqual(g.scale(), 128, "scale");
        h.assertValueEqual(g.getSeaLevel(), 63, "sea level");
        h.assertValueEqual(g.seaBlock(), Blocks.WATER.defaultBlockState(), "sea block");
        h.assertTrue(level.getBiome(new BlockPos(1_000, 70, -1_000)).is(PLANET_BIOME), "the biome isn't the file's");
        h.succeed();
    }

    /** Its planet file makes it a planet, with the square in Space the file gives. */
    @GameTest(template = "empty")
    public static void the_planet_dimension_is_a_planet(GameTestHelper h) {
        Optional<Planet> found = Planets.forLevel(planet(h));
        h.assertTrue(
                found.isPresent(),
                "no planet for " + PLANET.location() + "; loaded: " + Planets.all(LogicalSide.SERVER));
        Planet planet = found.get();
        h.assertValueEqual(planet.dimension(), PLANET, "the planet's dimension");
        h.assertValueEqual(planet.space().centerX(), -12000, "the centre's X in Space");
        h.assertValueEqual(planet.space().centerZ(), 4000, "the centre's Z in Space");
        h.assertValueEqual(planet.space().size(), 2048, "the size in Space");
        h.succeed();
    }

    /**
     * Each column of four generated chunks is a bedrock floor, stone, two blocks of tuff and calcite,
     * within 66 ± 8, with water above it up to 62 or air above it, and a step of at most a block to
     * the next column. Some of it is dry land to stand on, and the heightmaps, {@link
     * ChunkGenerator#getBaseHeight} and {@link ChunkGenerator#getBaseColumn} agree with the blocks.
     */
    @GameTest(template = "empty")
    public static void the_planet_generates_walkable_ground(GameTestHelper h) {
        ServerLevel level = planet(h);
        PlanetChunkGenerator g = generator(level);
        RandomState random = level.getChunkSource().randomState();
        int minY = level.getMinBuildHeight();
        int size = 16 * CHUNKS;
        int[][] ground = new int[size][size];
        int dry = 0;
        for (int cx = 0; cx < CHUNKS; cx++) {
            for (int cz = 0; cz < CHUNKS; cz++) {
                LevelChunk chunk = level.getChunk(FIRST_CHUNK + cx, FIRST_CHUNK + cz);
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        int x = chunk.getPos().getBlockX(lx);
                        int z = chunk.getPos().getBlockZ(lz);
                        int y = g.groundY(x, z, level, random);
                        ground[cx * 16 + lx][cz * 16 + lz] = y;
                        String at = " at " + x + ", " + z + " (ground " + y + ")";
                        h.assertTrue(y >= 58 && y <= 74, "the ground is out of 66 ± 8" + at);

                        expect(h, chunk, x, y, z, Blocks.CALCITE, "surface" + at);
                        expect(h, chunk, x, y - 1, z, Blocks.TUFF, "filler" + at);
                        expect(h, chunk, x, y - 2, z, Blocks.TUFF, "filler" + at);
                        expect(h, chunk, x, y - 3, z, Blocks.STONE, "base" + at);
                        expect(h, chunk, x, minY + 1, z, Blocks.STONE, "base" + at);
                        expect(h, chunk, x, minY, z, Blocks.BEDROCK, "floor" + at);
                        int surface;
                        if (y >= 62) {
                            dry++;
                            expect(h, chunk, x, y + 1, z, Blocks.AIR, "air" + at);
                            expect(h, chunk, x, y + 2, z, Blocks.AIR, "air" + at);
                            surface = y + 1;
                        } else {
                            expect(h, chunk, x, y + 1, z, Blocks.WATER, "sea" + at);
                            expect(h, chunk, x, 62, z, Blocks.WATER, "sea" + at);
                            expect(h, chunk, x, 63, z, Blocks.AIR, "air" + at);
                            surface = 63;
                        }

                        h.assertValueEqual(
                                height(chunk, Heightmap.Types.WORLD_SURFACE, x, z), surface, "world surface" + at);
                        h.assertValueEqual(height(chunk, Heightmap.Types.OCEAN_FLOOR, x, z), y + 1, "ocean floor" + at);
                        h.assertValueEqual(
                                g.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, random),
                                surface,
                                "base height to the world surface" + at);
                        h.assertValueEqual(
                                g.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random),
                                y + 1,
                                "base height to the ocean floor" + at);
                        NoiseColumn column = g.getBaseColumn(x, z, level, random);
                        for (int dy = minY; dy <= y + 3; dy += dy < y - 4 ? 17 : 1) {
                            h.assertValueEqual(
                                    column.getBlock(dy),
                                    chunk.getBlockState(new BlockPos(x, dy, z)),
                                    "base column at y " + dy + at);
                        }
                    }
                }
            }
        }
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                if (x + 1 < size) {
                    h.assertTrue(
                            Math.abs(ground[x][z] - ground[x + 1][z]) <= 1,
                            "a step of over a block east of " + x + ", " + z);
                }
                if (z + 1 < size) {
                    h.assertTrue(
                            Math.abs(ground[x][z] - ground[x][z + 1]) <= 1,
                            "a step of over a block south of " + x + ", " + z);
                }
            }
        }
        h.assertTrue(dry > 0, "none of the " + size * size + " columns is dry land");
        h.succeed();
    }

    /**
     * On flat ground at 50 under a sea level of 63, the base height is the sea's top to the world
     * surface and the ground's to the ocean floor, and the column is the ground, then water to 62,
     * then air. With no sea, the world surface is the ground's.
     */
    @GameTest(template = "empty")
    public static void the_base_height_and_column_follow_the_sea(GameTestHelper h) {
        ServerLevel level = planet(h);
        RandomState random = level.getChunkSource().randomState();
        BlockState water = Blocks.WATER.defaultBlockState();
        PlanetChunkGenerator wet = flat(level, 50, Optional.empty(), water);
        h.assertValueEqual(wet.getBaseHeight(5, 9, Heightmap.Types.WORLD_SURFACE_WG, level, random), 63, "wet surface");
        h.assertValueEqual(wet.getBaseHeight(5, 9, Heightmap.Types.MOTION_BLOCKING, level, random), 63, "wet motion");
        h.assertValueEqual(wet.getBaseHeight(5, 9, Heightmap.Types.OCEAN_FLOOR_WG, level, random), 51, "wet floor");
        NoiseColumn column = wet.getBaseColumn(5, 9, level, random);
        h.assertValueEqual(column.getBlock(50), Blocks.GRAVEL.defaultBlockState(), "the surface");
        h.assertValueEqual(column.getBlock(49), Blocks.STONE.defaultBlockState(), "under the surface");
        h.assertValueEqual(column.getBlock(51), water, "the sea's bottom");
        h.assertValueEqual(column.getBlock(62), water, "the sea's top");
        h.assertValueEqual(column.getBlock(63), Blocks.AIR.defaultBlockState(), "above the sea");

        PlanetChunkGenerator dry = flat(level, 50, Optional.empty(), Blocks.AIR.defaultBlockState());
        h.assertValueEqual(dry.getBaseHeight(5, 9, Heightmap.Types.WORLD_SURFACE_WG, level, random), 51, "dry surface");
        h.assertValueEqual(dry.getBaseColumn(5, 9, level, random).getBlock(51), Blocks.AIR.defaultBlockState(), "dry");
        h.succeed();
    }

    /**
     * Ground set above the world is clamped to its top block; ground set below it leaves only the
     * floor, or nothing at all.
     */
    @GameTest(template = "empty")
    public static void ground_outside_the_world_is_clamped(GameTestHelper h) {
        ServerLevel level = planet(h);
        RandomState random = level.getChunkSource().randomState();
        int minY = level.getMinBuildHeight();
        BlockState air = Blocks.AIR.defaultBlockState();
        PlanetChunkGenerator high = flat(level, 1_000, Optional.empty(), air);
        h.assertValueEqual(
                high.getBaseHeight(0, 0, Heightmap.Types.WORLD_SURFACE_WG, level, random),
                level.getMaxBuildHeight(),
                "ground above the world");
        PlanetChunkGenerator low = flat(level, -1_000, Optional.empty(), air);
        h.assertValueEqual(
                low.getBaseHeight(0, 0, Heightmap.Types.WORLD_SURFACE_WG, level, random),
                minY,
                "ground below the world");
        h.assertValueEqual(low.getBaseColumn(0, 0, level, random).getBlock(minY), air, "no ground");
        PlanetChunkGenerator floored = flat(level, -1_000, Optional.of(Blocks.BEDROCK.defaultBlockState()), air);
        h.assertValueEqual(
                floored.getBaseHeight(0, 0, Heightmap.Types.WORLD_SURFACE_WG, level, random), minY + 1, "only a floor");
        h.succeed();
    }

    // ---- Helpers ------------------------------------------------------------------------

    private static ServerLevel planet(GameTestHelper h) {
        ServerLevel level = h.getLevel().getServer().getLevel(PLANET);
        if (level == null) {
            throw new GameTestAssertException(
                    "no " + PLANET.location() + ": the planet_test datapack or TestDimensions didn't load it");
        }
        return level;
    }

    private static PlanetChunkGenerator generator(ServerLevel level) {
        ChunkGenerator g = level.getChunkSource().getGenerator();
        if (!(g instanceof PlanetChunkGenerator planet)) {
            throw new GameTestAssertException(
                    PLANET.location() + " is made by " + g.getClass().getName());
        }
        return planet;
    }

    /** Flat gravel over stone at a height, under a sea level of 63. */
    private static PlanetChunkGenerator flat(
            ServerLevel level, int ground, Optional<BlockState> floor, BlockState sea) {
        Holder<Biome> biome =
                level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(PLANET_BIOME);
        BlockState gravel = Blocks.GRAVEL.defaultBlockState();
        return new PlanetChunkGenerator(
                new FixedBiomeSource(biome),
                Blocks.STONE.defaultBlockState(),
                gravel,
                gravel,
                0,
                floor,
                ground,
                0,
                128,
                63,
                sea);
    }

    private static void expect(GameTestHelper h, LevelChunk chunk, int x, int y, int z, Block block, String what) {
        BlockState state = chunk.getBlockState(new BlockPos(x, y, z));
        if (!state.is(block)) {
            throw new GameTestAssertException(what + ": " + state + " at y " + y + ", not " + block);
        }
    }

    /** A chunk's heightmap as the level's: the y above the highest block that counts. */
    private static int height(LevelChunk chunk, Heightmap.Types type, int x, int z) {
        return chunk.getHeight(type, x & 15, z & 15) + 1;
    }
}
