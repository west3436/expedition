package io.github.west3436.expedition.planet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.west3436.expedition.api.planet.Planet;
import io.github.west3436.expedition.api.planet.PlanetAccess;
import io.github.west3436.expedition.api.planet.PlanetAtmosphere;
import io.github.west3436.expedition.api.planet.PlanetMob;
import io.github.west3436.expedition.api.planet.PlanetMobs;
import io.github.west3436.expedition.api.planet.PlanetOre;
import io.github.west3436.expedition.api.planet.PlanetStructures;
import io.github.west3436.expedition.api.planet.PlanetSurface;
import io.github.west3436.expedition.api.planet.SpaceSquare;
import io.github.west3436.expedition.api.planet.StructureRef;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.MobCategory;
import org.junit.jupiter.api.Test;

/**
 * The planet file format, without the game: every field read, its defaults, and each kind of
 * mistake reported with its field. A problem is written here as {@code "<field> <key>"}.
 */
class PlanetFormatTest {
    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("mypack", "kepler");

    /** Knows a few blocks and entity types, as the game's registries would. */
    static final References REFS = new References() {
        @Override
        public boolean isBlock(ResourceLocation id) {
            return Set.of("iron_ore", "copper_ore").contains(id.getPath());
        }

        @Override
        public boolean isEntityType(ResourceLocation id) {
            return Set.of("stray", "goat").contains(id.getPath());
        }
    };

    /** The issue's example, with every field. */
    static final String FULL = """
            {
              "dimension": "mypack:kepler",
              "space": { "center": [12000, -4000], "size": 4096, "image": "mypack:textures/planet/kepler.png",
                         "parent": "mypack:kepler_prime" },
              "surface": { "center": [10, -20] },
              "atmosphere": { "partial_pressures": { "zas:nitrogen": 60.0, "zas:methane": 4.5 },
                              "temperature": { "day": 210, "night": 150 } },
              "gravity": 0.38,
              "ores": [ { "block": "minecraft:iron_ore", "weight": 40, "vein_size": 9, "y": [-32, 64] },
                        { "block": "minecraft:copper_ore", "weight": 20 } ],
              "ore_density": 1.5,
              "mobs": { "replace_existing": true,
                        "monster": [ { "type": "minecraft:stray", "weight": 10, "group": [1, 3] } ],
                        "creature": [ { "type": "minecraft:goat", "weight": 5 } ] },
              "structures": { "allow": ["#mypack:kepler_ruins", "minecraft:igloo"], "deny": ["mypack:kepler_tower"] },
              "access": { "hidden_until_scanned": true }
            }
            """;

    static final String MINIMAL = "{ \"dimension\": \"mypack:kepler\", \"space\": { \"center\": [0, 0] } }";

    static PlanetFormat.Read read(String json) {
        return PlanetFormat.read(ID, "kepler.json", JsonParser.parseString(json), REFS);
    }

    /** A file's problems, each as {@code "<field> <key>"}. */
    static List<String> problems(String json) {
        PlanetFormat.Read read = read(json);
        assertEquals(read.problems().isEmpty(), read.planet().isPresent(), "a planet and problems: " + read);
        read.problems().forEach(p -> {
            assertEquals("kepler.json", p.file());
            assertEquals(PlanetProblem.Severity.ERROR, p.severity());
        });
        return read.problems().stream().map(p -> p.field() + " " + p.key()).toList();
    }

    /** The minimal file with one more field, or one replaced. */
    static String with(String field, String json) {
        return with(MINIMAL, field, json);
    }

    /** A file with one more field, or one replaced. */
    static String with(String file, String field, String json) {
        JsonObject root = JsonParser.parseString(file).getAsJsonObject();
        root.add(field, JsonParser.parseString(json));
        return root.toString();
    }

    private static void assertProblems(String json, String... expected) {
        assertEquals(List.of(expected), problems(json), json);
    }

    private static ResourceLocation mc(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    // ---- What a good file gives -------------------------------------------------------------

    @Test
    void everyFieldIsRead() {
        Planet planet = read(FULL).planet().orElseThrow();
        assertEquals(ID, planet.id());
        assertEquals(ID, planet.dimension().location());
        assertEquals(
                new SpaceSquare(
                        12000,
                        -4000,
                        4096,
                        Optional.of(id("mypack", "textures/planet/kepler.png")),
                        Optional.of(id("mypack", "kepler_prime"))),
                planet.space());
        assertEquals(new PlanetSurface(10, -20), planet.surface());
        PlanetAtmosphere air = planet.atmosphere().orElseThrow();
        assertEquals(Map.of(id("zas", "nitrogen"), 60.0, id("zas", "methane"), 4.5), air.partialPressures());
        assertEquals(
                id("zas", "nitrogen"),
                air.partialPressures().keySet().iterator().next(),
                "the file's order");
        assertEquals(210, air.dayTemperature());
        assertEquals(150, air.nightTemperature());
        assertFalse(air.vacuum());
        assertEquals(0.38, planet.gravity());
        assertEquals(
                List.of(
                        new PlanetOre(mc("iron_ore"), 40, 9, -32, 64),
                        new PlanetOre(
                                mc("copper_ore"), 20, PlanetOre.DEFAULT_VEIN_SIZE, PlanetOre.MIN_Y, PlanetOre.MAX_Y)),
                planet.ores());
        assertEquals(1.5, planet.oreDensity());
        assertEquals(
                new PlanetMobs(
                        true,
                        Map.of(
                                MobCategory.MONSTER, List.of(new PlanetMob(mc("stray"), 10, 1, 3)),
                                MobCategory.CREATURE, List.of(new PlanetMob(mc("goat"), 5, 1, 1)))),
                planet.mobs());
        assertEquals(
                new PlanetStructures(
                        List.of(
                                new StructureRef(id("mypack", "kepler_ruins"), true),
                                new StructureRef(mc("igloo"), false)),
                        List.of(new StructureRef(id("mypack", "kepler_tower"), false))),
                planet.structures());
        assertTrue(planet.access().hiddenUntilScanned());
    }

    @Test
    void aFileNeedsOnlyItsDimensionAndCentre() {
        Planet planet = read(MINIMAL).planet().orElseThrow();
        assertEquals(SpaceSquare.of(0, 0, SpaceSquare.DEFAULT_SIZE), planet.space());
        assertEquals(8192, planet.space().size(), "the issue's default size");
        assertEquals(PlanetSurface.DEFAULT, planet.surface());
        assertTrue(planet.atmosphere().isEmpty(), "an atmosphere left out leaves the air to ZAS³");
        assertEquals(Planet.DEFAULT_GRAVITY, planet.gravity());
        assertTrue(planet.ores().isEmpty());
        assertEquals(Planet.DEFAULT_ORE_DENSITY, planet.oreDensity());
        assertEquals(PlanetMobs.DEFAULT, planet.mobs());
        assertEquals(PlanetStructures.DEFAULT, planet.structures());
        assertEquals(PlanetAccess.DEFAULT, planet.access());
        assertFalse(planet.access().hiddenUntilScanned());
    }

    @Test
    void oneTemperatureIsDayAndNight() {
        String json = with("atmosphere", "{ \"partial_pressures\": { \"zas:nitrogen\": 80 }, \"temperature\": 288 }");
        PlanetAtmosphere air = read(json).planet().orElseThrow().atmosphere().orElseThrow();
        assertEquals(288, air.dayTemperature());
        assertEquals(288, air.nightTemperature());
    }

    @Test
    void aVacuumHasNoGases() {
        String json = with("atmosphere", "{ \"vacuum\": true, \"temperature\": 40 }");
        PlanetAtmosphere air = read(json).planet().orElseThrow().atmosphere().orElseThrow();
        assertTrue(air.vacuum());
        assertTrue(air.partialPressures().isEmpty());
        assertEquals(0, air.totalPressure());
    }

    @Test
    void neoforgeConditionsAreAllowed() {
        assertProblems(with(PlanetFormat.CONDITIONS, "[ { \"type\": \"neoforge:true\" } ]"));
    }

    @Test
    void aWrittenPlanetReadsBackTheSame() {
        for (String json : List.of(FULL, MINIMAL, with("atmosphere", "{ \"vacuum\": true, \"temperature\": 3 }"))) {
            Planet planet = read(json).planet().orElseThrow();
            JsonElement written = PlanetFormat.write(planet);
            assertEquals(
                    Optional.of(planet),
                    PlanetFormat.read(ID, "x", written, REFS).planet(),
                    written.toString());
        }
    }

    // ---- Mistakes ---------------------------------------------------------------------------

    @Test
    void theFileMustBeAnObjectWithItsRequiredFields() {
        assertProblems("[]", " not_object");
        assertProblems("{}", "dimension missing", "space missing");
        assertProblems("{ \"dimension\": \"mypack:kepler\", \"space\": {} }", "space.center missing");
    }

    @Test
    void anUnknownFieldIsAProblem() {
        assertProblems(with("gravityy", "0.5"), "gravityy unknown_field");
        assertProblems(with("access", "{ \"hidden\": true }"), "access.hidden unknown_field");
        assertProblems(
                with("ores", "[ { \"block\": \"minecraft:iron_ore\", \"weight\": 1, \"veins\": 3 } ]"),
                "ores[0].veins unknown_field");
    }

    @Test
    void idsMustBeIds() {
        assertProblems(MINIMAL.replace("mypack:kepler", "My Pack:Kepler"), "dimension not_id");
        assertProblems(MINIMAL.replace("\"mypack:kepler\"", "5"), "dimension not_string");
        assertProblems(
                with("space", "{ \"center\": [0, 0], \"image\": \"textures/Kepler.png\" }"), "space.image not_id");
    }

    @Test
    void theSpaceSquareIsChecked() {
        assertProblems(with("space", "{ \"center\": [1] }"), "space.center not_pair");
        assertProblems(with("space", "{ \"center\": [1.5, 0] }"), "space.center[0] not_integer");
        assertProblems(with("space", "{ \"center\": [0, \"north\"] }"), "space.center[1] not_integer");
        assertProblems(with("space", "{ \"center\": [0, 0], \"size\": 8 }"), "space.size out_of_range");
        assertProblems(with("space", "{ \"center\": [0, 0], \"size\": 4096.5 }"), "space.size not_integer");
        assertProblems(with("space", "{ \"center\": [29999000, 0] }"), "space space_outside");
        assertProblems(with("space", "{ \"center\": [0, 0], \"parent\": 3 }"), "space.parent not_string");
        assertProblems(with("surface", "{ \"center\": \"origin\" }"), "surface.center not_pair");
    }

    @Test
    void theAtmosphereIsChecked() {
        assertProblems(
                with("atmosphere", "{ \"partial_pressures\": { \"zas:nitrogen\": 80 } }"),
                "atmosphere.temperature missing");
        assertProblems(
                with("atmosphere", "{ \"partial_pressures\": { \"zas:nitrogen\": 80 }, \"temperature\": 0 }"),
                "atmosphere.temperature above");
        assertProblems(
                with("atmosphere", "{ \"partial_pressures\": { \"zas:nitrogen\": 80 }, \"temperature\": \"cold\" }"),
                "atmosphere.temperature not_temperature");
        assertProblems(
                with(
                        "atmosphere",
                        "{ \"partial_pressures\": { \"zas:nitrogen\": 80 }, \"temperature\": { \"day\": 200 } }"),
                "atmosphere.temperature.night missing");
        assertProblems(with("atmosphere", "{ \"temperature\": 200 }"), "atmosphere.partial_pressures air_required");
        assertProblems(
                with(
                        "atmosphere",
                        "{ \"vacuum\": true, \"partial_pressures\": { \"zas:nitrogen\": 80 }, \"temperature\": 3 }"),
                "atmosphere.partial_pressures vacuum_with_gases");
        assertProblems(
                with("atmosphere", "{ \"partial_pressures\": { \"zas:nitrogen\": 0 }, \"temperature\": 200 }"),
                "atmosphere.partial_pressures no_pressure");
        assertProblems(
                with("atmosphere", "{ \"partial_pressures\": { \"zas:methane\": -1 }, \"temperature\": 200 }"),
                "atmosphere.partial_pressures.zas:methane at_least");
        assertProblems(
                with("atmosphere", "{ \"partial_pressures\": { \"Methane!\": 1 }, \"temperature\": 200 }"),
                "atmosphere.partial_pressures.Methane! not_id");
        assertProblems(
                with("atmosphere", "{ \"partial_pressures\": [], \"temperature\": 200 }"),
                "atmosphere.partial_pressures not_object");
        assertProblems(
                with("atmosphere", "{ \"vacuum\": \"yes\", \"temperature\": 200 }"), "atmosphere.vacuum not_boolean");
    }

    @Test
    void gravityAndOreDensityAreChecked() {
        assertProblems(with("gravity", "-1"), "gravity out_of_range");
        assertProblems(with("gravity", "10.5"), "gravity out_of_range");
        assertProblems(with("gravity", "\"low\""), "gravity not_number");
        assertProblems(with("ore_density", "11"), "ore_density out_of_range");
        assertProblems(
                with(with("ore_density", "-1"), "gravity", "\"low\""),
                "gravity not_number",
                "ore_density out_of_range");
    }

    @Test
    void oresAreChecked() {
        assertProblems(with("ores", "{}"), "ores not_list");
        assertProblems(with("ores", "[ \"minecraft:iron_ore\" ]"), "ores[0] not_object");
        assertProblems(with("ores", "[ { \"block\": \"minecraft:iron_ore\" } ]"), "ores[0].weight missing");
        assertProblems(
                with("ores", "[ { \"block\": \"minecraft:iron_ore\", \"weight\": 0 } ]"), "ores[0].weight at_least");
        assertProblems(
                with("ores", "[ { \"block\": \"minecraft:unobtainium_ore\", \"weight\": 1 } ]"),
                "ores[0].block unknown_block");
        assertProblems(
                with("ores", "[ { \"block\": \"minecraft:iron_ore\", \"weight\": 1, \"vein_size\": 65 } ]"),
                "ores[0].vein_size out_of_range");
        assertProblems(
                with("ores", "[ { \"block\": \"minecraft:iron_ore\", \"weight\": 1, \"y\": [64, -32] } ]"),
                "ores[0].y min_above_max");
        assertProblems(
                with("ores", "[ { \"block\": \"minecraft:iron_ore\", \"weight\": 1, \"y\": [-3000, 0] } ]"),
                "ores[0].y[0] out_of_range");
        assertProblems(
                with(
                        "ores",
                        "[ { \"block\": \"minecraft:iron_ore\", \"weight\": 1 },"
                                + " { \"block\": \"minecraft:iron_ore\", \"weight\": 2 } ]"),
                "ores[1].block duplicate");
    }

    @Test
    void mobsAreChecked() {
        assertProblems(with("mobs", "{ \"monsters\": [] }"), "mobs.monsters unknown_category");
        assertProblems(with("mobs", "{ \"replace_existing\": 1 }"), "mobs.replace_existing not_boolean");
        assertProblems(with("mobs", "{ \"monster\": {} }"), "mobs.monster not_list");
        assertProblems(
                with("mobs", "{ \"monster\": [ { \"type\": \"minecraft:alien\", \"weight\": 1 } ] }"),
                "mobs.monster[0].type unknown_entity_type");
        assertProblems(
                with(
                        "mobs",
                        "{ \"monster\": [ { \"type\": \"minecraft:stray\", \"weight\": 1, \"group\": [3, 1] } ] }"),
                "mobs.monster[0].group min_above_max");
        assertProblems(
                with(
                        "mobs",
                        "{ \"monster\": [ { \"type\": \"minecraft:stray\", \"weight\": 1, \"group\": [0, 1] } ] }"),
                "mobs.monster[0].group[0] out_of_range");
        assertProblems(
                with(
                        "mobs",
                        "{ \"monster\": [ { \"type\": \"minecraft:stray\", \"weight\": 1 },"
                                + " { \"type\": \"minecraft:stray\", \"weight\": 2 } ] }"),
                "mobs.monster[1].type duplicate");
    }

    @Test
    void structuresAreChecked() {
        assertProblems(with("structures", "{ \"allow\": [ \"#Bad Tag\" ] }"), "structures.allow[0] not_structure");
        assertProblems(with("structures", "{ \"allow\": [ 5 ] }"), "structures.allow[0] not_string");
        assertProblems(
                with("structures", "{ \"allow\": [ \"#minecraft:village\", \"#minecraft:village\" ] }"),
                "structures.allow[1] duplicate");
        assertProblems(
                with("structures", "{ \"allow\": [ \"minecraft:igloo\" ], \"deny\": [ \"minecraft:igloo\" ] }"),
                "structures.deny[0] allowed_and_denied");
        // A structure and the tag of the same name are two things.
        assertProblems(
                with("structures", "{ \"allow\": [ \"minecraft:igloo\" ], \"deny\": [ \"#minecraft:igloo\" ] }"));
    }

    @Test
    void everyProblemInAFileIsReported() {
        String json = """
                { "dimension": "mypack:kepler", "space": { "center": [0, 0], "size": 1 },
                  "gravity": -2, "access": { "hidden_until_scanned": "no" }, "colour": "red" }
                """;
        assertProblems(
                json,
                "space.size out_of_range",
                "gravity out_of_range",
                "access.hidden_until_scanned not_boolean",
                "colour unknown_field");
    }

    @Test
    void aProblemSaysWhereAndWhat() {
        PlanetProblem problem = read(with("gravity", "-1")).problems().getFirst();
        assertEquals("expedition.planet.problem.out_of_range", problem.translationKey());
        assertEquals(List.of(-1.0, 0L, 10L), problem.args());
    }
}
