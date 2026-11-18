package io.github.west3436.expedition.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import io.github.west3436.expedition.api.planet.Planet;
import io.github.west3436.expedition.planet.PlanetFormat;
import io.github.west3436.expedition.planet.References;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** Which planet's image a camera in Space sees below it, and how it is fitted into the view. */
class PlanetImagesTest {
    private static final ResourceLocation KEPLER_IMAGE =
            ResourceLocation.fromNamespaceAndPath("mypack", "textures/planet/kepler.png");

    /** Kepler's square runs from 10000 to 14000 along X and from -6000 to -2000 along Z. */
    private static final Planet KEPLER =
            planet("kepler", "{ \"center\": [12000, -4000], \"size\": 4000, \"image\": \"" + KEPLER_IMAGE + "\" }");

    /** A planet with no image, beside it. */
    private static final Planet BARE = planet("bare", "{ \"center\": [20000, 0], \"size\": 1000 }");

    private static final List<Planet> PLANETS = List.of(BARE, KEPLER);
    private static final double PLANE = PlanetImages.planeY(-64);

    @Test
    void theImagesLieBelowTheVoid() {
        assertEquals(-128, PLANE);
    }

    @Test
    void overASquareItsImageCoversTheSquareExactly() {
        PlanetImages.Image image =
                PlanetImages.below(PLANETS, 12_000.5, 100, -4_000.5, PLANE).orElseThrow();
        assertEquals(new PlanetImages.Image(KEPLER_IMAGE, 10_000, -6_000, 14_000, -2_000, PLANE), image);
    }

    @Test
    void outsideEverySquareNothingIsSeen() {
        assertTrue(PlanetImages.below(PLANETS, 10_000, 100, -6_000, PLANE).isPresent(), "the square's first column");
        assertTrue(PlanetImages.below(PLANETS, 13_999.9, 100, -2_000.1, PLANE).isPresent(), "its last column");
        assertEquals(Optional.empty(), PlanetImages.below(PLANETS, 9_999.9, 100, -4_000, PLANE), "just west of it");
        assertEquals(Optional.empty(), PlanetImages.below(PLANETS, 14_000, 100, -4_000, PLANE), "just east of it");
        assertEquals(Optional.empty(), PlanetImages.below(PLANETS, 12_000, 100, -2_000, PLANE), "just south of it");
        assertEquals(Optional.empty(), PlanetImages.below(PLANETS, 0, 100, 0, PLANE), "far from any square");
    }

    @Test
    void aPlanetWithNoImageShowsNothing() {
        assertEquals(Optional.empty(), PlanetImages.below(PLANETS, 20_000, 100, 0, PLANE));
    }

    @Test
    void nothingIsSeenFromBelowTheImages() {
        assertEquals(Optional.empty(), PlanetImages.below(PLANETS, 12_000, PLANE, -4_000, PLANE));
        assertEquals(Optional.empty(), PlanetImages.below(PLANETS, 12_000, PLANE - 10, -4_000, PLANE));
    }

    @Test
    void anImageIsShrunkSoItsFarthestCornerIsInReach() {
        PlanetImages.Image small = new PlanetImages.Image(KEPLER_IMAGE, 0, 0, 10, 10, 0);
        assertEquals(1, PlanetImages.shrink(small, 5, 10, 5, 768));

        PlanetImages.Image image =
                PlanetImages.below(PLANETS, 10_500, 100, -5_500, PLANE).orElseThrow();
        double reach = 384;
        double k = PlanetImages.shrink(image, 10_500, 100, -5_500, reach);
        // The far corner is (14000, -2000): 3500 and 3500 away, 228 above.
        double far = Math.sqrt(3_500.0 * 3_500 + 228.0 * 228 + 3_500.0 * 3_500);
        assertEquals(reach / far, k, 1e-12);
        assertTrue(far * k <= reach + 1e-9);
    }

    private static Planet planet(String name, String space) {
        String json = "{ \"dimension\": \"mypack:" + name + "\", \"space\": " + space + " }";
        return PlanetFormat.read(
                        ResourceLocation.fromNamespaceAndPath("mypack", name),
                        name + ".json",
                        JsonParser.parseString(json),
                        References.ANY)
                .planet()
                .orElseThrow();
    }
}
