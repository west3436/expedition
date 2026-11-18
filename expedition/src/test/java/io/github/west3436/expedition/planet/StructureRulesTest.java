package io.github.west3436.expedition.planet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** A planet's structure lists, resolved and applied, without the game. */
class StructureRulesTest {
    /** A structure set: its id and its structures' ids. */
    private record FakeSet(String id, List<String> structures) {}

    private static final StructureRules.SetView<FakeSet, String> VIEW = new StructureRules.SetView<>() {
        @Override
        public Optional<ResourceLocation> id(FakeSet set) {
            return Optional.ofNullable(set.id()).map(ResourceLocation::parse);
        }

        @Override
        public List<String> entries(FakeSet set) {
            return set.structures();
        }

        @Override
        public Optional<ResourceLocation> structure(String entry) {
            return Optional.of(ResourceLocation.parse(entry));
        }

        @Override
        public FakeSet rebuild(FakeSet set, List<String> kept) {
            return new FakeSet(set.id(), kept);
        }
    };

    private static final FakeSet VILLAGES =
            new FakeSet("minecraft:villages", List.of("minecraft:village_plains", "minecraft:village_desert"));
    private static final FakeSet COMPLEXES =
            new FakeSet("minecraft:nether_complexes", List.of("minecraft:fortress", "minecraft:bastion_remnant"));
    private static final FakeSet MINESHAFTS =
            new FakeSet("minecraft:mineshafts", List.of("minecraft:mineshaft", "minecraft:mineshaft_mesa"));
    private static final FakeSet TRIAL = new FakeSet("minecraft:trial_chambers", List.of("minecraft:trial_chambers"));
    private static final List<FakeSet> LEVEL = List.of(VILLAGES, COMPLEXES, MINESHAFTS, TRIAL);

    /** Vanilla-like registries: a structure tag for villages and a set tag for underground sets. */
    private static final StructureRules.Lookup LOOKUP = new StructureRules.Lookup() {
        private final Set<String> structures = Set.of(
                "minecraft:village_plains",
                "minecraft:village_desert",
                "minecraft:fortress",
                "minecraft:bastion_remnant",
                "minecraft:mineshaft",
                "minecraft:mineshaft_mesa",
                "minecraft:trial_chambers");
        private final Set<String> sets = Set.of(
                "minecraft:villages", "minecraft:nether_complexes", "minecraft:mineshafts", "minecraft:trial_chambers");
        private final Map<String, Set<String>> structureTags =
                Map.of("minecraft:village", Set.of("minecraft:village_plains", "minecraft:village_desert"));
        private final Map<String, Set<String>> setTags =
                Map.of("mypack:underground", Set.of("minecraft:mineshafts", "minecraft:trial_chambers"));

        @Override
        public boolean hasStructure(ResourceLocation id) {
            return structures.contains(id.toString());
        }

        @Override
        public boolean hasSet(ResourceLocation id) {
            return sets.contains(id.toString());
        }

        @Override
        public Optional<Set<ResourceLocation>> structureTag(ResourceLocation tag) {
            return Optional.ofNullable(structureTags.get(tag.toString())).map(StructureRulesTest::ids);
        }

        @Override
        public Optional<Set<ResourceLocation>> setTag(ResourceLocation tag) {
            return Optional.ofNullable(setTags.get(tag.toString())).map(StructureRulesTest::ids);
        }
    };

    private static Set<ResourceLocation> ids(Set<String> ids) {
        Set<ResourceLocation> out = new java.util.HashSet<>();
        ids.forEach(id -> out.add(ResourceLocation.parse(id)));
        return out;
    }

    private static StructureRules rules(List<String> allow, List<String> deny) {
        List<String> problems = new ArrayList<>();
        StructureRules rules = StructureRules.resolve(allow, deny, LOOKUP, problems::add);
        assertEquals(List.of(), problems);
        return rules;
    }

    private static List<FakeSet> apply(StructureRules rules) {
        return rules.filter(LEVEL, VIEW).sets();
    }

    @Test
    void noListsKeepEverythingAsItWas() {
        StructureRules rules = rules(List.of(), List.of());
        assertTrue(rules.keepsEverything());
        List<FakeSet> kept = apply(rules);
        assertEquals(LEVEL, kept);
        for (int i = 0; i < LEVEL.size(); i++) {
            assertSame(LEVEL.get(i), kept.get(i), "an untouched set is the same object");
        }
        assertTrue(StructureRules.NONE.keepsEverything());
    }

    @Test
    void denyingOneStructureRebuildsItsSet() {
        StructureRules.Filtered<FakeSet> filtered =
                rules(List.of(), List.of("minecraft:bastion_remnant")).filter(LEVEL, VIEW);
        assertEquals(
                List.of(
                        VILLAGES,
                        new FakeSet("minecraft:nether_complexes", List.of("minecraft:fortress")),
                        MINESHAFTS,
                        TRIAL),
                filtered.sets());
        assertSame(VILLAGES, filtered.sets().get(0));
        assertEquals(Set.of("minecraft:bastion_remnant"), filtered.removed());
    }

    @Test
    void denyingASetDropsIt() {
        assertEquals(List.of(VILLAGES, COMPLEXES, TRIAL), apply(rules(List.of(), List.of("minecraft:mineshafts"))));
    }

    @Test
    void denyingEveryStructureOfASetDropsIt() {
        assertEquals(
                List.of(COMPLEXES, MINESHAFTS, TRIAL),
                apply(rules(List.of(), List.of("minecraft:village_plains", "minecraft:village_desert"))));
    }

    @Test
    void denyingByStructureTag() {
        assertEquals(List.of(COMPLEXES, MINESHAFTS, TRIAL), apply(rules(List.of(), List.of("#minecraft:village"))));
    }

    @Test
    void denyingBySetTag() {
        assertEquals(List.of(VILLAGES, COMPLEXES), apply(rules(List.of(), List.of("#mypack:underground"))));
    }

    @Test
    void allowKeepsOnlyWhatItMatches() {
        // By set: the whole set stays as it was.
        assertEquals(List.of(VILLAGES), apply(rules(List.of("minecraft:villages"), List.of())));
        // By structure: its set is rebuilt with that structure alone.
        assertEquals(
                List.of(new FakeSet("minecraft:mineshafts", List.of("minecraft:mineshaft_mesa"))),
                apply(rules(List.of("minecraft:mineshaft_mesa"), List.of())));
        // By tag, mixed.
        assertEquals(
                List.of(VILLAGES, MINESHAFTS, TRIAL),
                apply(rules(List.of("#minecraft:village", "#mypack:underground"), List.of())));
    }

    @Test
    void denyWinsOverAllow() {
        assertEquals(
                List.of(new FakeSet("minecraft:villages", List.of("minecraft:village_desert"))),
                apply(rules(List.of("minecraft:villages"), List.of("minecraft:village_plains"))));
        assertEquals(List.of(), apply(rules(List.of("#minecraft:village"), List.of("minecraft:villages"))));
    }

    @Test
    void anIdThatIsBothAStructureAndASetMatchesEither() {
        StructureRules rules = rules(List.of(), List.of("minecraft:trial_chambers"));
        assertTrue(rules.deny().structures().contains(ResourceLocation.parse("minecraft:trial_chambers")));
        assertTrue(rules.deny().sets().contains(ResourceLocation.parse("minecraft:trial_chambers")));
        assertEquals(List.of(VILLAGES, COMPLEXES, MINESHAFTS), apply(rules));
    }

    @Test
    void entriesThatNameNothingAreReportedAndSkipped() {
        List<String> problems = new ArrayList<>();
        StructureRules rules = StructureRules.resolve(
                List.of(), List.of("minecraft:no_such", "#mypack:no_such_tag", "Not An Id"), LOOKUP, problems::add);
        assertEquals(3, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("minecraft:no_such"), problems.get(0));
        assertTrue(problems.get(1).contains("#mypack:no_such_tag"), problems.get(1));
        assertTrue(problems.get(2).contains("Not An Id"), problems.get(2));
        assertTrue(rules.keepsEverything());
        assertEquals(LEVEL, apply(rules));
    }

    @Test
    void anAllowThatResolvesToNothingStillKeepsNothing() {
        StructureRules rules = StructureRules.resolve(List.of("#mypack:no_such_tag"), List.of(), LOOKUP, p -> {});
        assertFalse(rules.keepsEverything());
        assertEquals(List.of(), apply(rules));
    }

    @Test
    void anUnnamedSetOrStructureIsMatchedByNothingButAllow() {
        StructureRules rules = rules(List.of(), List.of("minecraft:villages"));
        assertTrue(rules.keeps(null, null));
        assertFalse(rules(List.of("minecraft:villages"), List.of()).keeps(null, null));
    }
}
