package io.github.west3436.expedition.planet;

import com.mojang.logging.LogUtils;
import io.github.west3436.expedition.mixin.ChunkGeneratorStructureStateAccessor;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.slf4j.Logger;

/**
 * Applies each planet's {@code structures} lists to its level, whatever generates the dimension.
 *
 * <p>A level's structure state is built once, in the {@code ChunkMap} constructor, and the {@code
 * ServerLevel} constructor works out its placements and stronghold rings right after, before any
 * chunk generates. {@code ChunkMapMixin} calls {@link #apply} between the two, so the planet's
 * rules decide both what generates and what {@code /locate} finds. Worldgen threads read the state
 * afterwards, so it isn't rebuilt on a running server: a changed list takes effect at the next
 * start.
 *
 * <p>A set that loses some of its structures gives their spots to the rest, as vanilla does when
 * the structure it picks can't generate there: with bastions denied, every Nether complex is a
 * fortress. To remove a whole set, deny the set.
 */
public final class StructureFilter {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * A planet's {@code structures} lists as written.
     *
     * @param allow the {@code allow} entries, empty when left out
     * @param deny the {@code deny} entries, empty when left out
     */
    public record Lists(List<String> allow, List<String> deny) {
        /** No lists. */
        public static final Lists NONE = new Lists(List.of(), List.of());

        public Lists {
            allow = List.copyOf(allow);
            deny = List.copyOf(deny);
        }

        /**
         * Whether the lists are both empty, so they change nothing.
         *
         * @return true when both are empty
         */
        public boolean isEmpty() {
            return allow.isEmpty() && deny.isEmpty();
        }
    }

    /** Where the lists for a dimension come from. */
    @FunctionalInterface
    public interface Source {
        /**
         * The lists of the planet on a dimension.
         *
         * @param dimension the dimension
         * @return its planet's lists, or empty when it isn't a planet
         */
        Optional<Lists> lists(ResourceKey<Level> dimension);
    }

    private static final Source NO_PLANETS = dimension -> Optional.empty();

    private static volatile Source source = NO_PLANETS;

    /** The game's structure sets, as the rule reads them. */
    private static final StructureRules.SetView<Holder<StructureSet>, StructureSet.StructureSelectionEntry> SETS =
            new StructureRules.SetView<>() {
                @Override
                public Optional<ResourceLocation> id(Holder<StructureSet> set) {
                    return set.unwrapKey().map(ResourceKey::location);
                }

                @Override
                public List<StructureSet.StructureSelectionEntry> entries(Holder<StructureSet> set) {
                    return set.value().structures();
                }

                @Override
                public Optional<ResourceLocation> structure(StructureSet.StructureSelectionEntry entry) {
                    return entry.structure().unwrapKey().map(ResourceKey::location);
                }

                @Override
                public Holder<StructureSet> rebuild(
                        Holder<StructureSet> set, List<StructureSet.StructureSelectionEntry> kept) {
                    // The same placement object, so stronghold rings and exclusion zones still match.
                    return Holder.direct(new StructureSet(kept, set.value().placement()));
                }
            };

    private StructureFilter() {}

    /**
     * Sets where the lists come from. Levels built after this use it.
     *
     * @param source the source
     */
    public static void setSource(Source source) {
        StructureFilter.source = Objects.requireNonNull(source);
    }

    /**
     * Applies a dimension's planet lists to its level's structure state, which must not have worked
     * out its placements yet. Called once per level, from the {@code ChunkMap} constructor.
     *
     * @param level the level being built
     * @param state its structure state
     */
    public static void apply(ServerLevel level, ChunkGeneratorStructureState state) {
        ResourceKey<Level> dimension = level.dimension();
        Lists lists = source.lists(dimension).orElse(Lists.NONE);
        if (lists.isEmpty()) {
            return;
        }
        String name = dimension.location().toString();
        StructureRules rules = StructureRules.resolve(
                lists.allow(),
                lists.deny(),
                lookup(level.registryAccess()),
                problem -> LOGGER.warn("Planet {}: structures.{}", name, problem));
        if (rules.keepsEverything()) {
            return;
        }
        ChunkGeneratorStructureStateAccessor accessor = (ChunkGeneratorStructureStateAccessor) state;
        if (accessor.expedition$hasGeneratedPositions()) {
            // Something worked out the placements before the level was finished. Generation still
            // follows the lists, but /locate may find what they removed.
            LOGGER.error("Planet {}: its structure placements were made before its lists applied", name);
        }
        List<Holder<StructureSet>> before = state.possibleStructureSets();
        StructureRules.Filtered<Holder<StructureSet>> filtered = rules.filter(before, SETS);
        accessor.expedition$setPossibleStructureSets(filtered.sets());
        LOGGER.info(
                "Planet {}: {} of {} structure sets kept; removed {}",
                name,
                filtered.sets().size(),
                before.size(),
                filtered.removed().isEmpty() ? "nothing" : String.join(", ", filtered.removed()));
    }

    /**
     * Resolves lists against a server's registries.
     *
     * @param lists the lists as written
     * @param registries the server's registries
     * @param problems told of each entry that names nothing
     * @return the rules
     */
    public static StructureRules resolve(Lists lists, RegistryAccess registries, Consumer<String> problems) {
        return StructureRules.resolve(lists.allow(), lists.deny(), lookup(registries), problems);
    }

    private static StructureRules.Lookup lookup(RegistryAccess registries) {
        Registry<Structure> structures = registries.registryOrThrow(Registries.STRUCTURE);
        Registry<StructureSet> sets = registries.registryOrThrow(Registries.STRUCTURE_SET);
        return new StructureRules.Lookup() {
            @Override
            public boolean hasStructure(ResourceLocation id) {
                return structures.containsKey(id);
            }

            @Override
            public boolean hasSet(ResourceLocation id) {
                return sets.containsKey(id);
            }

            @Override
            public Optional<Set<ResourceLocation>> structureTag(ResourceLocation tag) {
                return structures
                        .getTag(TagKey.create(Registries.STRUCTURE, tag))
                        .map(StructureFilter::ids);
            }

            @Override
            public Optional<Set<ResourceLocation>> setTag(ResourceLocation tag) {
                return sets.getTag(TagKey.create(Registries.STRUCTURE_SET, tag)).map(StructureFilter::ids);
            }
        };
    }

    private static <T> Set<ResourceLocation> ids(HolderSet.Named<T> tag) {
        return tag.stream()
                .flatMap(holder -> holder.unwrapKey().stream())
                .map(ResourceKey::location)
                .collect(Collectors.toSet());
    }
}
