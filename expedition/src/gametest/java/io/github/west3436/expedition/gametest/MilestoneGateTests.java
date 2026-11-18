package io.github.west3436.expedition.gametest;

import com.mojang.logging.LogUtils;
import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.api.planet.Planet;
import io.github.west3436.expedition.api.planet.PlanetAtmosphere;
import io.github.west3436.expedition.api.planet.PlanetGravity;
import io.github.west3436.expedition.api.planet.Planets;
import io.github.west3436.expedition.planet.NativeAir;
import io.github.west3436.expedition.planet.PlanetAir;
import io.github.west3436.expedition.planet.PlanetSpawns;
import io.github.west3436.expedition.worldgen.PlanetChunkGenerator;
import io.github.west3436.zas.api.atmosphere.AtmosphereSample;
import io.github.west3436.zas.api.atmosphere.AtmosphereState;
import io.github.west3436.zas.api.atmosphere.Atmospheres;
import io.github.west3436.zas.api.event.HazardTickEvent;
import io.github.west3436.zas.api.life.Hazard;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.MobSpawnSettings.SpawnerData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.LogicalSide;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

/**
 * The M3 gate (#21): one planet made of data alone, {@code expedition_test:example} from the {@code
 * example_planet} datapack, has thin methane air, its own gravity, its own ores and its own mobs.
 * The pack is the worked example for pack makers; its {@code README.md} explains every file.
 *
 * <p>The tests share the batch {@value #BATCH}, which no other test is in, so the gate runs and
 * reports as one. They use ZAS³'s simulation API only, never its content, so they pass with {@code
 * -Pcontent=off}.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MilestoneGateTests {
    /** The gate's batch. */
    public static final String BATCH = "m3_gate";

    /** The worked example's dimension, and its planet's. */
    public static final ResourceKey<Level> EXAMPLE = ResourceKey.create(
            Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(TestDimensions.NAMESPACE, "example"));

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation METHANE = ResourceLocation.fromNamespaceAndPath("zas", "methane");
    private static final ResourceLocation NITROGEN = ResourceLocation.fromNamespaceAndPath("zas", "nitrogen");

    /** What the planet file declares. */
    private static final double METHANE_KPA = 4.0;

    private static final double NITROGEN_KPA = 1.0;
    private static final double DAY_K = 180;
    private static final double NIGHT_K = 130;
    private static final double GRAVITY = 0.6;

    /** High over the ground near the surface's centre: open air. */
    private static final BlockPos OPEN_AIR = new BlockPos(8, 160, 8);

    private static final long NOON = 6000;
    private static final long MIDNIGHT = 18000;

    /** The chunks the ores are counted in: four by four, away from the surface's centre. */
    private static final int FIRST_CHUNK = 20;

    private static final int CHUNKS = 4;

    private MilestoneGateTests() {}

    // ---- The gate -----------------------------------------------------------------------

    /**
     * The datapack loads: the dimension is a level made by {@code expedition:planet}, and its planet
     * file loads with everything it declares.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void the_example_planet_loads_from_data_alone(GameTestHelper h) {
        ServerLevel level = example(h);
        h.assertTrue(
                level.getChunkSource().getGenerator() instanceof PlanetChunkGenerator,
                "the example isn't made by expedition:planet");
        Planet planet = planet();
        PlanetAtmosphere air = planet.atmosphere().orElseThrow(() -> new GameTestAssertException("no atmosphere"));
        h.assertValueEqual(air.partialPressures(), Map.of(METHANE, METHANE_KPA, NITROGEN, NITROGEN_KPA), "its air");
        h.assertValueEqual(planet.gravity(), GRAVITY, "its gravity");
        h.assertValueEqual(planet.ores().size(), 3, "its ores");
        h.assertValueEqual(planet.oreDensity(), 2.0, "its ore density");
        h.assertTrue(planet.mobs().replaceExisting(), "its mobs don't replace the biome's");
        h.succeed();
    }

    /**
     * Its open air, read through ZAS³'s own API, is the file's thin methane: 4 kPa of methane and 1
     * of nitrogen, 180 K at noon and 130 K at midnight. Every level's day time is the Overworld's, so
     * the test sets it there and puts it back after.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 1200)
    public static void its_air_is_thin_methane_by_day_and_by_night(GameTestHelper h) {
        ServerLevel overworld = h.getLevel().getServer().overworld();
        ServerLevel example = example(h);
        long before = overworld.getDayTime();
        h.startSequence()
                .thenExecute(() -> {
                    example.setChunkForced(0, 0, true);
                    overworld.setDayTime(NOON);
                })
                .thenWaitUntil(() -> assertAir(h, example, DAY_K))
                .thenExecute(() -> overworld.setDayTime(MIDNIGHT))
                .thenWaitUntil(() -> assertAir(h, example, NIGHT_K))
                .thenExecute(() -> {
                    overworld.setDayTime(before);
                    example.setChunkForced(0, 0, false);
                })
                .thenSucceed();
    }

    /**
     * A mob there carries the planet's gravity: the modifier of 0.6 − 1 on its gravity attribute,
     * so it falls at 0.6 of the Overworld's 0.08.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void its_gravity_is_on_its_mobs(GameTestHelper h) {
        ServerLevel example = example(h);
        h.assertValueEqual(PlanetGravity.of(example), GRAVITY, "the level's gravity");
        Pig pig = EntityType.PIG.create(example);
        if (pig == null) {
            throw new GameTestAssertException("no pig");
        }
        try {
            BlockPos at = new BlockPos(24, 160, 8);
            example.getChunk(at);
            pig.moveTo(Vec3.atBottomCenterOf(at));
            pig.setNoAi(true);
            h.assertTrue(example.addFreshEntity(pig), "the pig wasn't added");
            AttributeModifier modifier = pig.getAttribute(Attributes.GRAVITY).getModifier(PlanetGravity.MODIFIER_ID);
            h.assertTrue(modifier != null, "the pig has no gravity modifier");
            h.assertTrue(Math.abs(modifier.amount() - (GRAVITY - 1)) < 1e-9, "the modifier is " + modifier.amount());
            h.assertTrue(
                    Math.abs(pig.getGravity() - 0.08 * GRAVITY) < 1e-9, "the pig's gravity is " + pig.getGravity());
        } finally {
            pig.discard();
        }
        h.succeed();
    }

    /**
     * Its crust holds its own ores and no others: the biome has no features, so every ore is the
     * planet file's. Iron (weight 30) is about three times copper (weight 10), both veins of 8, and
     * there is coal (weight 20, veins of 12, from y 0 to 80); no vanilla ore appears.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void its_crust_holds_its_own_ores_in_ratio(GameTestHelper h) {
        ServerLevel example = example(h);
        int iron = 0;
        int copper = 0;
        int coal = 0;
        int other = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int cx = 0; cx < CHUNKS; cx++) {
            for (int cz = 0; cz < CHUNKS; cz++) {
                LevelChunk chunk = example.getChunk(FIRST_CHUNK + cx, FIRST_CHUNK + cz);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = example.getMinBuildHeight(); y < 90; y++) {
                            BlockState state = chunk.getBlockState(pos.set(x, y, z));
                            if (state.is(Blocks.IRON_ORE)) {
                                iron++;
                            } else if (state.is(Blocks.COPPER_ORE)) {
                                copper++;
                            } else if (state.is(Blocks.COAL_ORE)) {
                                coal++;
                            } else if (state.is(Blocks.DIAMOND_ORE)
                                    || state.is(Blocks.GOLD_ORE)
                                    || state.is(Blocks.REDSTONE_ORE)
                                    || state.is(Blocks.LAPIS_ORE)
                                    || state.is(Blocks.DEEPSLATE_IRON_ORE)) {
                                other++;
                            }
                        }
                    }
                }
            }
        }
        LOGGER.info(
                "The example planet's ores in {} chunks: {} iron, {} copper, {} coal",
                CHUNKS * CHUNKS,
                iron,
                copper,
                coal);
        h.assertTrue(iron > 0 && copper > 0 && coal > 0, "iron " + iron + ", copper " + copper + ", coal " + coal);
        double ratio = (double) iron / copper;
        h.assertTrue(ratio > 2.2 && ratio < 4.0, "iron to copper is " + ratio + ", not about 3");
        h.assertValueEqual(other, 0, "ores the planet doesn't list");
        h.succeed();
    }

    /**
     * It spawns its own mobs, as natural spawning asks for them (the biome's list, then NeoForge's
     * potential-spawns event): strays as its monsters and goats as its creatures, each with the
     * file's weight and group. The biome spawns nothing, so a category the file leaves out spawns
     * nothing. Strays and goats are its natives; a pig isn't.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void it_spawns_its_own_mobs(GameTestHelper h) {
        ServerLevel example = example(h);
        BlockPos pos = new BlockPos(8, 64, 8);

        List<SpawnerData> monsters = PlanetMobTests.spawns(example, MobCategory.MONSTER, pos);
        h.assertValueEqual(PlanetMobTests.types(monsters), List.of(EntityType.STRAY), "its monsters");
        SpawnerData stray = monsters.getFirst();
        h.assertTrue(
                stray.getWeight().asInt() == 10 && stray.minCount == 1 && stray.maxCount == 2,
                "the strays' weight and group");

        List<SpawnerData> creatures = PlanetMobTests.spawns(example, MobCategory.CREATURE, pos);
        h.assertValueEqual(PlanetMobTests.types(creatures), List.of(EntityType.GOAT), "its creatures");
        SpawnerData goat = creatures.getFirst();
        h.assertTrue(
                goat.getWeight().asInt() == 5 && goat.minCount == 1 && goat.maxCount == 2,
                "the goats' weight and group");

        h.assertValueEqual(
                PlanetMobTests.spawns(example, MobCategory.AMBIENT, pos), List.<SpawnerData>of(), "its ambient mobs");

        h.assertTrue(PlanetSpawns.isNative(EXAMPLE, EntityType.STRAY), "the stray isn't the example's");
        h.assertTrue(PlanetSpawns.isNative(EXAMPLE, EntityType.GOAT), "the goat isn't the example's");
        h.assertFalse(PlanetSpawns.isNative(EXAMPLE, EntityType.PIG), "the pig is the example's");
        h.succeed();
    }

    /**
     * Its goat lives in its air and a pig beside it doesn't: ZAS³'s hazards harm the pig, and every
     * one that comes to the goat is spared it, low pressure among them. Waits, as {@link
     * PlanetMobTests} does, for the pig's first damage and a hazard to have reached the goat, and
     * forces the chunk to entity-ticking first, since ZAS³ only checks entities that tick.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 1200)
    public static void its_goat_lives_in_its_air_and_a_pig_doesnt(GameTestHelper h) {
        ServerLevel example = example(h);
        ChunkPos chunk = new ChunkPos(-4, -4);
        h.assertTrue(PlanetAir.appliesTo(EXAMPLE), "the example's air isn't the planet's");

        Map<LivingEntity, List<HazardTickEvent>> heard = new HashMap<>();
        Consumer<HazardTickEvent> listener = e -> {
            List<HazardTickEvent> mine = heard.get(e.entity());
            if (mine != null) {
                mine.add(e);
            }
        };
        Mob[] mobs = new Mob[2];
        h.startSequence()
                .thenExecute(() -> example.setChunkForced(chunk.x, chunk.z, true))
                .thenWaitUntil(() -> h.assertTrue(
                        example.isPositionEntityTicking(chunk.getMiddleBlockPosition(64)),
                        "the chunk isn't loaded yet"))
                .thenExecute(() -> {
                    mobs[0] = PlanetMobTests.place(
                            h, example, EntityType.GOAT, chunk.getMinBlockX() + 4, chunk.getMinBlockZ() + 4);
                    mobs[1] = PlanetMobTests.place(
                            h, example, EntityType.PIG, chunk.getMinBlockX() + 11, chunk.getMinBlockZ() + 11);
                    heard.put(mobs[0], new ArrayList<>());
                    heard.put(mobs[1], new ArrayList<>());
                    NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, HazardTickEvent.class, listener);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(NativeAir.adapted(mobs[0]), "the goat isn't adapted to the example's air");
                    h.assertFalse(NativeAir.adapted(mobs[1]), "a pig is adapted to the example's air");
                    h.assertTrue(PlanetMobTests.lost(mobs[1]) > 0, "the pig isn't harmed yet");
                    h.assertFalse(heard.get(mobs[0]).isEmpty(), "no hazard has come to the goat yet");
                })
                .thenExecute(() -> {
                    try {
                        List<HazardTickEvent> goat = heard.get(mobs[0]);
                        List<HazardTickEvent> pig = heard.get(mobs[1]);
                        h.assertTrue(
                                goat.stream().allMatch(HazardTickEvent::isCanceled),
                                "a hazard wasn't spared the goat: " + PlanetMobTests.hazards(goat));
                        h.assertTrue(
                                goat.stream().anyMatch(e -> e.hazard() == Hazard.LOW_PRESSURE),
                                "the goat wasn't spared the low pressure: " + PlanetMobTests.hazards(goat));
                        h.assertValueEqual(PlanetMobTests.lost(mobs[0]), 0f, "the goat's lost health");
                        h.assertTrue(
                                pig.stream().noneMatch(HazardTickEvent::isCanceled),
                                "a hazard was spared the pig: " + PlanetMobTests.hazards(pig));
                    } finally {
                        NeoForge.EVENT_BUS.unregister(listener);
                        for (Mob mob : mobs) {
                            mob.discard();
                        }
                        example.setChunkForced(chunk.x, chunk.z, false);
                    }
                })
                .thenSucceed();
    }

    // ---- Helpers ------------------------------------------------------------------------

    private static ServerLevel example(GameTestHelper h) {
        ServerLevel level = h.getLevel().getServer().getLevel(EXAMPLE);
        if (level == null) {
            throw new GameTestAssertException("no " + EXAMPLE.location() + ": the example_planet datapack didn't load");
        }
        return level;
    }

    private static Planet planet() {
        return Planets.forDimension(EXAMPLE, LogicalSide.SERVER)
                .orElseThrow(() -> new GameTestAssertException("no planet for " + EXAMPLE.location()));
    }

    /** The open air over the example: ambient, the declared mix, and the temperature. */
    private static void assertAir(GameTestHelper h, ServerLevel level, double temperature) {
        AtmosphereSample air = Atmospheres.at(level, OPEN_AIR);
        h.assertValueEqual(air.state(), AtmosphereState.AMBIENT, "the state of the open air");
        Map<ResourceLocation, Double> pressures = air.partialPressures();
        h.assertTrue(
                pressures.keySet().equals(Set.of(METHANE, NITROGEN))
                        && Math.abs(pressures.get(METHANE) - METHANE_KPA) < 1e-3
                        && Math.abs(pressures.get(NITROGEN) - NITROGEN_KPA) < 1e-3,
                "the open air's mix is " + pressures);
        h.assertTrue(
                Math.abs(air.pressure() - (METHANE_KPA + NITROGEN_KPA)) < 1e-3,
                "the open air's pressure is " + air.pressure());
        h.assertTrue(
                Math.abs(air.temperature() - temperature) < 0.5,
                "the open air is " + air.temperature() + " K, not " + temperature + " K");
    }
}
