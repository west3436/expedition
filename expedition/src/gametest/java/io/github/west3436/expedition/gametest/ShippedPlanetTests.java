package io.github.west3436.expedition.gametest;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.api.planet.Planet;
import io.github.west3436.expedition.api.planet.PlanetAtmosphere;
import io.github.west3436.expedition.api.planet.PlanetGravity;
import io.github.west3436.expedition.api.planet.Planets;
import io.github.west3436.expedition.api.planet.SpaceSquare;
import io.github.west3436.expedition.api.space.Space;
import io.github.west3436.expedition.planet.PlanetAir;
import io.github.west3436.zas.api.atmosphere.AtmosphereSample;
import io.github.west3436.zas.api.atmosphere.AtmosphereState;
import io.github.west3436.zas.api.atmosphere.Atmospheres;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.LogicalSide;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The planets Expedition ships (#30): the Overworld, the Nether, the End and the moon, each with
 * its square in Space as decided; none of the vanilla three carries an atmosphere, so ZAS³'s own
 * air stands; and the moon is a level of the simple terrain generator with vacuum and low gravity.
 *
 * <p>The {@code planet_test} datapack replaces the Nether's file with one that also denies bastions
 * ({@link PlanetStructureTests}); its square and the rest are the shipped file's.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ShippedPlanetTests {
    private static final String BATCH = "shipped_planets";

    /** The moon's dimension. */
    public static final ResourceKey<Level> MOON =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("expedition", "moon"));

    private static final double MOON_GRAVITY = 0.17;

    private static final int TIMEOUT = 1200;

    private ShippedPlanetTests() {}

    /** Each planet loads with the square, image, parent and access the table in #30 gives. */
    @GameTest(template = "empty", batch = BATCH)
    public static void the_four_planets_load_with_their_squares(GameTestHelper h) {
        Planet overworld = planet(h, "overworld", Level.OVERWORLD);
        Planet nether = planet(h, "the_nether", Level.NETHER);
        Planet end = planet(h, "the_end", Level.END);
        Planet moon = planet(h, "moon", MOON);

        square(h, overworld, 0, 0, 8192, "overworld");
        square(h, moon, 0, -7120, 2048, "moon");
        square(h, nether, 30_000, 0, 8192, "the_nether");
        square(h, end, 0, 300_000, 8192, "the_end");

        // The moon sits 2,000 blocks past the Overworld's edge.
        h.assertValueEqual(overworld.space().minZ() - moon.space().maxZ(), 2000, "the gap from the Overworld");
        h.assertValueEqual(moon.space().parent(), Optional.of(overworld.id()), "the moon's parent");
        for (Planet planet : List.of(overworld, nether, end)) {
            h.assertTrue(planet.space().parent().isEmpty(), planet.id() + " has a parent");
        }

        h.assertFalse(overworld.access().hiddenUntilScanned(), "the Overworld is hidden");
        h.assertFalse(moon.access().hiddenUntilScanned(), "the moon is hidden");
        h.assertFalse(nether.access().hiddenUntilScanned(), "the Nether is hidden");
        h.assertTrue(end.access().hiddenUntilScanned(), "the End isn't hidden");

        h.assertTrue(
                Space.planetAt(0, -7120, LogicalSide.SERVER).equals(Optional.of(moon)),
                "the moon's centre in Space isn't over the moon");
        h.assertTrue(
                Space.planetAt(0, -5000, LogicalSide.SERVER).isEmpty(), "the gap between the Overworld and the moon");
        h.succeed();
    }

    /**
     * The vanilla three carry no atmosphere and normal gravity, so ZAS³'s air for them stands and
     * Expedition hands it nothing.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void the_vanilla_planets_leave_their_air_to_zas(GameTestHelper h) {
        for (ResourceKey<Level> dimension : List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
            Planet planet = Planets.forDimension(dimension, LogicalSide.SERVER).orElseThrow();
            h.assertTrue(planet.atmosphere().isEmpty(), planet.id() + " carries an atmosphere");
            h.assertValueEqual(planet.gravity(), PlanetGravity.NORMAL, planet.id() + "'s gravity");
            h.assertFalse(PlanetAir.appliesTo(dimension), dimension.location() + "'s air is the planet file's");
        }
        h.succeed();
    }

    /**
     * The moon is a level: its ground is the simple terrain generator's, light grey powder over
     * andesite over stone on a bedrock floor, with nothing above. Its planet gives it a vacuum and a
     * gravity of 0.17, and a pig there carries it.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void the_moon_is_a_level_of_low_gravity(GameTestHelper h) {
        ServerLevel moon = moon(h);
        Planet planet = Planets.forLevel(moon).orElseThrow();
        h.assertValueEqual(PlanetGravity.of(moon), MOON_GRAVITY, "the moon's gravity");
        PlanetAtmosphere air = planet.atmosphere().orElseThrow();
        h.assertTrue(air.vacuum(), "the moon's atmosphere isn't a vacuum");

        LevelChunk chunk = moon.getChunk(0, 0);
        for (int x = 0; x < 16; x += 5) {
            for (int z = 0; z < 16; z += 5) {
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                String at = " at " + x + ", " + z + " (ground " + y + ")";
                h.assertTrue(y >= 64 - 14 && y <= 64 + 14, "the ground is out of 64 ± 14" + at);
                expect(h, chunk, x, y, z, Blocks.LIGHT_GRAY_CONCRETE_POWDER, "surface" + at);
                expect(h, chunk, x, y - 1, z, Blocks.ANDESITE, "filler" + at);
                expect(h, chunk, x, y - 4, z, Blocks.ANDESITE, "filler" + at);
                expect(h, chunk, x, y + 1, z, Blocks.AIR, "air" + at);
                expect(h, chunk, x, moon.getMinBuildHeight(), z, Blocks.BEDROCK, "floor" + at);
            }
        }

        Pig pig = EntityType.PIG.create(moon);
        if (pig == null) {
            throw new GameTestAssertException("no pig");
        }
        pig.moveTo(Vec3.atBottomCenterOf(new BlockPos(24, 120, 8)));
        pig.setNoAi(true);
        moon.getChunk(1, 0);
        h.assertTrue(moon.addFreshEntity(pig), "the pig wasn't added to the moon");
        try {
            h.assertTrue(
                    Math.abs(pig.getGravity() - 0.08 * MOON_GRAVITY) < 1e-9,
                    "the pig's gravity on the moon is " + pig.getGravity());
        } finally {
            pig.discard();
        }
        h.succeed();
    }

    /** ZAS³ reads the moon's open air as its planet file gives it: a vacuum. */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void zas_reads_a_vacuum_on_the_moon(GameTestHelper h) {
        ServerLevel moon = moon(h);
        BlockPos above = new BlockPos(40, 150, 8);
        h.assertTrue(PlanetAir.appliesTo(MOON), "the moon's air isn't its planet's");
        moon.setChunkForced(2, 0, true);
        h.startSequence()
                .thenWaitUntil(() -> {
                    AtmosphereSample sample = Atmospheres.at(moon, above);
                    h.assertValueEqual(sample.state(), AtmosphereState.AMBIENT, "the state of the moon's open air");
                    h.assertTrue(sample.pressure() == 0, "the moon's open air is at " + sample.pressure() + " kPa");
                })
                .thenExecute(() -> moon.setChunkForced(2, 0, false))
                .thenSucceed();
    }

    // ---- Helpers ------------------------------------------------------------------------

    private static Planet planet(GameTestHelper h, String path, ResourceKey<Level> dimension) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(ExpeditionMod.MOD_ID, path);
        Planet planet = Planets.byId(id, LogicalSide.SERVER)
                .orElseThrow(() -> new GameTestAssertException(
                        "no planet " + id + "; loaded: " + Planets.all(LogicalSide.SERVER)));
        h.assertValueEqual(planet.dimension(), dimension, id + "'s dimension");
        h.assertValueEqual(
                Planets.forDimension(dimension, LogicalSide.SERVER),
                Optional.of(planet),
                "the planet of " + dimension.location());
        return planet;
    }

    private static void square(GameTestHelper h, Planet planet, int x, int z, int size, String image) {
        SpaceSquare square = planet.space();
        h.assertValueEqual(square.centerX(), x, planet.id() + "'s centre X in Space");
        h.assertValueEqual(square.centerZ(), z, planet.id() + "'s centre Z in Space");
        h.assertValueEqual(square.size(), size, planet.id() + "'s size in Space");
        h.assertValueEqual(
                square.image(),
                Optional.of(ResourceLocation.fromNamespaceAndPath(
                        ExpeditionMod.MOD_ID, "textures/planet/" + image + ".png")),
                planet.id() + "'s image");
        h.assertValueEqual(planet.surface().centerX(), 0, planet.id() + "'s surface centre X");
        h.assertValueEqual(planet.surface().centerZ(), 0, planet.id() + "'s surface centre Z");
    }

    private static ServerLevel moon(GameTestHelper h) {
        ServerLevel moon = h.getLevel().getServer().getLevel(MOON);
        if (moon == null) {
            throw new GameTestAssertException("the moon isn't a level on the server");
        }
        return moon;
    }

    private static void expect(
            GameTestHelper h,
            LevelChunk chunk,
            int x,
            int y,
            int z,
            net.minecraft.world.level.block.Block block,
            String what) {
        BlockPos p = new BlockPos(chunk.getPos().getBlockX(x), y, chunk.getPos().getBlockZ(z));
        h.assertTrue(chunk.getBlockState(p).is(block), what + ": " + chunk.getBlockState(p));
    }
}
