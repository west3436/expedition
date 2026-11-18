package io.github.west3436.expedition.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * Every image a planet file in this project names is a square PNG in the project's assets: the
 * mod's own planets' in the mod, the test planet's in the GameTests' resources.
 */
class PlanetImageFilesTest {
    private static final List<String> SETS = List.of("main", "gametest");

    @Test
    void everyNamedImageIsASquarePng() throws IOException {
        Map<String, String> named = new TreeMap<>();
        for (String set : SETS) {
            for (Path file : planetFiles(Path.of("src", set, "resources"))) {
                JsonObject space = JsonParser.parseString(Files.readString(file))
                        .getAsJsonObject()
                        .getAsJsonObject("space");
                if (space != null && space.has("image")) {
                    ResourceLocation image =
                            ResourceLocation.parse(space.get("image").getAsString());
                    Path png = find(image);
                    assertTrue(png != null, file + " names " + image + ", which is in no source set's assets");
                    BufferedImage read = ImageIO.read(png.toFile());
                    assertTrue(read != null, png + " isn't an image");
                    assertEquals(read.getWidth(), read.getHeight(), png + " isn't square");
                    named.put(file.getFileName().toString(), image.toString());
                }
            }
        }
        assertEquals(
                Map.of(
                        "kepler.json", "expedition_test:textures/planet/kepler.png",
                        "moon.json", "expedition:textures/planet/moon.png",
                        "overworld.json", "expedition:textures/planet/overworld.png",
                        "the_end.json", "expedition:textures/planet/the_end.png",
                        "the_nether.json", "expedition:textures/planet/the_nether.png"),
                named,
                "the planets with images");
    }

    /** Where an asset is, in whichever source set holds it; the GameTests load both. */
    private static Path find(ResourceLocation image) {
        for (String set : SETS) {
            Path png = Path.of("src", set, "resources", "assets", image.getNamespace())
                    .resolve(image.getPath());
            if (Files.isRegularFile(png)) {
                return png;
            }
        }
        return null;
    }

    /** Every {@code expedition/planet/*.json} under a resources folder, datapacks included. */
    private static List<Path> planetFiles(Path resources) throws IOException {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(resources)) {
            return files;
        }
        try (Stream<Path> all = Files.walk(resources)) {
            all.filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> p.getParent() != null
                            && p.getParent().getFileName().toString().equals("planet")
                            && p.getParent().getParent() != null
                            && p.getParent()
                                    .getParent()
                                    .getFileName()
                                    .toString()
                                    .equals("expedition"))
                    .forEach(files::add);
        }
        return files;
    }
}
