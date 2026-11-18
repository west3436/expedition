package io.github.west3436.expedition.planet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;

/**
 * A planet's {@code structures} lists, resolved to structure and structure-set ids, and the rule
 * that applies them. It is pure, so the rule is tested without the game.
 *
 * <p>The rule, for a structure in a structure set:
 *
 * <ul>
 *   <li>{@code deny} removes it when it matches the structure or its set, and wins over {@code
 *       allow}.
 *   <li>{@code allow}, when the planet gives one, keeps only what it matches, by the structure or
 *       by its set. Left out or empty, it keeps everything.
 * </ul>
 *
 * <p>A filter can only take structures away. Biomes still decide where a kept structure may
 * generate, so {@code allow} can't put a structure on a planet whose biomes it doesn't list.
 *
 * @param allow what {@code allow} matches, or empty when the planet gives no {@code allow}
 * @param deny what {@code deny} matches
 */
public record StructureRules(Optional<Matcher> allow, Matcher deny) {
    /** Rules that keep every structure. */
    public static final StructureRules NONE = new StructureRules(Optional.empty(), Matcher.EMPTY);

    /**
     * Structures and structure sets, by id. A structure matches when its own id is in {@code
     * structures} or its set's id is in {@code sets}.
     *
     * @param structures structure ids
     * @param sets structure-set ids
     */
    public record Matcher(Set<ResourceLocation> structures, Set<ResourceLocation> sets) {
        /** Matches nothing. */
        public static final Matcher EMPTY = new Matcher(Set.of(), Set.of());

        public Matcher {
            structures = Set.copyOf(structures);
            sets = Set.copyOf(sets);
        }

        /**
         * Whether a structure matches.
         *
         * @param structure the structure's id, or null for one with none
         * @param set its set's id, or null for one with none
         * @return true when either id is listed
         */
        public boolean matches(@Nullable ResourceLocation structure, @Nullable ResourceLocation set) {
            return (structure != null && structures.contains(structure)) || (set != null && sets.contains(set));
        }
    }

    /**
     * Whether a structure may stay.
     *
     * @param structure the structure's id, or null for one with none
     * @param set its set's id, or null for one with none
     * @return false when {@code deny} matches it, or {@code allow} is given and doesn't
     */
    public boolean keeps(@Nullable ResourceLocation structure, @Nullable ResourceLocation set) {
        if (deny.matches(structure, set)) {
            return false;
        }
        return allow.map(matcher -> matcher.matches(structure, set)).orElse(true);
    }

    /**
     * Whether these rules keep every structure, so a level's sets need no change.
     *
     * @return true with no {@code allow} and nothing denied
     */
    public boolean keepsEverything() {
        return allow.isEmpty() && deny.structures().isEmpty() && deny.sets().isEmpty();
    }

    /**
     * How {@link #filter} reads and rebuilds a structure set, so the rule runs on the game's types
     * and on a test's.
     *
     * @param <S> a structure set
     * @param <E> one structure entry in a set
     */
    public interface SetView<S, E> {
        /**
         * The set's id.
         *
         * @param set the set
         * @return its id, or empty for one with none
         */
        Optional<ResourceLocation> id(S set);

        /**
         * The set's structures.
         *
         * @param set the set
         * @return its entries, in order
         */
        List<E> entries(S set);

        /**
         * An entry's structure id.
         *
         * @param entry the entry
         * @return its structure's id, or empty for one with none
         */
        Optional<ResourceLocation> structure(E entry);

        /**
         * The set with fewer structures, and the same placement.
         *
         * @param set the set
         * @param kept the entries it keeps, in order, at least one
         * @return the new set
         */
        S rebuild(S set, List<E> kept);
    }

    /**
     * What {@link #filter} kept and took away.
     *
     * @param sets the sets kept, in order: unchanged, or rebuilt with fewer structures
     * @param removed the ids of the structures taken away, sorted
     * @param <S> a structure set
     */
    public record Filtered<S>(List<S> sets, Set<String> removed) {}

    /**
     * Applies the rules to a level's structure sets. A set that keeps all its structures stays as
     * it was; one that keeps some is rebuilt with the same placement; one that keeps none is
     * dropped.
     *
     * @param sets the level's sets, in order
     * @param view how to read and rebuild them
     * @param <S> a structure set
     * @param <E> one structure entry in a set
     * @return the sets kept, and what was taken away
     */
    public <S, E> Filtered<S> filter(List<S> sets, SetView<S, E> view) {
        List<S> kept = new ArrayList<>(sets.size());
        Set<String> removed = new TreeSet<>();
        for (S set : sets) {
            ResourceLocation setId = view.id(set).orElse(null);
            List<E> entries = view.entries(set);
            List<E> keptEntries = new ArrayList<>(entries.size());
            for (E entry : entries) {
                ResourceLocation structure = view.structure(entry).orElse(null);
                if (keeps(structure, setId)) {
                    keptEntries.add(entry);
                } else {
                    removed.add(structure != null ? structure.toString() : "an unnamed structure");
                }
            }
            if (keptEntries.size() == entries.size()) {
                kept.add(set);
            } else if (!keptEntries.isEmpty()) {
                kept.add(view.rebuild(set, List.copyOf(keptEntries)));
            }
        }
        return new Filtered<>(List.copyOf(kept), removed);
    }

    /**
     * What the rules can look up in the game's registries.
     */
    public interface Lookup {
        /**
         * Whether a structure exists.
         *
         * @param id its id
         * @return true when it does
         */
        boolean hasStructure(ResourceLocation id);

        /**
         * Whether a structure set exists.
         *
         * @param id its id
         * @return true when it does
         */
        boolean hasSet(ResourceLocation id);

        /**
         * A structure tag's members.
         *
         * @param tag the tag's id
         * @return its structures' ids, or empty when there is no such tag
         */
        Optional<Set<ResourceLocation>> structureTag(ResourceLocation tag);

        /**
         * A structure-set tag's members.
         *
         * @param tag the tag's id
         * @return its sets' ids, or empty when there is no such tag
         */
        Optional<Set<ResourceLocation>> setTag(ResourceLocation tag);
    }

    /**
     * Resolves a planet's lists as written. An entry is a structure id, a structure-set id, or
     * either kind of tag, written with {@code #}. A plain id is looked up as both a structure and a
     * set: vanilla has ids that are both, such as {@code minecraft:trial_chambers}, and each such set
     * holds only the structure of its name. A tag is looked up as a structure tag and as a
     * structure-set tag.
     *
     * <p>An entry that names nothing is reported and skipped. An {@code allow} that resolves to
     * nothing still keeps nothing, since the planet gave one.
     *
     * @param allow the {@code allow} list, empty when left out
     * @param deny the {@code deny} list, empty when left out
     * @param lookup the registries
     * @param problems told of each entry that names nothing
     * @return the rules
     */
    public static StructureRules resolve(
            List<String> allow, List<String> deny, Lookup lookup, Consumer<String> problems) {
        Optional<Matcher> allowed =
                allow.isEmpty() ? Optional.empty() : Optional.of(matcher("allow", allow, lookup, problems));
        return new StructureRules(allowed, matcher("deny", deny, lookup, problems));
    }

    private static Matcher matcher(String field, List<String> entries, Lookup lookup, Consumer<String> problems) {
        Set<ResourceLocation> structures = new HashSet<>();
        Set<ResourceLocation> sets = new HashSet<>();
        for (String entry : entries) {
            boolean tag = entry.startsWith("#");
            ResourceLocation id = ResourceLocation.tryParse(tag ? entry.substring(1) : entry);
            if (id == null) {
                problems.accept(field + ": '" + entry + "' isn't an id");
                continue;
            }
            if (tag) {
                Optional<Set<ResourceLocation>> ofStructures = lookup.structureTag(id);
                Optional<Set<ResourceLocation>> ofSets = lookup.setTag(id);
                ofStructures.ifPresent(structures::addAll);
                ofSets.ifPresent(sets::addAll);
                if (ofStructures.isEmpty() && ofSets.isEmpty()) {
                    problems.accept(field + ": no structure or structure-set tag " + entry);
                }
            } else {
                boolean structure = lookup.hasStructure(id);
                boolean set = lookup.hasSet(id);
                if (structure) {
                    structures.add(id);
                }
                if (set) {
                    sets.add(id);
                }
                if (!structure && !set) {
                    problems.accept(field + ": no structure or structure set " + entry);
                }
            }
        }
        return new Matcher(structures, sets);
    }
}
