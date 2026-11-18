package io.github.west3436.expedition.planet;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import io.github.west3436.expedition.api.planet.Planet;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * Loading planet files as a set, without the game: the checks between files, and the payload that
 * carries the planets to clients. A problem is written here as {@code "<file> <field> <key>"}.
 */
class PlanetLoaderTest {
    /** A planet file in the pack {@code test}. */
    private static PlanetLoader.Source file(String name, String dimension, int x, int z, int size, String parent) {
        String json = String.format(
                Locale.ROOT,
                "{ \"dimension\": \"%s\", \"space\": { \"center\": [%d, %d], \"size\": %d%s } }",
                dimension,
                x,
                z,
                size,
                parent == null ? "" : ", \"parent\": \"" + parent + "\"");
        return PlanetLoader.Source.of(
                ResourceLocation.fromNamespaceAndPath("test", name), name, JsonParser.parseString(json));
    }

    private static PlanetLoader.Source file(String name, int x, int z) {
        return file(name, "test:" + name, x, z, 1024, null);
    }

    private static PlanetLoader.Result load(PlanetLoader.Source... files) {
        return PlanetLoader.load(List.of(files), null, PlanetFormatTest.REFS);
    }

    private static List<String> problems(PlanetLoader.Result result) {
        return result.problems().stream()
                .map(p -> p.file() + " " + p.field() + " " + p.key())
                .toList();
    }

    private static List<String> loaded(PlanetLoader.Result result) {
        return result.planets().keySet().stream().map(ResourceLocation::getPath).toList();
    }

    @Test
    void planetsApartLoad() {
        PlanetLoader.Result result = load(file("b", 0, 0), file("a", 1024, 0), file("c", 0, -1024));
        assertEquals(List.of(), problems(result));
        assertEquals(List.of("a", "b", "c"), loaded(result), "sorted by id");
        assertEquals(3, result.read());
        assertEquals("b", result.files().get(ResourceLocation.fromNamespaceAndPath("test", "b")));
    }

    @Test
    void overlappingSquaresBothFail() {
        PlanetLoader.Result result = load(file("a", 0, 0), file("b", 1000, 1000), file("c", 5000, 0));
        assertEquals(List.of("a space overlap", "b space overlap"), problems(result));
        assertEquals(List.of("c"), loaded(result));
        assertEquals(List.of("test:b", "b"), result.problems().getFirst().args(), "each names the other");
        assertEquals(2, result.failedFiles());
    }

    @Test
    void twoPlanetsInOneDimensionBothFail() {
        PlanetLoader.Result result = load(
                file("a", "minecraft:the_end", 0, 0, 1024, null), file("b", "minecraft:the_end", 5000, 0, 1024, null));
        assertEquals(List.of("a dimension same_dimension", "b dimension same_dimension"), problems(result));
        assertEquals(List.of(), loaded(result));
    }

    @Test
    void aMoonNeedsItsPlanet() {
        PlanetLoader.Result ok = load(file("kepler", 0, 0), file("moon", "test:moon", 5000, 0, 512, "test:kepler"));
        assertEquals(List.of(), problems(ok));
        assertEquals(List.of("kepler", "moon"), loaded(ok));

        PlanetLoader.Result missing = load(file("moon", "test:moon", 5000, 0, 512, "test:kepler"));
        assertEquals(List.of("moon space.parent unknown_parent"), problems(missing));

        // The planet fails on its own, so the moon fails with it, and so does the moon's moon.
        PlanetLoader.Result failed = load(
                file("kepler", 0, 0),
                file("other", 100, 0),
                file("moon", "test:moon", 5000, 0, 512, "test:kepler"),
                file("moonlet", "test:moonlet", 9000, 0, 16, "test:moon"));
        assertEquals(
                List.of(
                        "kepler space overlap",
                        "other space overlap",
                        "moon space.parent unknown_parent",
                        "moonlet space.parent unknown_parent"),
                problems(failed));
        assertEquals(List.of(), loaded(failed));
    }

    @Test
    void parentsInALoopFail() {
        PlanetLoader.Result result = load(
                file("a", "test:a", 0, 0, 1024, "test:b"),
                file("b", "test:b", 5000, 0, 1024, "test:a"),
                file("self", "test:self", 10000, 0, 1024, "test:self"),
                file("c", 15000, 0));
        assertEquals(
                List.of("a space.parent parent_loop", "b space.parent parent_loop", "self space.parent parent_loop"),
                problems(result));
        assertEquals(List.of("c"), loaded(result));
        assertEquals(
                List.of("test:a → test:b → test:a"),
                result.problems().getFirst().args());
    }

    @Test
    void aFileThatIsntJsonFails() {
        PlanetLoader.Result result = load(
                new PlanetLoader.Source(ResourceLocation.fromNamespaceAndPath("test", "bad"), "bad", null, "oops"),
                file("good", 0, 0));
        assertEquals(List.of("bad  syntax"), problems(result));
        assertEquals(List.of("good"), loaded(result));
        assertEquals(2, result.read());
    }

    @Test
    void theProblemsOfOneFileDontStopTheOthers() {
        PlanetLoader.Result result = load(
                PlanetLoader.Source.of(
                        ResourceLocation.fromNamespaceAndPath("test", "broken"),
                        "broken",
                        JsonParser.parseString("{ \"dimension\": \"test:broken\" }")),
                file("good", 0, 0));
        assertEquals(List.of("broken space missing"), problems(result));
        assertEquals(List.of("good"), loaded(result));
    }

    @Test
    void thePayloadCarriesEveryPlanet() {
        List<Planet> planets = new ArrayList<>();
        planets.add(PlanetFormat.read(
                        PlanetFormatTest.ID,
                        "full",
                        JsonParser.parseString(PlanetFormatTest.FULL),
                        PlanetFormatTest.REFS)
                .planet()
                .orElseThrow());
        planets.addAll(load(file("b", 50000, 0)).planets().values());
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        PlanetsPayload.STREAM_CODEC.encode(buf, new PlanetsPayload(planets));
        assertEquals(planets, PlanetsPayload.STREAM_CODEC.decode(buf).planets());
        assertEquals(0, buf.readableBytes());
    }
}
