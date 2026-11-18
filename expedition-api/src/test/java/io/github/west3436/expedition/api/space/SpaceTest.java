package io.github.west3436.expedition.api.space;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.west3436.expedition.api.planet.Planet;
import io.github.west3436.expedition.api.planet.PlanetAccess;
import io.github.west3436.expedition.api.planet.PlanetMobs;
import io.github.west3436.expedition.api.planet.PlanetStructures;
import io.github.west3436.expedition.api.planet.PlanetSurface;
import io.github.west3436.expedition.api.planet.SpaceSquare;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** Space's id, its horizontal distance, and the 1:1 mapping between a square and its surface. */
class SpaceTest {
    @Test
    void spaceIsExpeditionsSpaceDimension() {
        assertEquals(ResourceLocation.fromNamespaceAndPath("expedition", "space"), Space.DIMENSION.location());
        assertTrue(Space.is(Space.DIMENSION));
        assertFalse(
                Space.is(ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"))));
        assertEquals(0.0, Space.GRAVITY);
    }

    @Test
    void distanceIsHorizontal() {
        assertEquals(5.0, Space.distance(0, 0, 3, 4));
        assertEquals(30_000.0, Space.distance(0, 0, 30_000, 0));
        assertEquals(Space.distance(-7, 2, 5, -9), Space.distance(5, -9, -7, 2));
    }

    @Test
    void aSquareMapsOneToOneOntoItsSurface() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("test", "kepler");
        Planet kepler = new Planet(
                id,
                ResourceKey.create(Registries.DIMENSION, id),
                SpaceSquare.of(12000, -4000, 4096),
                new PlanetSurface(100, -30),
                Optional.empty(),
                Planet.DEFAULT_GRAVITY,
                List.of(),
                Planet.DEFAULT_ORE_DENSITY,
                PlanetMobs.DEFAULT,
                PlanetStructures.DEFAULT,
                PlanetAccess.DEFAULT);
        assertEquals(new BlockPos(100, 70, -30), Space.toSurface(kepler, new BlockPos(12000, 70, -4000)));
        assertEquals(new BlockPos(90, 5, -25), Space.toSurface(kepler, new BlockPos(11990, 5, -3995)));
        BlockPos inSpace = new BlockPos(13_000, -20, -5_500);
        assertEquals(inSpace, Space.fromSurface(kepler, Space.toSurface(kepler, inSpace)));
        assertEquals(new BlockPos(12000, 64, -4000), Space.fromSurface(kepler, new BlockPos(100, 64, -30)));
    }
}
