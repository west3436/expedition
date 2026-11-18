package io.github.west3436.expedition.planet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.west3436.expedition.api.planet.PlanetSurface;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** A bounded planet's square, cutoff and region count, without the game. */
class PlanetBoundsTest {
    /** Off the chunk grid: the square runs from x 744 to 1256 and z -248 to 264. */
    private static final PlanetBounds OFF_GRID = PlanetBounds.of(new PlanetSurface(1000, 8), 512, 32);

    @Test
    void theSquareIsTheSpaceSquareOnTheSurface() {
        assertEquals(744, OFF_GRID.minX());
        assertEquals(1256, OFF_GRID.maxX());
        assertEquals(-248, OFF_GRID.minZ());
        assertEquals(264, OFF_GRID.maxZ());
        assertEquals(512, OFF_GRID.size());
        assertEquals(1000.0, OFF_GRID.centerX());
        assertEquals(8.0, OFF_GRID.centerZ());
    }

    @Test
    void anOddSquareReachesFurtherOnThePositiveSide() {
        PlanetBounds odd = PlanetBounds.of(new PlanetSurface(0, 0), 17, 0);
        assertEquals(-8, odd.minX());
        assertEquals(9, odd.maxX());
        // The border is centred on the middle of the square, so it covers it exactly.
        assertEquals(0.5, odd.centerX());
    }

    @Test
    void chunksStraddlingTheCutoffKeepTheirTerrain() {
        // The cutoff is at x 744 - 32 = 712 below and 1256 + 32 = 1288 above.
        assertFalse(OFF_GRID.isVoid(44, 0), "chunk 44 (704-719) reaches inside the cutoff");
        assertTrue(OFF_GRID.isVoid(43, 0), "chunk 43 (688-703) is wholly past it");
        assertFalse(OFF_GRID.isVoid(80, 0), "chunk 80 (1280-1295) starts inside the cutoff");
        assertTrue(OFF_GRID.isVoid(81, 0), "chunk 81 (1296-1311) is wholly past it");
        assertTrue(OFF_GRID.isVoid(1000, 0));
    }

    @Test
    void negativeCoordinatesAndZFollowTheSameRule() {
        // Z cutoff: -248 - 32 = -280 below, 264 + 32 = 296 above.
        assertFalse(OFF_GRID.isVoid(50, -18), "chunk z -18 (-288 to -273) reaches inside");
        assertTrue(OFF_GRID.isVoid(50, -19), "chunk z -19 (-304 to -289) is wholly past");
        assertFalse(OFF_GRID.isVoid(50, 18), "chunk z 18 (288-303) starts inside");
        assertTrue(OFF_GRID.isVoid(50, 19), "chunk z 19 (304-319) is wholly past");
        PlanetBounds negative = PlanetBounds.of(new PlanetSurface(-5000, -5000), 1024, 0);
        // The square runs from -5512 up to -4488.
        assertFalse(negative.isVoid(-345, -300), "chunk -345 (-5520 to -5505) reaches inside");
        assertTrue(negative.isVoid(-346, -300), "chunk -346 (-5536 to -5521) is wholly past");
        assertFalse(negative.isVoid(-281, -300), "chunk -281 (-4496 to -4481) starts inside");
        assertTrue(negative.isVoid(-280, -300), "chunk -280 (-4480 to -4465) is wholly past");
    }

    @Test
    void aChunkOnTheGridEdgeIsVoidExactlyAtTheCutoff() {
        PlanetBounds grid = PlanetBounds.of(new PlanetSurface(0, 0), 1024, 0);
        assertFalse(grid.isVoid(31, 0), "chunk 31 ends at 511, inside");
        assertTrue(grid.isVoid(32, 0), "chunk 32 starts at 512, the first block past");
        assertFalse(grid.isVoid(-32, 0), "chunk -32 starts at -512, inside");
        assertTrue(grid.isVoid(-33, 0), "chunk -33 ends at -513, past");
    }

    @Test
    void aMarginOfMinusOneKeepsTerrainEverywhere() {
        PlanetBounds everywhere = PlanetBounds.of(new PlanetSurface(0, 0), 16, -1);
        assertFalse(everywhere.isVoid(100_000, -100_000));
        assertFalse(everywhere.isRegionVoid(1000, 1000));
    }

    @Test
    void regionFilesPastTheCutoffAreCounted() {
        // Regions are 512 blocks; the cutoff runs from x 712 to 1288 and z -280 to 296.
        assertFalse(OFF_GRID.isRegionVoid(1, 0), "region 1 (512-1023) reaches inside");
        assertFalse(OFF_GRID.isRegionVoid(2, 0), "region 2 (1024-1535) reaches inside");
        assertTrue(OFF_GRID.isRegionVoid(3, 0), "region 3 (1536-2047) is wholly past");
        assertTrue(OFF_GRID.isRegionVoid(0, 0), "region 0 (0-511) is wholly past, below 712");
        assertFalse(OFF_GRID.isRegionVoid(1, -1), "region z -1 (-512 to -1) reaches inside");
        assertTrue(OFF_GRID.isRegionVoid(1, -2), "region z -2 is wholly past");
        long count = OFF_GRID.voidRegions(
                List.of("r.1.0.mca", "r.2.0.mca", "r.3.0.mca", "r.0.0.mca", "r.1.-2.mca", "level.dat", "r.x.0.mca"));
        assertEquals(3, count);
    }

    @Test
    void settingsBoundWhatIsntExcluded() {
        ResourceKey<net.minecraft.world.level.Level> overworld =
                ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));
        ResourceKey<net.minecraft.world.level.Level> kepler =
                ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("mypack", "kepler"));
        PlanetBorders.Settings on = new PlanetBorders.Settings(true, 200, Set.of(overworld.location()));
        assertTrue(on.bounds(kepler));
        assertFalse(on.bounds(overworld));
        assertFalse(PlanetBorders.Settings.OFF.bounds(kepler), "off by default");
    }
}
