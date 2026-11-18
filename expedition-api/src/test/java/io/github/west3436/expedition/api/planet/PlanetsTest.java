package io.github.west3436.expedition.api.planet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.west3436.expedition.api.space.Space;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.fml.LogicalSide;
import org.junit.jupiter.api.Test;

/**
 * The planet queries: none before Expedition installs its lookup, then its answers, one side apart
 * from the other, and the gravity they give. The lookup can be installed once per JVM, so this is one test.
 */
class PlanetsTest {
    @Test
    void theQueriesAnswerFromTheInstalledLookup() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("test", "kepler");
        assertTrue(Planets.all(LogicalSide.SERVER).isEmpty(), "planets before any lookup");
        assertTrue(Planets.byId(id, LogicalSide.SERVER).isEmpty());
        ResourceKey<Level> overworld =
                ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));
        assertEquals(
                PlanetGravity.NORMAL, PlanetGravity.of(overworld, LogicalSide.SERVER), "gravity before any lookup");

        Planet kepler = PlanetValuesTest.planet("kepler", SpaceSquare.of(12000, -4000, 4096));
        Planet base = PlanetValuesTest.planet("moon", SpaceSquare.of(20000, 0, 2048));
        Planet moon = new Planet(
                base.id(),
                base.dimension(),
                base.space(),
                base.surface(),
                base.atmosphere(),
                0.16,
                base.ores(),
                base.oreDensity(),
                base.mobs(),
                base.structures(),
                base.access());
        Planets.install(side -> side == LogicalSide.SERVER ? List.of(kepler, moon) : List.of(moon));

        assertEquals(Optional.of(kepler), Planets.forDimension(kepler.dimension(), LogicalSide.SERVER));
        assertEquals(Optional.of(kepler), Planets.byId(id, LogicalSide.SERVER));
        assertEquals(List.of(kepler, moon), Planets.all(LogicalSide.SERVER));
        assertTrue(Planets.forDimension(kepler.dimension(), LogicalSide.CLIENT).isEmpty(), "the client's");
        assertEquals(Optional.of(moon), Planets.forDimension(moon.dimension(), LogicalSide.CLIENT));
        assertTrue(Planets.forDimension(
                        ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld")),
                        LogicalSide.SERVER)
                .isEmpty());

        assertEquals(0.16, PlanetGravity.of(moon.dimension(), LogicalSide.SERVER), "the moon's gravity");
        assertEquals(0.16, PlanetGravity.of(moon.dimension(), LogicalSide.CLIENT), "the moon's gravity, client");
        assertEquals(
                PlanetGravity.NORMAL,
                PlanetGravity.of(kepler.dimension(), LogicalSide.CLIENT),
                "off the client's planets");
        assertEquals(PlanetGravity.NORMAL, PlanetGravity.of(overworld, LogicalSide.SERVER), "the Overworld's gravity");
        assertEquals(Space.GRAVITY, PlanetGravity.of(Space.DIMENSION, LogicalSide.SERVER), "Space's gravity");
        assertEquals(Space.GRAVITY, PlanetGravity.of(Space.DIMENSION, LogicalSide.CLIENT), "Space's gravity, client");

        // The planet under a column of Space: kepler's square runs from 9952 to 14048 along X.
        assertEquals(Optional.of(kepler), Space.planetAt(12000, -4000, LogicalSide.SERVER));
        assertEquals(Optional.of(kepler), Space.planetAt(9952, -6048, LogicalSide.SERVER), "the first column");
        assertEquals(Optional.empty(), Space.planetAt(14048, -4000, LogicalSide.SERVER), "the first column past it");
        assertEquals(Optional.empty(), Space.planetAt(12000, -4000, LogicalSide.CLIENT), "off the client's planets");
        assertEquals(Optional.of(moon), Space.planetAt(20000, 1023, LogicalSide.CLIENT), "the moon, client");
        assertEquals(Optional.empty(), Space.planetAt(0, 0, LogicalSide.SERVER), "between the squares");

        assertThrows(IllegalStateException.class, () -> Planets.install(side -> List.of()));
    }
}
