package io.github.west3436.expedition.gametest;

import io.github.west3436.expedition.ExpeditionMod;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
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
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A planet's structure lists on a dimension Expedition doesn't generate: the Nether, with vanilla's
 * noise generator, denies bastions ({@link TestPlanetStructures}). Bastions and fortresses share one
 * structure set, {@code minecraft:nether_complexes}, so this also checks that one structure can go
 * while the rest of its set stays.
 *
 * <p>Each test compares the level's structure state with a fresh one that the Nether's generator
 * builds the vanilla way.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PlanetStructureTests {
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
        ProtoChunk chunk = new ProtoChunk(
                spot, UpgradeData.EMPTY, level, level.registryAccess().registryOrThrow(Registries.BIOME), null);
        generator.createStructures(
                level.registryAccess(), state, level.structureManager(), chunk, level.getStructureManager());
        if (chunk.getAllStarts().containsKey(fortress)) {
            counts[0]++;
        }
        if (chunk.getAllStarts().containsKey(bastion)) {
            counts[1]++;
        }
    }
}
