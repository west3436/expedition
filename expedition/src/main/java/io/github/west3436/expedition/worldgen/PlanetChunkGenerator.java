package io.github.west3436.expedition.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.west3436.expedition.ExpeditionMod;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * {@code expedition:planet}: a simple terrain generator for pack makers who would rather set a few
 * dials than write noise settings (#20). Rolling land of a base block, a few filler blocks and a
 * surface block, with a sea of one block up to a sea level, and a floor if asked for. It has no
 * caves, carvers or aquifers; the biome source's biomes still bring their features, structures and
 * mobs.
 *
 * <p>The land comes from {@link PlanetTerrain}, seeded from the world's seed, so a world always
 * generates the same planet. Two dimensions with the same dials in one world have the same land.
 * {@link #getBaseHeight} and {@link #getBaseColumn} answer from the same columns that {@link
 * #fillFromNoise} places, so structures and spawning see the ground that is there.
 *
 * <p>A dimension file names it like any other generator:
 *
 * <pre>{@code
 * "generator": {
 *   "type": "expedition:planet",
 *   "biome_source": { "type": "minecraft:fixed", "biome": "mypack:kepler_plains" },
 *   "base_block": "minecraft:stone",
 *   "surface_block": "minecraft:gravel",
 *   "sea_block": "minecraft:air",
 *   "amplitude": 12
 * }
 * }</pre>
 *
 * <p>Every field but {@code biome_source} may be left out:
 *
 * <ul>
 *   <li>{@code base_block}: the bulk of the planet ({@code minecraft:stone})
 *   <li>{@code surface_block}: the top block of the ground (the base block)
 *   <li>{@code filler_block}: the blocks under the surface block (the surface block)
 *   <li>{@code filler_depth}: how many filler blocks there are, 0 to 64 (3)
 *   <li>{@code floor_block}: one layer at the bottom of the world, such as bedrock (none)
 *   <li>{@code ground_level}: the land's middle height (64)
 *   <li>{@code amplitude}: how far the land rises above or falls below the ground level, 0 to 512
 *       (8)
 *   <li>{@code scale}: the width in blocks of the largest hills, 1 to 65,536 (128)
 *   <li>{@code sea_level}: the sea fills the blocks above the ground and below this height (63)
 *   <li>{@code sea_block}: what the sea is made of; {@code minecraft:air} for none ({@code
 *       minecraft:water})
 * </ul>
 *
 * <p>A block is its id, such as {@code "minecraft:stone"}, or a block state as vanilla writes one,
 * such as {@code {"Name": "minecraft:oak_log", "Properties": {"axis": "x"}}}.
 */
public final class PlanetChunkGenerator extends ChunkGenerator {
    /** A block's id, for its default state, or a whole block state. Encodes as a block state. */
    private static final Codec<BlockState> BLOCK = Codec.withAlternative(
            BlockState.CODEC,
            BuiltInRegistries.BLOCK.byNameCodec().xmap(Block::defaultBlockState, BlockState::getBlock));

    private static final Codec<Integer> HEIGHT = Codec.intRange(DimensionType.MIN_Y, DimensionType.MAX_Y);

    /** The codec registered as {@code expedition:planet}. */
    public static final MapCodec<PlanetChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource),
                    BLOCK.optionalFieldOf("base_block", Blocks.STONE.defaultBlockState())
                            .forGetter(PlanetChunkGenerator::baseBlock),
                    BLOCK.optionalFieldOf("surface_block").forGetter(g -> Optional.of(g.surfaceBlock())),
                    BLOCK.optionalFieldOf("filler_block").forGetter(g -> Optional.of(g.fillerBlock())),
                    Codec.intRange(0, 64)
                            .optionalFieldOf("filler_depth", 3)
                            .forGetter(PlanetChunkGenerator::fillerDepth),
                    BLOCK.optionalFieldOf("floor_block").forGetter(PlanetChunkGenerator::floorBlock),
                    HEIGHT.optionalFieldOf("ground_level", 64).forGetter(PlanetChunkGenerator::groundLevel),
                    Codec.intRange(0, 512).optionalFieldOf("amplitude", 8).forGetter(PlanetChunkGenerator::amplitude),
                    Codec.intRange(1, 65_536).optionalFieldOf("scale", 128).forGetter(PlanetChunkGenerator::scale),
                    HEIGHT.optionalFieldOf("sea_level", 63).forGetter(PlanetChunkGenerator::getSeaLevel),
                    BLOCK.optionalFieldOf("sea_block", Blocks.WATER.defaultBlockState())
                            .forGetter(PlanetChunkGenerator::seaBlock))
            .apply(instance, instance.stable(PlanetChunkGenerator::fromCodec)));

    /** Names the world-seeded random the land's seed is drawn from. */
    private static final ResourceLocation TERRAIN_RANDOM =
            ResourceLocation.fromNamespaceAndPath(ExpeditionMod.MOD_ID, "planet_terrain");

    /**
     * What {@link #getMinY} and {@link #getGenDepth} answer: the Overworld's, since a generator isn't
     * told its dimension's height. Vanilla reads them only to bound some features and structures
     * (ore heights given from the bottom or top, mineshafts, strongholds), clamped to the level's own
     * height. The land itself fills whatever height the dimension has.
     */
    private static final int MIN_Y = -64;

    private static final int GEN_DEPTH = 384;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final BlockState baseBlock;
    private final BlockState surfaceBlock;
    private final BlockState fillerBlock;
    private final int fillerDepth;
    private final Optional<BlockState> floorBlock;
    private final int groundLevel;
    private final int amplitude;
    private final int scale;
    private final int seaLevel;
    private final BlockState seaBlock;

    /** The land for the last random state asked about: in practice, the level's one. */
    private volatile Terrain terrain;

    private record Terrain(RandomState random, PlanetTerrain land) {}

    /**
     * A generator with every dial given.
     *
     * @param biomeSource where the biomes come from
     * @param baseBlock the bulk of the planet
     * @param surfaceBlock the top block of the ground
     * @param fillerBlock the blocks under the surface block
     * @param fillerDepth how many filler blocks there are, 0 or more
     * @param floorBlock one layer at the bottom of the world, if any
     * @param groundLevel the land's middle height
     * @param amplitude how far the land rises above or falls below the ground level, 0 or more
     * @param scale the width in blocks of the largest hills, 1 or more
     * @param seaLevel the sea fills the blocks above the ground and below this height
     * @param seaBlock what the sea is made of, air for none
     */
    public PlanetChunkGenerator(
            BiomeSource biomeSource,
            BlockState baseBlock,
            BlockState surfaceBlock,
            BlockState fillerBlock,
            int fillerDepth,
            Optional<BlockState> floorBlock,
            int groundLevel,
            int amplitude,
            int scale,
            int seaLevel,
            BlockState seaBlock) {
        super(biomeSource);
        if (fillerDepth < 0 || amplitude < 0 || scale < 1) {
            throw new IllegalArgumentException("filler depth " + fillerDepth + ", amplitude " + amplitude + " or scale "
                    + scale + " is out of range");
        }
        this.baseBlock = baseBlock;
        this.surfaceBlock = surfaceBlock;
        this.fillerBlock = fillerBlock;
        this.fillerDepth = fillerDepth;
        this.floorBlock = floorBlock;
        this.groundLevel = groundLevel;
        this.amplitude = amplitude;
        this.scale = scale;
        this.seaLevel = seaLevel;
        this.seaBlock = seaBlock;
    }

    private static PlanetChunkGenerator fromCodec(
            BiomeSource biomeSource,
            BlockState baseBlock,
            Optional<BlockState> surfaceBlock,
            Optional<BlockState> fillerBlock,
            int fillerDepth,
            Optional<BlockState> floorBlock,
            int groundLevel,
            int amplitude,
            int scale,
            int seaLevel,
            BlockState seaBlock) {
        BlockState surface = surfaceBlock.orElse(baseBlock);
        return new PlanetChunkGenerator(
                biomeSource,
                baseBlock,
                surface,
                fillerBlock.orElse(surface),
                fillerDepth,
                floorBlock,
                groundLevel,
                amplitude,
                scale,
                seaLevel,
                seaBlock);
    }

    /** @return the bulk of the planet */
    public BlockState baseBlock() {
        return baseBlock;
    }

    /** @return the top block of the ground */
    public BlockState surfaceBlock() {
        return surfaceBlock;
    }

    /** @return the blocks under the surface block */
    public BlockState fillerBlock() {
        return fillerBlock;
    }

    /** @return how many filler blocks lie under the surface block */
    public int fillerDepth() {
        return fillerDepth;
    }

    /** @return the layer at the bottom of the world, if any */
    public Optional<BlockState> floorBlock() {
        return floorBlock;
    }

    /** @return the land's middle height */
    public int groundLevel() {
        return groundLevel;
    }

    /** @return how far the land rises above or falls below the ground level */
    public int amplitude() {
        return amplitude;
    }

    /** @return the width in blocks of the largest hills */
    public int scale() {
        return scale;
    }

    /** @return what the sea is made of */
    public BlockState seaBlock() {
        return seaBlock;
    }

    /**
     * The y of a column's surface block in a level: the land's height, clamped to the level. One
     * below the level's bottom means the column has no ground.
     *
     * @param x the column's block x
     * @param z the column's block z
     * @param level the level's height
     * @param random the level's random state, which seeds the land
     * @return the y of the column's surface block
     */
    public int groundY(int x, int z, LevelHeightAccessor level, RandomState random) {
        return Mth.clamp(land(random).groundY(x, z), level.getMinBuildHeight() - 1, level.getMaxBuildHeight() - 1);
    }

    private PlanetTerrain land(RandomState random) {
        Terrain t = terrain;
        if (t == null || t.random() != random) {
            long seed =
                    random.getOrCreateRandomFactory(TERRAIN_RANDOM).at(0, 0, 0).nextLong();
            t = new Terrain(random, new PlanetTerrain(seed, groundLevel, amplitude, scale));
            terrain = t;
        }
        return t.land();
    }

    /** The block at a height of a column whose surface block is at {@code groundY}. */
    private BlockState blockAt(int y, int groundY, int minY) {
        if (y == minY && floorBlock.isPresent()) {
            return floorBlock.get();
        }
        return switch (PlanetTerrain.layer(y, groundY, fillerDepth, seaLevel)) {
            case BASE -> baseBlock;
            case FILLER -> fillerBlock;
            case SURFACE -> surfaceBlock;
            case SEA -> seaBlock;
            case AIR -> AIR;
        };
    }

    /** The highest y of a column that may hold anything but air. */
    private int topY(int groundY, int minY, int maxY) {
        int top = seaBlock.isAir() ? groundY : Math.max(groundY, seaLevel - 1);
        if (floorBlock.isPresent()) {
            top = Math.max(top, minY);
        }
        return Math.min(top, maxY);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(
            Blender blender, RandomState random, StructureManager structureManager, ChunkAccess chunk) {
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight() - 1;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int groundY = groundY(pos.getBlockX(x), pos.getBlockZ(z), chunk, random);
                // Top down, as vanilla fills, so each heightmap stops at its first match.
                int sectionIndex = -1;
                LevelChunkSection section = null;
                for (int y = topY(groundY, minY, maxY); y >= minY; y--) {
                    BlockState state = blockAt(y, groundY, minY);
                    if (state.isAir()) {
                        continue;
                    }
                    int index = chunk.getSectionIndex(y);
                    if (index != sectionIndex) {
                        sectionIndex = index;
                        section = chunk.getSection(index);
                    }
                    section.setBlockState(x, y & 15, z, state, false);
                    oceanFloor.update(x, y, z, state);
                    worldSurface.update(x, y, z, state);
                }
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        int minY = level.getMinBuildHeight();
        int groundY = groundY(x, z, level, random);
        Predicate<BlockState> counts = type.isOpaque();
        for (int y = topY(groundY, minY, level.getMaxBuildHeight() - 1); y >= minY; y--) {
            if (counts.test(blockAt(y, groundY, minY))) {
                return y + 1;
            }
        }
        return minY;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        int minY = level.getMinBuildHeight();
        int groundY = groundY(x, z, level, random);
        BlockState[] states = new BlockState[level.getHeight()];
        for (int i = 0; i < states.length; i++) {
            states[i] = blockAt(minY + i, groundY, minY);
        }
        return new NoiseColumn(minY, states);
    }

    @Override
    public int getSpawnHeight(LevelHeightAccessor level) {
        return Mth.clamp(groundLevel + 1, level.getMinBuildHeight(), level.getMaxBuildHeight());
    }

    /** No surface rules: {@link #fillFromNoise} places the surface itself. */
    @Override
    public void buildSurface(
            WorldGenRegion level, StructureManager structureManager, RandomState random, ChunkAccess chunk) {}

    /** No caves or canyons. */
    @Override
    public void applyCarvers(
            WorldGenRegion level,
            long seed,
            RandomState random,
            BiomeManager biomeManager,
            StructureManager structureManager,
            ChunkAccess chunk,
            GenerationStep.Carving step) {}

    /** The biome's creatures, placed as a chunk generates, as vanilla's noise generator does. */
    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {
        ChunkPos pos = level.getCenter();
        Holder<Biome> biome = level.getBiome(pos.getWorldPosition().atY(level.getMaxBuildHeight() - 1));
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
        random.setDecorationSeed(level.getSeed(), pos.getMinBlockX(), pos.getMinBlockZ());
        NaturalSpawner.spawnMobsForChunkGeneration(level, biome, pos, random);
    }

    @Override
    public int getGenDepth() {
        return GEN_DEPTH;
    }

    @Override
    public int getSeaLevel() {
        return seaLevel;
    }

    @Override
    public int getMinY() {
        return MIN_Y;
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
        info.add("Planet ground: " + land(random).groundY(pos.getX(), pos.getZ()));
    }
}
