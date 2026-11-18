package io.github.west3436.expedition.planet;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DynamicOps;
import io.github.west3436.expedition.api.planet.Planet;
import io.github.west3436.expedition.api.planet.PlanetAtmosphere;
import io.github.west3436.expedition.api.planet.StructureRef;
import io.github.west3436.expedition.planet.PlanetProblem.Severity;
import io.github.west3436.zas.api.ZasApi;
import io.github.west3436.zas.api.gas.GasProperties;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import javax.annotation.Nullable;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.neoforged.neoforge.common.conditions.ICondition;

/**
 * Loads the planet files of a set of datapacks as one: each file read by {@link PlanetFormat}, then
 * checked against the others. Two planets in one dimension, or with overlapping squares in Space,
 * both fail to load; so does a planet whose parent didn't load, or whose parents go round in a loop.
 *
 * <p>The reload listener ({@link PlanetReloadListener}) and {@code /expedition planet validate} load
 * the same way, so the command reports exactly what a {@code /reload} would do.
 */
public final class PlanetLoader {
    private static final FileToIdConverter FILES = FileToIdConverter.json(PlanetFormat.DIRECTORY);

    private PlanetLoader() {}

    /**
     * One planet file, as read from its pack.
     *
     * @param id the planet's id, from the file's namespace and path
     * @param file the file's label in problems: its path in the pack, and the pack
     * @param json the file's contents, or null if they aren't JSON
     * @param syntaxError why the file isn't JSON, or null if it is
     */
    public record Source(
            ResourceLocation id,
            String file,
            @Nullable JsonElement json,
            @Nullable String syntaxError) {
        /**
         * A file that parsed.
         *
         * @param id the planet's id
         * @param file the file's label
         * @param json the contents
         * @return the source
         */
        public static Source of(ResourceLocation id, String file, JsonElement json) {
            return new Source(id, file, json, null);
        }
    }

    /**
     * What loading gave.
     *
     * @param planets the planets that loaded, by id, sorted
     * @param files each loaded planet's file label, by planet id
     * @param problems every problem, errors and warnings, in the files' order
     * @param read how many files were read, conditions met or not
     */
    public record Result(
            Map<ResourceLocation, Planet> planets,
            Map<ResourceLocation, String> files,
            List<PlanetProblem> problems,
            int read) {
        /** No planets. */
        public static final Result EMPTY = new Result(Map.of(), Map.of(), List.of(), 0);

        /**
         * Copies the maps and list.
         */
        public Result {
            planets = Collections.unmodifiableMap(new TreeMap<>(planets));
            files = Map.copyOf(files);
            problems = List.copyOf(problems);
        }

        /**
         * The same result with more problems after these.
         *
         * @param more the problems to add
         * @return the result
         */
        public Result with(List<PlanetProblem> more) {
            List<PlanetProblem> all = new ArrayList<>(problems);
            all.addAll(more);
            return new Result(planets, files, all, read);
        }

        /**
         * How many files didn't load because of an error.
         *
         * @return the count
         */
        public long failedFiles() {
            return problems.stream()
                    .filter(p -> p.severity() == Severity.ERROR)
                    .map(PlanetProblem::file)
                    .distinct()
                    .count();
        }
    }

    /**
     * Reads every planet file in a resource manager: the top pack's copy of each.
     *
     * @param resources the server's resources
     * @return the files, sorted by id
     */
    public static List<Source> list(ResourceManager resources) {
        List<Source> sources = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Resource> entry :
                new TreeMap<>(FILES.listMatchingResources(resources)).entrySet()) {
            ResourceLocation path = entry.getKey();
            ResourceLocation id = FILES.fileToId(path);
            String file = "data/" + path.getNamespace() + "/" + path.getPath() + " ("
                    + entry.getValue().sourcePackId() + ")";
            try (Reader reader = entry.getValue().openAsReader()) {
                sources.add(Source.of(id, file, JsonParser.parseReader(reader)));
            } catch (Exception e) {
                String why = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                sources.add(new Source(id, file, null, String.valueOf(why)));
            }
        }
        return sources;
    }

    /**
     * Loads a set of planet files.
     *
     * @param sources the files
     * @param conditions the ops NeoForge's {@code neoforge:conditions} are tested with, or null to
     *     load every file whatever its conditions
     * @param refs what block and entity ids are checked against
     * @return the planets that loaded, and every problem
     */
    public static Result load(
            Collection<Source> sources, @Nullable DynamicOps<JsonElement> conditions, References refs) {
        List<PlanetProblem> problems = new ArrayList<>();
        Map<ResourceLocation, Planet> planets = new TreeMap<>();
        Map<ResourceLocation, String> files = new LinkedHashMap<>();
        for (Source source : sources) {
            if (source.json() == null) {
                problems.add(error(source.file(), "", "syntax", String.valueOf(source.syntaxError())));
                continue;
            }
            if (conditions != null
                    && source.json().isJsonObject()
                    && !ICondition.conditionsMatched(conditions, source.json())) {
                continue;
            }
            PlanetFormat.Read read = PlanetFormat.read(source.id(), source.file(), source.json(), refs);
            problems.addAll(read.problems());
            read.planet().ifPresent(planet -> {
                planets.put(planet.id(), planet);
                files.put(planet.id(), source.file());
            });
        }

        Set<ResourceLocation> failed = new HashSet<>();
        // Every pair is checked before any is dropped, so each of two clashing files names the other.
        List<Planet> all = List.copyOf(planets.values());
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                Planet a = all.get(i);
                Planet b = all.get(j);
                if (a.dimension().equals(b.dimension())) {
                    String dimension = a.dimension().location().toString();
                    problems.add(error(files.get(a.id()), "dimension", "same_dimension", dimension, files.get(b.id())));
                    problems.add(error(files.get(b.id()), "dimension", "same_dimension", dimension, files.get(a.id())));
                    failed.add(a.id());
                    failed.add(b.id());
                }
                if (a.space().overlaps(b.space())) {
                    problems.add(error(files.get(a.id()), "space", "overlap", b.id().toString(), files.get(b.id())));
                    problems.add(error(files.get(b.id()), "space", "overlap", a.id().toString(), files.get(a.id())));
                    failed.add(a.id());
                    failed.add(b.id());
                }
            }
        }
        failed.forEach(planets::remove);

        // Parents, until nothing more falls: a planet whose parent failed fails too.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Planet planet : List.copyOf(planets.values())) {
                Optional<ResourceLocation> parent = planet.space().parent();
                if (parent.isPresent() && !planets.containsKey(parent.get())) {
                    problems.add(error(
                            files.get(planet.id()),
                            "space.parent",
                            "unknown_parent",
                            parent.get().toString()));
                    planets.remove(planet.id());
                    changed = true;
                }
            }
            for (Planet planet : List.copyOf(planets.values())) {
                List<ResourceLocation> loop = loop(planet, planets);
                if (!loop.isEmpty()) {
                    List<String> names =
                            loop.stream().map(ResourceLocation::toString).toList();
                    String path = String.join(" → ", names) + " → " + names.getFirst();
                    for (ResourceLocation id : loop) {
                        problems.add(error(files.get(id), "space.parent", "parent_loop", path));
                        planets.remove(id);
                    }
                    changed = true;
                }
            }
        }
        files.keySet().retainAll(planets.keySet());
        return new Result(planets, files, problems, sources.size());
    }

    /** The planets in the loop through this one's parents, starting with it; empty if none. */
    private static List<ResourceLocation> loop(Planet planet, Map<ResourceLocation, Planet> planets) {
        List<ResourceLocation> chain = new ArrayList<>();
        Planet at = planet;
        while (at != null && !chain.contains(at.id())) {
            chain.add(at.id());
            at = at.space().parent().map(planets::get).orElse(null);
        }
        return at != null && at.id().equals(planet.id()) ? chain : List.of();
    }

    /**
     * Checks what loaded planets name that only a running server knows: that each dimension is one
     * of its levels, each gas one ZAS³ defines, and each structure and tag one its datapacks give.
     * These are warnings: the planet still loads.
     *
     * @param result the planets
     * @param server the server
     * @return the warnings
     */
    public static List<PlanetProblem> checkWorld(Result result, MinecraftServer server) {
        List<PlanetProblem> problems = new ArrayList<>();
        Registry<Structure> structures = server.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (Planet planet : result.planets().values()) {
            String file = result.files().get(planet.id());
            ResourceKey<Level> dimension = planet.dimension();
            if (server.getLevel(dimension) == null) {
                problems.add(warning(
                        file,
                        "dimension",
                        "unknown_dimension",
                        dimension.location().toString()));
            }
            if (ZasApi.isPresent() && planet.atmosphere().isPresent()) {
                PlanetAtmosphere air = planet.atmosphere().get();
                for (ResourceLocation gas : air.partialPressures().keySet()) {
                    if (!GasProperties.isDefined(gas)) {
                        problems.add(
                                warning(file, "atmosphere.partial_pressures." + gas, "unknown_gas", gas.toString()));
                    }
                }
            }
            checkStructures(
                    problems, file, "structures.allow", planet.structures().allow(), structures);
            checkStructures(
                    problems, file, "structures.deny", planet.structures().deny(), structures);
        }
        return problems;
    }

    private static void checkStructures(
            List<PlanetProblem> problems,
            String file,
            String field,
            List<StructureRef> refs,
            Registry<Structure> structures) {
        for (int i = 0; i < refs.size(); i++) {
            StructureRef ref = refs.get(i);
            if (ref.tag()) {
                if (structures
                        .getTag(TagKey.create(Registries.STRUCTURE, ref.id()))
                        .isEmpty()) {
                    problems.add(warning(file, field + "[" + i + "]", "unknown_structure_tag", ref.toString()));
                }
            } else if (!structures.containsKey(ref.id())) {
                problems.add(warning(file, field + "[" + i + "]", "unknown_structure", ref.toString()));
            }
        }
    }

    private static PlanetProblem error(String file, String field, String key, Object... args) {
        return new PlanetProblem(file, field, Severity.ERROR, key, List.of(args));
    }

    private static PlanetProblem warning(String file, String field, String key, Object... args) {
        return new PlanetProblem(file, field, Severity.WARNING, key, List.of(args));
    }
}
