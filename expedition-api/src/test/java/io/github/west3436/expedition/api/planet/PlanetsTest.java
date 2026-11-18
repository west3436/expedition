package io.github.west3436.expedition.api.planet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.LogicalSide;
import org.junit.jupiter.api.Test;

/**
 * The planet queries: none before Expedition installs its lookup, then its answers, one side apart
 * from the other. The lookup can be installed once per JVM, so this is one test.
 */
class PlanetsTest {
    @Test
    void theQueriesAnswerFromTheInstalledLookup() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("test", "kepler");
        assertTrue(Planets.all(LogicalSide.SERVER).isEmpty(), "planets before any lookup");
        assertTrue(Planets.byId(id, LogicalSide.SERVER).isEmpty());

        Planet kepler = PlanetValuesTest.planet("kepler", SpaceSquare.of(12000, -4000, 4096));
        Planet moon = PlanetValuesTest.planet("moon", SpaceSquare.of(20000, 0, 2048));
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

        assertThrows(IllegalStateException.class, () -> Planets.install(side -> List.of()));
    }
}
