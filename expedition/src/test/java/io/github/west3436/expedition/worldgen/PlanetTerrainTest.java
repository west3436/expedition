package io.github.west3436.expedition.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.west3436.expedition.worldgen.PlanetTerrain.Layer;
import org.junit.jupiter.api.Test;

/** A planet's land and its layers, without the game. */
class PlanetTerrainTest {
    /** The generator's default dials: ground level 64, amplitude 8, scale 128. */
    private static PlanetTerrain defaults(long seed) {
        return new PlanetTerrain(seed, 64, 8, 128);
    }

    @Test
    void theSameSeedGivesTheSameLand() {
        PlanetTerrain a = defaults(42);
        PlanetTerrain b = defaults(42);
        for (int x = -300; x < 300; x += 7) {
            for (int z = -300; z < 300; z += 11) {
                assertEquals(a.groundY(x, z), b.groundY(x, z), "at " + x + ", " + z);
            }
        }
    }

    @Test
    void anotherSeedGivesOtherLand() {
        PlanetTerrain a = defaults(42);
        PlanetTerrain b = defaults(43);
        int differ = 0;
        for (int x = 0; x < 256; x += 4) {
            for (int z = 0; z < 256; z += 4) {
                if (a.groundY(x, z) != b.groundY(x, z)) {
                    differ++;
                }
            }
        }
        assertTrue(differ > 1_000, differ + " of 4,096 columns differ");
    }

    @Test
    void theGroundStaysWithinTheAmplitudeAndUsesMostOfIt() {
        PlanetTerrain land = new PlanetTerrain(7, 100, 20, 64);
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (int x = -2_000; x < 2_000; x += 3) {
            for (int z = -2_000; z < 2_000; z += 3) {
                int y = land.groundY(x, z);
                lowest = Math.min(lowest, y);
                highest = Math.max(highest, y);
            }
        }
        assertTrue(lowest >= 80 && highest <= 120, "the ground spans " + lowest + " to " + highest);
        assertTrue(lowest <= 90 && highest >= 110, "the ground spans only " + lowest + " to " + highest);
    }

    @Test
    void noAmplitudeIsFlat() {
        PlanetTerrain land = new PlanetTerrain(7, -12, 0, 64);
        for (int x = -100; x < 100; x += 9) {
            for (int z = -100; z < 100; z += 13) {
                assertEquals(-12, land.groundY(x, z));
            }
        }
    }

    /** A player walks over it: on the defaults, no column is more than a block above its neighbours. */
    @Test
    void theDefaultLandIsWalkable() {
        for (long seed = 0; seed < 4; seed++) {
            PlanetTerrain land = defaults(seed);
            for (int x = -256; x < 256; x++) {
                for (int z = -256; z < 256; z++) {
                    int y = land.groundY(x, z);
                    assertTrue(
                            Math.abs(y - land.groundY(x + 1, z)) <= 1 && Math.abs(y - land.groundY(x, z + 1)) <= 1,
                            "a step of more than a block at " + x + ", " + z + " on seed " + seed);
                }
            }
        }
    }

    @Test
    void aLargerScaleIsSmoother() {
        assertTrue(meanStep(new PlanetTerrain(3, 64, 16, 256)) < meanStep(new PlanetTerrain(3, 64, 16, 32)));
    }

    @Test
    void outOfRangeDialsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new PlanetTerrain(0, 64, -1, 128));
        assertThrows(IllegalArgumentException.class, () -> new PlanetTerrain(0, 64, 8, 0));
    }

    @Test
    void aColumnIsSurfaceThenFillerThenBase() {
        // Ground at 70, three filler blocks, sea level 63: dry land.
        assertEquals(Layer.AIR, PlanetTerrain.layer(71, 70, 3, 63));
        assertEquals(Layer.SURFACE, PlanetTerrain.layer(70, 70, 3, 63));
        assertEquals(Layer.FILLER, PlanetTerrain.layer(69, 70, 3, 63));
        assertEquals(Layer.FILLER, PlanetTerrain.layer(67, 70, 3, 63));
        assertEquals(Layer.BASE, PlanetTerrain.layer(66, 70, 3, 63));
        assertEquals(Layer.BASE, PlanetTerrain.layer(-64, 70, 3, 63));
    }

    @Test
    void noFillerPutsTheBaseUnderTheSurface() {
        assertEquals(Layer.SURFACE, PlanetTerrain.layer(70, 70, 0, 63));
        assertEquals(Layer.BASE, PlanetTerrain.layer(69, 70, 0, 63));
    }

    @Test
    void theSeaFillsUpToJustBelowTheSeaLevel() {
        // Ground at 55 under a sea level of 63: sea from 56 to 62.
        assertEquals(Layer.SURFACE, PlanetTerrain.layer(55, 55, 3, 63));
        assertEquals(Layer.SEA, PlanetTerrain.layer(56, 55, 3, 63));
        assertEquals(Layer.SEA, PlanetTerrain.layer(62, 55, 3, 63));
        assertEquals(Layer.AIR, PlanetTerrain.layer(63, 55, 3, 63));
        // Ground at the sea level stays dry.
        assertEquals(Layer.AIR, PlanetTerrain.layer(64, 63, 3, 63));
    }

    private static double meanStep(PlanetTerrain land) {
        long total = 0;
        int count = 0;
        for (int x = 0; x < 512; x++) {
            for (int z = 0; z < 512; z += 8) {
                total += Math.abs(land.groundY(x, z) - land.groundY(x + 1, z));
                count++;
            }
        }
        return (double) total / count;
    }
}
