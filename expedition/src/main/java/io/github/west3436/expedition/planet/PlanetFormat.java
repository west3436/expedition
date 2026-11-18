package io.github.west3436.expedition.planet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
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
import io.github.west3436.expedition.planet.PlanetProblem.Severity;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.MobCategory;

/**
 * The planet file format: one file read into a {@link Planet}, with every problem it has named by
 * its field, and a planet written back out.
 *
 * <p>Reading checks each field's type and range and fills in the defaults the API's value types
 * name; an unknown field is a problem too, so a misspelt one is never silently ignored. Everything
 * wrong with a file is reported, not only the first thing. Checks between files (two planets in one
 * dimension, overlapping squares, parents) are {@link PlanetLoader}'s.
 */
public final class PlanetFormat {
    /** Where planet files live in a datapack: {@code data/<namespace>/expedition/planet/}. */
    public static final String DIRECTORY = "expedition/planet";

    /** NeoForge's load conditions, which a file may carry at its top. */
    public static final String CONDITIONS = "neoforge:conditions";

    private PlanetFormat() {}

    /**
     * What reading one file gave.
     *
     * @param planet the planet, if the file has no problem
     * @param problems every problem, each an {@link Severity#ERROR}
     */
    public record Read(Optional<Planet> planet, List<PlanetProblem> problems) {}

    /**
     * Reads a planet file.
     *
     * @param id the planet's id: its file's namespace and path
     * @param file the file's label in problems
     * @param json the file's contents
     * @param refs what the file's block and entity ids are checked against
     * @return the planet, or its problems
     */
    public static Read read(ResourceLocation id, String file, JsonElement json, References refs) {
        Reader r = new Reader(file, refs);
        Planet planet = r.planet(id, json);
        if (!r.problems.isEmpty()) {
            return new Read(Optional.empty(), List.copyOf(r.problems));
        }
        return new Read(Optional.of(planet), List.of());
    }

    /**
     * Writes a planet as a file that reads back as the same planet. Fields at their defaults are
     * written too, except an ore's height range when it has none.
     *
     * @param planet the planet
     * @return the file's contents
     */
    public static JsonObject write(Planet planet) {
        JsonObject root = new JsonObject();
        root.addProperty("dimension", planet.dimension().location().toString());

        SpaceSquare space = planet.space();
        JsonObject spaceJson = new JsonObject();
        spaceJson.add("center", pair(space.centerX(), space.centerZ()));
        spaceJson.addProperty("size", space.size());
        space.image().ifPresent(image -> spaceJson.addProperty("image", image.toString()));
        space.parent().ifPresent(parent -> spaceJson.addProperty("parent", parent.toString()));
        root.add("space", spaceJson);

        JsonObject surface = new JsonObject();
        surface.add("center", pair(planet.surface().centerX(), planet.surface().centerZ()));
        root.add("surface", surface);

        planet.atmosphere().ifPresent(air -> {
            JsonObject json = new JsonObject();
            JsonObject pressures = new JsonObject();
            air.partialPressures().forEach((gas, kPa) -> pressures.addProperty(gas.toString(), kPa));
            if (!air.vacuum()) {
                json.add("partial_pressures", pressures);
            }
            if (air.dayTemperature() == air.nightTemperature()) {
                json.addProperty("temperature", air.dayTemperature());
            } else {
                JsonObject temperature = new JsonObject();
                temperature.addProperty("day", air.dayTemperature());
                temperature.addProperty("night", air.nightTemperature());
                json.add("temperature", temperature);
            }
            json.addProperty("vacuum", air.vacuum());
            root.add("atmosphere", json);
        });

        root.addProperty("gravity", planet.gravity());

        JsonArray ores = new JsonArray();
        for (PlanetOre ore : planet.ores()) {
            JsonObject json = new JsonObject();
            json.addProperty("block", ore.block().toString());
            json.addProperty("weight", ore.weight());
            json.addProperty("vein_size", ore.veinSize());
            if (!ore.anyHeight()) {
                json.add("y", pair(ore.minY(), ore.maxY()));
            }
            ores.add(json);
        }
        root.add("ores", ores);
        root.addProperty("ore_density", planet.oreDensity());

        JsonObject mobs = new JsonObject();
        mobs.addProperty("replace_existing", planet.mobs().replaceExisting());
        planet.mobs().spawns().forEach((category, list) -> {
            JsonArray array = new JsonArray();
            for (PlanetMob mob : list) {
                JsonObject json = new JsonObject();
                json.addProperty("type", mob.type().toString());
                json.addProperty("weight", mob.weight());
                json.add("group", pair(mob.minGroup(), mob.maxGroup()));
                array.add(json);
            }
            mobs.add(category.getSerializedName(), array);
        });
        root.add("mobs", mobs);

        JsonObject structures = new JsonObject();
        structures.add("allow", refs(planet.structures().allow()));
        structures.add("deny", refs(planet.structures().deny()));
        root.add("structures", structures);

        JsonObject access = new JsonObject();
        access.addProperty("hidden_until_scanned", planet.access().hiddenUntilScanned());
        root.add("access", access);
        return root;
    }

    private static JsonArray pair(int a, int b) {
        JsonArray array = new JsonArray();
        array.add(a);
        array.add(b);
        return array;
    }

    private static JsonArray refs(List<StructureRef> refs) {
        JsonArray array = new JsonArray();
        refs.forEach(ref -> array.add(ref.toString()));
        return array;
    }

    /** The mob categories as files write them, in vanilla's order. */
    static String categories() {
        return Arrays.stream(MobCategory.values())
                .map(MobCategory::getSerializedName)
                .collect(Collectors.joining(", "));
    }

    // ---- Reading ------------------------------------------------------------------------

    /** One file's read: the problems found so far. Each method returns null for a value it refused. */
    private static final class Reader {
        private final String file;
        private final References refs;
        private final List<PlanetProblem> problems = new ArrayList<>();

        Reader(String file, References refs) {
            this.file = file;
            this.refs = refs;
        }

        void error(String field, String key, Object... args) {
            problems.add(new PlanetProblem(file, field, Severity.ERROR, key, List.of(args)));
        }

        @Nullable
        Planet planet(ResourceLocation id, JsonElement json) {
            Fields root = fields("", json);
            if (root == null) {
                return null;
            }
            int before = problems.size();
            ResourceLocation dimension = root.required("dimension", this::id);
            SpaceSquare space = root.required("space", this::space);
            PlanetSurface surface = root.optional("surface", this::surface, PlanetSurface.DEFAULT);
            Optional<PlanetAtmosphere> atmosphere =
                    Optional.ofNullable(root.optional("atmosphere", this::atmosphere, null));
            Double gravity =
                    root.optional("gravity", (f, e) -> number(f, e, 0, Planet.MAX_GRAVITY), Planet.DEFAULT_GRAVITY);
            List<PlanetOre> ores = root.optional("ores", this::ores, List.of());
            Double oreDensity = root.optional(
                    "ore_density", (f, e) -> number(f, e, 0, Planet.MAX_ORE_DENSITY), Planet.DEFAULT_ORE_DENSITY);
            PlanetMobs mobs = root.optional("mobs", this::mobs, PlanetMobs.DEFAULT);
            PlanetStructures structures = root.optional("structures", this::structures, PlanetStructures.DEFAULT);
            PlanetAccess access = root.optional("access", this::access, PlanetAccess.DEFAULT);
            root.finish(CONDITIONS);
            if (problems.size() > before) {
                return null;
            }
            try {
                return new Planet(
                        id,
                        ResourceKey.create(Registries.DIMENSION, dimension),
                        space,
                        surface,
                        atmosphere,
                        gravity,
                        ores,
                        oreDensity,
                        mobs,
                        structures,
                        access);
            } catch (IllegalArgumentException e) {
                // Every check is made above, field by field; this is a backstop.
                error("", "invalid", e.getMessage());
                return null;
            }
        }

        @Nullable
        SpaceSquare space(String field, JsonElement json) {
            Fields f = fields(field, json);
            if (f == null) {
                return null;
            }
            int before = problems.size();
            int[] center = f.required("center", (at, e) -> pair(at, e, -SpaceSquare.LIMIT, SpaceSquare.LIMIT));
            Integer size = f.optional(
                    "size",
                    (at, e) -> integer(at, e, SpaceSquare.MIN_SIZE, SpaceSquare.MAX_SIZE),
                    SpaceSquare.DEFAULT_SIZE);
            ResourceLocation image = f.optional("image", this::id, null);
            ResourceLocation parent = f.optional("parent", this::id, null);
            f.finish();
            if (problems.size() > before) {
                return null;
            }
            for (int c : center) {
                long min = (long) c - size / 2;
                if (min < -SpaceSquare.LIMIT || min + size > SpaceSquare.LIMIT) {
                    error(field, "space_outside", SpaceSquare.LIMIT);
                    return null;
                }
            }
            return new SpaceSquare(center[0], center[1], size, Optional.ofNullable(image), Optional.ofNullable(parent));
        }

        @Nullable
        PlanetSurface surface(String field, JsonElement json) {
            Fields f = fields(field, json);
            if (f == null) {
                return null;
            }
            int[] center = f.optional(
                    "center", (at, e) -> pair(at, e, -SpaceSquare.LIMIT, SpaceSquare.LIMIT), new int[] {0, 0});
            f.finish();
            return center == null ? null : new PlanetSurface(center[0], center[1]);
        }

        @Nullable
        PlanetAtmosphere atmosphere(String field, JsonElement json) {
            Fields f = fields(field, json);
            if (f == null) {
                return null;
            }
            int before = problems.size();
            Boolean vacuum = f.optional("vacuum", this::bool, false);
            boolean given = f.has("partial_pressures");
            Map<ResourceLocation, Double> pressures = f.optional("partial_pressures", this::pressures, null);
            double[] temperature = f.required("temperature", this::temperature);
            f.finish();
            String pressuresField = Fields.at(field, "partial_pressures");
            if (vacuum != null && pressures != null) {
                if (vacuum && !pressures.isEmpty()) {
                    error(pressuresField, "vacuum_with_gases");
                } else if (!vacuum
                        && pressures.values().stream()
                                        .mapToDouble(Double::doubleValue)
                                        .sum()
                                <= 0) {
                    error(pressuresField, "no_pressure");
                }
            } else if (vacuum != null && !vacuum && !given) {
                error(pressuresField, "air_required");
            }
            if (problems.size() > before) {
                return null;
            }
            return new PlanetAtmosphere(
                    pressures == null ? Map.of() : pressures, temperature[0], temperature[1], vacuum);
        }

        @Nullable
        Map<ResourceLocation, Double> pressures(String field, JsonElement json) {
            Fields f = fields(field, json);
            if (f == null) {
                return null;
            }
            Map<ResourceLocation, Double> pressures = new LinkedHashMap<>();
            boolean ok = true;
            for (String key : f.keys()) {
                String at = Fields.at(field, key);
                ResourceLocation gas = id(at, new JsonPrimitive(key));
                Double kPa = f.required(key, (a, e) -> number(a, e, 0, Double.MAX_VALUE));
                if (gas == null || kPa == null) {
                    ok = false;
                } else {
                    pressures.put(gas, kPa);
                }
            }
            return ok ? pressures : null;
        }

        @Nullable
        double[] temperature(String field, JsonElement json) {
            if (json.isJsonObject()) {
                Fields f = fields(field, json);
                Double day = f.required("day", this::kelvin);
                Double night = f.required("night", this::kelvin);
                f.finish();
                return day == null || night == null ? null : new double[] {day, night};
            }
            if (!isNumber(json)) {
                error(field, "not_temperature", shown(json));
                return null;
            }
            Double kelvin = kelvin(field, json);
            return kelvin == null ? null : new double[] {kelvin, kelvin};
        }

        @Nullable
        Double kelvin(String field, JsonElement json) {
            Double value = number(field, json, 0, Double.MAX_VALUE);
            if (value != null && value <= 0) {
                error(field, "above", value, 0);
                return null;
            }
            return value;
        }

        @Nullable
        List<PlanetOre> ores(String field, JsonElement json) {
            JsonArray array = array(field, json);
            if (array == null) {
                return null;
            }
            List<PlanetOre> ores = new ArrayList<>();
            Set<ResourceLocation> blocks = new HashSet<>();
            boolean ok = true;
            for (int i = 0; i < array.size(); i++) {
                String at = field + "[" + i + "]";
                PlanetOre ore = ore(at, array.get(i));
                if (ore == null) {
                    ok = false;
                } else if (!blocks.add(ore.block())) {
                    error(at + ".block", "duplicate", ore.block().toString());
                    ok = false;
                } else {
                    ores.add(ore);
                }
            }
            return ok ? ores : null;
        }

        @Nullable
        PlanetOre ore(String field, JsonElement json) {
            Fields f = fields(field, json);
            if (f == null) {
                return null;
            }
            int before = problems.size();
            ResourceLocation block = f.required("block", this::id);
            if (block != null && !refs.isBlock(block)) {
                error(Fields.at(field, "block"), "unknown_block", block.toString());
            }
            Integer weight = f.required("weight", (at, e) -> integer(at, e, 1, Integer.MAX_VALUE));
            Integer veinSize = f.optional(
                    "vein_size", (at, e) -> integer(at, e, 1, PlanetOre.MAX_VEIN_SIZE), PlanetOre.DEFAULT_VEIN_SIZE);
            int[] y = f.optional("y", (at, e) -> range(at, e, PlanetOre.MIN_Y, PlanetOre.MAX_Y), new int[] {
                PlanetOre.MIN_Y, PlanetOre.MAX_Y
            });
            f.finish();
            if (problems.size() > before) {
                return null;
            }
            return new PlanetOre(block, weight, veinSize, y[0], y[1]);
        }

        @Nullable
        PlanetMobs mobs(String field, JsonElement json) {
            Fields f = fields(field, json);
            if (f == null) {
                return null;
            }
            int before = problems.size();
            Boolean replace = f.optional("replace_existing", this::bool, false);
            Map<MobCategory, List<PlanetMob>> spawns = new EnumMap<>(MobCategory.class);
            for (String key : f.keys()) {
                if (key.equals("replace_existing")) {
                    continue;
                }
                MobCategory category = category(key);
                if (category == null) {
                    error(Fields.at(field, key), "unknown_category", key, categories());
                    f.skip(key);
                    continue;
                }
                List<PlanetMob> list = f.required(key, this::mobList);
                if (list != null) {
                    spawns.put(category, list);
                }
            }
            f.finish();
            return problems.size() > before ? null : new PlanetMobs(replace, spawns);
        }

        @Nullable
        List<PlanetMob> mobList(String field, JsonElement json) {
            JsonArray array = array(field, json);
            if (array == null) {
                return null;
            }
            List<PlanetMob> mobs = new ArrayList<>();
            Set<ResourceLocation> types = new HashSet<>();
            boolean ok = true;
            for (int i = 0; i < array.size(); i++) {
                String at = field + "[" + i + "]";
                PlanetMob mob = mob(at, array.get(i));
                if (mob == null) {
                    ok = false;
                } else if (!types.add(mob.type())) {
                    error(at + ".type", "duplicate", mob.type().toString());
                    ok = false;
                } else {
                    mobs.add(mob);
                }
            }
            return ok ? mobs : null;
        }

        @Nullable
        PlanetMob mob(String field, JsonElement json) {
            Fields f = fields(field, json);
            if (f == null) {
                return null;
            }
            int before = problems.size();
            ResourceLocation type = f.required("type", this::id);
            if (type != null && !refs.isEntityType(type)) {
                error(Fields.at(field, "type"), "unknown_entity_type", type.toString());
            }
            Integer weight = f.required("weight", (at, e) -> integer(at, e, 1, Integer.MAX_VALUE));
            int[] group = f.optional("group", (at, e) -> range(at, e, 1, PlanetMob.MAX_GROUP), new int[] {1, 1});
            f.finish();
            if (problems.size() > before) {
                return null;
            }
            return new PlanetMob(type, weight, group[0], group[1]);
        }

        @Nullable
        PlanetStructures structures(String field, JsonElement json) {
            Fields f = fields(field, json);
            if (f == null) {
                return null;
            }
            int before = problems.size();
            List<StructureRef> allow = f.optional("allow", this::structureList, List.of());
            List<StructureRef> deny = f.optional("deny", this::structureList, List.of());
            f.finish();
            if (allow != null && deny != null) {
                for (int i = 0; i < deny.size(); i++) {
                    if (allow.contains(deny.get(i))) {
                        error(
                                Fields.at(field, "deny") + "[" + i + "]",
                                "allowed_and_denied",
                                deny.get(i).toString());
                    }
                }
            }
            return problems.size() > before ? null : new PlanetStructures(allow, deny);
        }

        @Nullable
        List<StructureRef> structureList(String field, JsonElement json) {
            JsonArray array = array(field, json);
            if (array == null) {
                return null;
            }
            Set<StructureRef> refs = new LinkedHashSet<>();
            boolean ok = true;
            for (int i = 0; i < array.size(); i++) {
                String at = field + "[" + i + "]";
                String text = string(at, array.get(i));
                if (text == null) {
                    ok = false;
                    continue;
                }
                boolean tag = text.startsWith("#");
                ResourceLocation id = ResourceLocation.tryParse(tag ? text.substring(1) : text);
                if (id == null) {
                    error(at, "not_structure", text);
                    ok = false;
                } else if (!refs.add(new StructureRef(id, tag))) {
                    error(at, "duplicate", text);
                    ok = false;
                }
            }
            return ok ? List.copyOf(refs) : null;
        }

        @Nullable
        PlanetAccess access(String field, JsonElement json) {
            Fields f = fields(field, json);
            if (f == null) {
                return null;
            }
            Boolean hidden = f.optional("hidden_until_scanned", this::bool, false);
            f.finish();
            return hidden == null ? null : new PlanetAccess(hidden);
        }

        // ---- Leaves ---------------------------------------------------------------------

        @Nullable
        Fields fields(String field, JsonElement json) {
            if (!json.isJsonObject()) {
                error(field, "not_object", shown(json));
                return null;
            }
            return new Fields(this, field, json.getAsJsonObject());
        }

        @Nullable
        JsonArray array(String field, JsonElement json) {
            if (!json.isJsonArray()) {
                error(field, "not_list", shown(json));
                return null;
            }
            return json.getAsJsonArray();
        }

        @Nullable
        String string(String field, JsonElement json) {
            if (!json.isJsonPrimitive() || !json.getAsJsonPrimitive().isString()) {
                error(field, "not_string", shown(json));
                return null;
            }
            return json.getAsString();
        }

        @Nullable
        ResourceLocation id(String field, JsonElement json) {
            String text = string(field, json);
            if (text == null) {
                return null;
            }
            ResourceLocation id = ResourceLocation.tryParse(text);
            if (id == null) {
                error(field, "not_id", text);
            }
            return id;
        }

        @Nullable
        Boolean bool(String field, JsonElement json) {
            if (!json.isJsonPrimitive() || !json.getAsJsonPrimitive().isBoolean()) {
                error(field, "not_boolean", shown(json));
                return null;
            }
            return json.getAsBoolean();
        }

        @Nullable
        Double number(String field, JsonElement json, double min, double max) {
            if (!isNumber(json)) {
                error(field, "not_number", shown(json));
                return null;
            }
            double value = json.getAsDouble();
            if (!Double.isFinite(value)) {
                error(field, "not_number", shown(json));
                return null;
            }
            if (value < min || value > max) {
                rangeError(field, value, min, max);
                return null;
            }
            return value;
        }

        @Nullable
        Integer integer(String field, JsonElement json, int min, int max) {
            if (!isNumber(json)) {
                error(field, "not_integer", shown(json));
                return null;
            }
            BigDecimal value = json.getAsBigDecimal();
            BigDecimal whole;
            try {
                whole = value.setScale(0);
            } catch (ArithmeticException e) {
                error(field, "not_integer", shown(json));
                return null;
            }
            if (whole.compareTo(BigDecimal.valueOf(min)) < 0 || whole.compareTo(BigDecimal.valueOf(max)) > 0) {
                rangeError(field, whole.toPlainString(), min, max);
                return null;
            }
            return whole.intValueExact();
        }

        void rangeError(String field, Object value, double min, double max) {
            if (max == Double.MAX_VALUE || max == Integer.MAX_VALUE) {
                error(field, "at_least", value, number(min));
            } else {
                error(field, "out_of_range", value, number(min), number(max));
            }
        }

        /** Two whole numbers, each from {@code min} to {@code max}. */
        @Nullable
        int[] pair(String field, JsonElement json, int min, int max) {
            if (!json.isJsonArray() || json.getAsJsonArray().size() != 2) {
                error(field, "not_pair", shown(json));
                return null;
            }
            JsonArray array = json.getAsJsonArray();
            Integer a = integer(field + "[0]", array.get(0), min, max);
            Integer b = integer(field + "[1]", array.get(1), min, max);
            return a == null || b == null ? null : new int[] {a, b};
        }

        /** A pair whose first number is no greater than its second. */
        @Nullable
        int[] range(String field, JsonElement json, int min, int max) {
            int[] pair = pair(field, json, min, max);
            if (pair != null && pair[0] > pair[1]) {
                error(field, "min_above_max", pair[0], pair[1]);
                return null;
            }
            return pair;
        }

        @Nullable
        static MobCategory category(String name) {
            for (MobCategory category : MobCategory.values()) {
                if (category.getSerializedName().equals(name)) {
                    return category;
                }
            }
            return null;
        }

        static boolean isNumber(JsonElement json) {
            return json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber();
        }

        /** A number as a message shows it: whole numbers without a decimal point. */
        static Object number(double value) {
            return value == Math.rint(value) && Math.abs(value) < 1e15 ? (Object) (long) value : value;
        }

        /** A JSON value as a message quotes it, cut short if it is long. */
        static String shown(JsonElement json) {
            String text = json.toString();
            return text.length() > 40 ? text.substring(0, 37) + "..." : text;
        }
    }

    /** One JSON object's fields: which have been read, so the rest can be reported as unknown. */
    private static final class Fields {
        private final Reader reader;
        private final String field;
        private final JsonObject json;
        private final Set<String> read = new LinkedHashSet<>();

        Fields(Reader reader, String field, JsonObject json) {
            this.reader = reader;
            this.field = field;
            this.json = json;
        }

        static String at(String field, String name) {
            return field.isEmpty() ? name : field + "." + name;
        }

        List<String> keys() {
            return List.copyOf(json.keySet());
        }

        void skip(String name) {
            read.add(name);
        }

        boolean has(String name) {
            return json.has(name);
        }

        @Nullable
        <T> T required(String name, Value<T> value) {
            read.add(name);
            JsonElement e = json.get(name);
            if (e == null) {
                reader.error(at(field, name), "missing");
                return null;
            }
            return value.read(at(field, name), e);
        }

        @Nullable
        <T> T optional(String name, Value<T> value, @Nullable T fallback) {
            read.add(name);
            JsonElement e = json.get(name);
            return e == null ? fallback : value.read(at(field, name), e);
        }

        /** Reports each field that wasn't read, besides {@code allowed}. */
        void finish(String... allowed) {
            Set<String> ok = new HashSet<>(read);
            ok.addAll(List.of(allowed));
            for (String key : json.keySet()) {
                if (!ok.contains(key)) {
                    reader.error(at(field, key), "unknown_field", String.join(", ", read));
                }
            }
        }
    }

    @FunctionalInterface
    private interface Value<T> {
        @Nullable
        T read(String field, JsonElement json);
    }
}
