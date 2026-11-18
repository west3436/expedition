package io.github.west3436.expedition.gametest;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.api.planet.Planet;
import io.github.west3436.expedition.api.planet.PlanetStructures;
import io.github.west3436.expedition.api.planet.Planets;
import io.github.west3436.expedition.planet.PlanetLoader;
import io.github.west3436.expedition.planet.PlanetProblem;
import io.github.west3436.expedition.planet.PlanetRegistry;
import io.github.west3436.expedition.planet.StructureFilter;
import io.github.west3436.expedition.worldgen.PlanetChunkGenerator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A planet's structure lists, on two generators Expedition doesn't own and on its own:
 *
 * <ul>
 *   <li>The test planet's file, on {@code expedition_test:kepler}'s flat generator.
 *   <li>The {@code expedition_test:generated} planet's file, denying igloos, on {@code
 *       expedition:planet}.
 *   <li>The Nether, with vanilla's noise generator, denying bastions ({@link TestPlanetStructures}).
 *       Bastions and fortresses share one structure set, {@code minecraft:nether_complexes}, so this
 *       also checks that one structure can go while the rest of its set stays.
 * </ul>
 *
 * <p>Each test compares the level's structure state with a fresh one that its generator builds the
 * vanilla way.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PlanetStructureTests {
    private static final ResourceKey<Level> GENERATED = ResourceKey.create(
            Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(TestDimensions.NAMESPACE, "generated"));

    private PlanetStructureTests() {}

    private static ServerLevel nether(GameTestHelper helper) {
        return helper.getLevel().getServer().getLevel(Level.NETHER);
    }

    /** The structure state the Nether would have without its planet lists. */
    private static ChunkGeneratorStructureState vanilla(ServerLevel level) {
        ChunkGeneratorStructureState state = level.getChunkSource()
                .getGenerator()
                .createState(
                        level.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET),
                        level.getChunkSource().randomState(),
                        level.getSeed());
        state.ensureStructuresGenerated();
        return state;
    }

    private static boolean holds(ChunkGeneratorStructureState state, Holder<Structure> structure) {
        for (Holder<StructureSet> set : state.possibleStructureSets()) {
            for (StructureSet.StructureSelectionEntry entry : set.value().structures()) {
                if (entry.structure().value() == structure.value()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }

    /** The structures in a state, by id, set by set. */
    private static Map<String, List<String>> contents(ChunkGeneratorStructureState state) {
        Map<String, List<String>> contents = new TreeMap<>();
        for (Holder<StructureSet> set : state.possibleStructureSets()) {
            // A rebuilt set has no id of its own, so each set is named by its first structure.
            List<String> structures = set.value().structures().stream()
                    .map(entry -> entry.structure()
                            .unwrapKey()
                            .orElseThrow()
                            .location()
                            .toString())
                    .sorted()
                    .toList();
            contents.put(structures.get(0), structures);
        }
        return contents;
    }

    /**
     * The test planet's file, on the flat generator of {@code expedition_test:kepler}: igloos,
     * villages and mineshafts, with {@code "allow": ["minecraft:igloo", "#minecraft:village"]} and
     * {@code "deny": ["minecraft:village_snowy"]}. Igloos stay by their structure, villages by their
     * tag less the denied one, and mineshafts go, since the allow list doesn't name them.
     */
    @GameTest(template = "empty")
    public static void planet_file_lists_apply(GameTestHelper helper) {
        ServerLevel kepler = helper.getLevel().getServer().getLevel(TestDimensions.KEPLER);
        check(kepler != null, "no test planet level");
        Map<String, List<String>> planet = contents(kepler.getChunkSource().getGeneratorState());
        Map<String, List<String>> control = contents(vanilla(kepler));
        check(
                control.keySet().equals(Set.of("minecraft:igloo", "minecraft:mineshaft", "minecraft:village_desert")),
                "control: the flat generator's sets are " + control);
        check(
                control.get("minecraft:village_desert").contains("minecraft:village_snowy"),
                "control: villages without the snowy village: " + control);
        Map<String, List<String>> expected = new TreeMap<>();
        expected.put("minecraft:igloo", List.of("minecraft:igloo"));
        List<String> villages = new java.util.ArrayList<>(control.get("minecraft:village_desert"));
        villages.remove("minecraft:village_snowy");
        expected.put("minecraft:village_desert", villages);
        check(planet.equals(expected), "the planet's sets are " + planet + ", not " + expected);
        check(
                StructureFilter.applied(TestDimensions.KEPLER)
                        .equals(Planets.forLevel(kepler).map(Planet::structures)),
                "the level wasn't built with its planet's lists");
        helper.succeed();
    }

    /**
     * The {@code expedition_test:generated} planet, made by {@code expedition:planet}: its biome is
     * tagged for igloos and the standard ruined portal, and its file denies igloos. The igloo set
     * goes, the ruined portals stay, and at every igloo spot near the origin the generator makes an
     * igloo somewhere with the vanilla state and nowhere with the planet's.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void lists_apply_on_the_planet_generator(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().getLevel(GENERATED);
        check(level != null, "no generated planet level");
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        check(generator instanceof PlanetChunkGenerator, "the generated planet isn't made by expedition:planet");
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        ChunkGeneratorStructureState vanilla = vanilla(level);
        Map<String, List<String>> planet = contents(state);
        Map<String, List<String>> control = contents(vanilla);
        check(
                control.keySet().equals(Set.of("minecraft:igloo", "minecraft:ruined_portal")),
                "control: the generated planet's sets are " + control);
        Map<String, List<String>> expected = new TreeMap<>(control);
        expected.remove("minecraft:igloo");
        check(planet.equals(expected), "the planet's sets are " + planet + ", not " + expected);
        check(
                StructureFilter.applied(GENERATED)
                        .equals(Planets.forLevel(level).map(Planet::structures)),
                "the level wasn't built with its planet's lists");

        Structure igloo =
                level.registryAccess().registryOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.IGLOO);
        RandomSpreadStructurePlacement placement = (RandomSpreadStructurePlacement) level.registryAccess()
                .registryOrThrow(Registries.STRUCTURE_SET)
                .getOrThrow(BuiltinStructureSets.IGLOOS)
                .placement();
        int planetIgloos = 0;
        int controlIgloos = 0;
        int spacing = placement.spacing();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                ChunkPos spot = placement.getPotentialStructureChunk(level.getSeed(), x * spacing, z * spacing);
                planetIgloos += starts(level, generator, state, spot).containsKey(igloo) ? 1 : 0;
                controlIgloos += starts(level, generator, vanilla, spot).containsKey(igloo) ? 1 : 0;
            }
        }
        String counts = "igloos: " + planetIgloos + " on the planet, " + controlIgloos + " in vanilla";
        check(controlIgloos > 0, "control: vanilla made no igloos; " + counts);
        check(planetIgloos == 0, "igloos generated; " + counts);
        helper.succeed();
    }

    /**
     * A {@code /reload} can't change a level's lists, so when a planet's file differs from the lists
     * its level was built with, checking the planets says they apply at the next start.
     */
    @GameTest(template = "empty")
    public static void changed_lists_wait_for_the_next_start(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PlanetLoader.Result loaded = PlanetRegistry.server();
        check(
                PlanetLoader.checkWorld(loaded, server).stream()
                        .noneMatch(p -> p.key().equals("structures_at_next_start")),
                "the loaded planets already differ from their levels");
        Map<ResourceLocation, Planet> changed = new java.util.HashMap<>();
        for (Planet planet : loaded.planets().values()) {
            changed.put(
                    planet.id(),
                    new Planet(
                            planet.id(),
                            planet.dimension(),
                            planet.space(),
                            planet.surface(),
                            planet.atmosphere(),
                            planet.gravity(),
                            planet.ores(),
                            planet.oreDensity(),
                            planet.mobs(),
                            PlanetStructures.DEFAULT,
                            planet.access()));
        }
        PlanetLoader.Result reloaded = new PlanetLoader.Result(changed, loaded.files(), List.of(), loaded.read());
        List<PlanetProblem> problems = PlanetLoader.checkWorld(reloaded, server);
        check(
                problems.stream()
                        .anyMatch(p -> p.key().equals("structures_at_next_start")
                                && p.field().equals("structures")),
                "no warning that the lists apply at the next start: " + problems);
        helper.succeed();
    }

    /**
     * Bastions are gone from the Nether's structure sets and from what {@code /locate} searches,
     * the placements per structure; fortresses keep theirs.
     */
    @GameTest(template = "empty")
    public static void denied_structure_has_no_placements(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        ChunkGeneratorStructureState state = nether.getChunkSource().getGeneratorState();
        ChunkGeneratorStructureState vanilla = vanilla(nether);
        Registry<Structure> structures = nether.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Holder<Structure> bastion = structures.getHolderOrThrow(BuiltinStructures.BASTION_REMNANT);
        Holder<Structure> fortress = structures.getHolderOrThrow(BuiltinStructures.FORTRESS);

        check(holds(vanilla, bastion), "control: the vanilla state has no bastions");
        check(!vanilla.getPlacementsForStructure(bastion).isEmpty(), "control: vanilla bastions have no placements");
        check(!holds(state, bastion), "the Nether's structure sets still hold bastions");
        check(state.getPlacementsForStructure(bastion).isEmpty(), "bastions still have placements");
        check(holds(state, fortress), "the Nether's structure sets lost fortresses");
        check(!state.getPlacementsForStructure(fortress).isEmpty(), "fortresses lost their placements");
        check(
                state.possibleStructureSets().size()
                        == vanilla.possibleStructureSets().size(),
                "the Nether lost a whole structure set");
        helper.succeed();
    }

    /**
     * At every {@code nether_complexes} spot near the origin, the Nether's generator makes no
     * bastion and does make fortresses, where the vanilla state makes both.
     *
     * <p>A GameTest world is made with structures off ({@code GameTestServer}'s {@code
     * WorldOptions}), so no structure generates in its chunks. Each spot gets a throwaway chunk
     * instead, which {@code ChunkGenerator#createStructures} fills as the structure-starts step
     * would.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void denied_structure_never_generates(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        ChunkGenerator generator = nether.getChunkSource().getGenerator();
        ChunkGeneratorStructureState state = nether.getChunkSource().getGeneratorState();
        ChunkGeneratorStructureState vanilla = vanilla(nether);
        Structure bastion = nether.registryAccess()
                .registryOrThrow(Registries.STRUCTURE)
                .getOrThrow(BuiltinStructures.BASTION_REMNANT);
        Structure fortress =
                nether.registryAccess().registryOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.FORTRESS);
        RandomSpreadStructurePlacement placement = (RandomSpreadStructurePlacement) nether.registryAccess()
                .registryOrThrow(Registries.STRUCTURE_SET)
                .getOrThrow(BuiltinStructureSets.NETHER_COMPLEXES)
                .placement();

        int[] planet = new int[2]; // fortresses, bastions
        int[] control = new int[2];
        int spacing = placement.spacing();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                ChunkPos spot = placement.getPotentialStructureChunk(nether.getSeed(), x * spacing, z * spacing);
                count(nether, generator, state, spot, fortress, bastion, planet);
                count(nether, generator, vanilla, spot, fortress, bastion, control);
            }
        }
        String counts = "fortresses and bastions: " + planet[0] + "/" + planet[1] + " on the planet, " + control[0]
                + "/" + control[1] + " in vanilla";
        check(control[1] > 0, "control: vanilla made no bastions; " + counts);
        check(planet[1] == 0, "bastions generated; " + counts);
        check(planet[0] > 0, "no fortress generated; " + counts);
        // A denied structure's spots go to the rest of its set, as when vanilla's pick can't generate.
        check(planet[0] >= control[0], "fewer fortresses than vanilla; " + counts);
        helper.succeed();
    }

    private static void count(
            ServerLevel level,
            ChunkGenerator generator,
            ChunkGeneratorStructureState state,
            ChunkPos spot,
            Structure fortress,
            Structure bastion,
            int[] counts) {
        Map<Structure, StructureStart> starts = starts(level, generator, state, spot);
        if (starts.containsKey(fortress)) {
            counts[0]++;
        }
        if (starts.containsKey(bastion)) {
            counts[1]++;
        }
    }

    /** The structure starts the generator makes in a throwaway chunk at a spot. */
    private static Map<Structure, StructureStart> starts(
            ServerLevel level, ChunkGenerator generator, ChunkGeneratorStructureState state, ChunkPos spot) {
        ProtoChunk chunk = new ProtoChunk(
                spot, UpgradeData.EMPTY, level, level.registryAccess().registryOrThrow(Registries.BIOME), null);
        generator.createStructures(
                level.registryAccess(), state, level.structureManager(), chunk, level.getStructureManager());
        return chunk.getAllStarts();
    }
}
