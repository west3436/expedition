package io.github.west3436.expedition.gametest;

import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.api.planet.Planet;
import io.github.west3436.expedition.api.planet.PlanetAtmosphere;
import io.github.west3436.expedition.api.planet.PlanetMob;
import io.github.west3436.expedition.api.planet.PlanetOre;
import io.github.west3436.expedition.api.planet.Planets;
import io.github.west3436.expedition.api.planet.StructureRef;
import io.github.west3436.expedition.api.space.Space;
import io.github.west3436.expedition.command.PlanetCommand;
import io.github.west3436.expedition.planet.PlanetLoader;
import io.github.west3436.expedition.planet.PlanetProblem;
import io.github.west3436.expedition.planet.PlanetRegistry;
import io.github.west3436.expedition.planet.References;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.fml.LogicalSide;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Planet files (#14), loaded from the GameTests' {@code planet_test} datapack: the test planet
 * {@code expedition_test:kepler} loads on the server, the API answers for its dimension with the
 * file's values, and {@code /expedition planet validate} finds nothing wrong. The format's errors
 * are unit tests ({@code PlanetFormatTest}, {@code PlanetLoaderTest}).
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PlanetTests {
    private static final String BATCH = "planets";

    private static final ResourceLocation KEPLER = TestDimensions.KEPLER.location();

    private PlanetTests() {}

    /** The test planet loads, with every value its file gives, and the defaults for the rest. */
    @GameTest(template = "empty", batch = BATCH)
    public static void the_test_planet_loads(GameTestHelper h) {
        Optional<Planet> found = Planets.forDimension(TestDimensions.KEPLER, LogicalSide.SERVER);
        h.assertTrue(found.isPresent(), "no planet for " + KEPLER + "; loaded: " + Planets.all(LogicalSide.SERVER));
        Planet kepler = found.get();
        h.assertValueEqual(kepler.id(), KEPLER, "the id");
        h.assertValueEqual(kepler.space().centerX(), 12000, "the centre's X in Space");
        h.assertValueEqual(kepler.space().centerZ(), -4000, "the centre's Z in Space");
        h.assertValueEqual(kepler.space().size(), 4096, "the size in Space");
        h.assertTrue(kepler.space().parent().isEmpty(), "a parent");
        h.assertValueEqual(kepler.surface().centerX(), 0, "the surface centre's X");

        PlanetAtmosphere air = kepler.atmosphere().orElse(null);
        h.assertTrue(air != null, "no atmosphere");
        h.assertValueEqual(air.partialPressure(gas("methane")), 6.0, "the methane");
        h.assertValueEqual(air.partialPressure(gas("nitrogen")), 2.5, "the nitrogen");
        h.assertValueEqual(air.totalPressure(), 8.5, "the total pressure");
        h.assertValueEqual(air.dayTemperature(), 210.0, "the day temperature");
        h.assertValueEqual(air.nightTemperature(), 150.0, "the night temperature");
        h.assertFalse(air.vacuum(), "a vacuum");

        h.assertValueEqual(kepler.gravity(), 0.38, "the gravity");
        h.assertValueEqual(kepler.oreDensity(), 1.5, "the ore density");
        h.assertValueEqual(
                kepler.ores(),
                List.of(
                        new PlanetOre(ResourceLocation.withDefaultNamespace("iron_ore"), 40, 9, -32, 64),
                        new PlanetOre(
                                ResourceLocation.withDefaultNamespace("copper_ore"),
                                20,
                                PlanetOre.DEFAULT_VEIN_SIZE,
                                PlanetOre.MIN_Y,
                                PlanetOre.MAX_Y)),
                "the ores");

        h.assertTrue(kepler.mobs().replaceExisting(), "the mobs don't replace the biomes'");
        h.assertValueEqual(
                kepler.mobs().spawns(MobCategory.MONSTER),
                List.of(new PlanetMob(ResourceLocation.withDefaultNamespace("stray"), 10, 1, 3)),
                "the monsters");
        h.assertValueEqual(
                kepler.mobs().spawns(MobCategory.CREATURE),
                List.of(new PlanetMob(ResourceLocation.withDefaultNamespace("goat"), 5, 1, 2)),
                "the creatures");
        h.assertTrue(kepler.mobs().spawns(MobCategory.AMBIENT).isEmpty(), "ambient mobs");

        h.assertValueEqual(
                kepler.structures().allow(),
                List.of(
                        new StructureRef(ResourceLocation.withDefaultNamespace("igloo"), false),
                        new StructureRef(ResourceLocation.withDefaultNamespace("village"), true)),
                "the allowed structures");
        h.assertValueEqual(
                kepler.structures().deny(),
                List.of(new StructureRef(ResourceLocation.withDefaultNamespace("village_snowy"), false)),
                "the denied structures");
        h.assertFalse(kepler.access().hiddenUntilScanned(), "hidden until scanned");

        h.assertTrue(
                PlanetRegistry.server().problems().isEmpty(),
                "the load had problems: " + PlanetRegistry.server().problems());
        h.succeed();
    }

    /**
     * The API answers for the test planet's level, which {@link TestDimensions} made, and for no
     * other. The GameTest server is dedicated, so its client side has no planets.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void the_api_answers_for_the_planets_level(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerLevel kepler = server.getLevel(TestDimensions.KEPLER);
        h.assertTrue(kepler != null, "the test dimension isn't a level");
        Optional<Planet> planet = Planets.forLevel(kepler);
        h.assertTrue(planet.isPresent(), "no planet for the level");
        h.assertValueEqual(planet.get().dimension(), TestDimensions.KEPLER, "the planet's dimension");
        h.assertValueEqual(Planets.byId(KEPLER, LogicalSide.SERVER), planet, "the planet by its id");
        h.assertTrue(Planets.all(LogicalSide.SERVER).contains(planet.get()), "all() leaves it out");
        h.assertTrue(
                Planets.forLevel(server.overworld())
                        .filter(p -> p.id().equals(KEPLER))
                        .isEmpty(),
                "the Overworld is the test planet");
        h.assertTrue(Planets.forLevel(server.getLevel(Space.DIMENSION)).isEmpty(), "Space is a planet");
        h.assertTrue(
                Planets.forDimension(TestDimensions.KEPLER, LogicalSide.CLIENT).isEmpty(),
                "the dedicated server's client side has planets");
        // The level is a working level: its flat ground generates.
        h.assertTrue(
                !kepler.getBlockState(new net.minecraft.core.BlockPos(0, 63, 0)).isAir(),
                "the test dimension's ground isn't there");
        h.succeed();
    }

    /**
     * {@code /expedition planet validate} reads the files again and finds nothing wrong, and the
     * same planets as loaded; the planet's dimension, gases and structures are all on the server.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void validate_finds_no_problems(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        PlanetLoader.Result result = PlanetCommand.check(server);
        h.assertTrue(result.problems().isEmpty(), "problems: " + result.problems());
        h.assertValueEqual(result.planets(), PlanetRegistry.server().planets(), "the planets validate reads");

        Chat chat = new Chat();
        CommandSourceStack source =
                server.createCommandSourceStack().withSource(chat).withPermission(PlanetCommand.PERMISSION_LEVEL);
        int ran;
        try {
            ran = server.getCommands().getDispatcher().execute("expedition planet validate", source);
        } catch (CommandSyntaxException e) {
            throw new RuntimeException(e);
        }
        h.assertValueEqual(ran, 1, "the command's result");
        TranslatableContents summary = chat.first("expedition.command.planet.validate.summary");
        h.assertTrue(summary != null, "no summary: " + chat.messages);
        // Files read, planets that would load, files that wouldn't, warnings. The test pack has four
        // planets: kepler; generated, whose dimension expedition:planet makes (PlanetGeneratorTests);
        // crust, which holds ores (PlanetOreTests); and bounded, the one with a world border
        // (PlanetBorderTests). The example_planet pack has a fifth: example, the worked example
        // (MilestoneGateTests). The mod itself ships four more: the Overworld, the Nether (whose
        // file the test pack replaces), the End and the moon (ShippedPlanetTests).
        h.assertValueEqual(List.of(summary.getArgs()), List.<Object>of(9, 9, 0L, 0L), "the summary");
        h.assertValueEqual(chat.messages.size(), 1, "the messages besides the summary: " + chat.messages);
        h.succeed();
    }

    /**
     * A problem reads in English in the server's log: the dedicated server has the mod's {@code
     * en_us.json}, so a log line names the file and field in words, not translation keys.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void a_problem_reads_in_english_in_the_log(GameTestHelper h) {
        PlanetLoader.Result result = PlanetLoader.load(
                List.of(PlanetLoader.Source.of(
                        KEPLER, "kepler.json (test)", JsonParser.parseString("{ \"dimension\": \"x:y\" }"))),
                null,
                References.REGISTRIES);
        h.assertValueEqual(
                result.problems().stream().map(PlanetProblem::toString).toList(),
                List.of("kepler.json (test): space: is missing, and is required"),
                "the log's text");
        h.succeed();
    }

    private static ResourceLocation gas(String name) {
        return ResourceLocation.fromNamespaceAndPath("zas", name);
    }

    /** A command source that writes down every message. */
    private static final class Chat implements CommandSource {
        final List<Component> messages = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            messages.add(message);
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        TranslatableContents first(String key) {
            for (Component message : messages) {
                if (message.getContents() instanceof TranslatableContents t
                        && t.getKey().equals(key)) {
                    return t;
                }
            }
            return null;
        }
    }
}
