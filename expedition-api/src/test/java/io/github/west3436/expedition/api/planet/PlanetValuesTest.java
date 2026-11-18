package io.github.west3436.expedition.api.planet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

/** The planet value types' own checks, without the game. */
class PlanetValuesTest {
    private static final ResourceLocation IRON = ResourceLocation.withDefaultNamespace("iron_ore");

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }

    static Planet planet(String path, SpaceSquare space) {
        return new Planet(
                id(path),
                ResourceKey.create(Registries.DIMENSION, id(path)),
                space,
                PlanetSurface.DEFAULT,
                Optional.empty(),
                Planet.DEFAULT_GRAVITY,
                List.of(),
                Planet.DEFAULT_ORE_DENSITY,
                PlanetMobs.DEFAULT,
                PlanetStructures.DEFAULT,
                PlanetAccess.DEFAULT);
    }

    @Test
    void aSquareRunsFromHalfItsSizeBelowTheCentre() {
        SpaceSquare square = SpaceSquare.of(100, -50, 16);
        assertEquals(92, square.minX());
        assertEquals(108, square.maxX());
        assertEquals(-58, square.minZ());
        assertEquals(-42, square.maxZ());
        assertTrue(square.contains(92, -58));
        assertTrue(square.contains(107, -43));
        assertFalse(square.contains(108, -50));
        assertFalse(square.contains(100, -59));
    }

    @Test
    void anOddSquareReachesFurtherOnThePositiveSide() {
        SpaceSquare square = SpaceSquare.of(0, 0, 17);
        assertEquals(-8, square.minX());
        assertEquals(9, square.maxX());
    }

    @Test
    void squaresThatTouchDontOverlap() {
        SpaceSquare a = SpaceSquare.of(0, 0, 16);
        assertFalse(a.overlaps(SpaceSquare.of(16, 0, 16)), "side by side along X");
        assertFalse(a.overlaps(SpaceSquare.of(0, -16, 16)), "side by side along Z");
        assertFalse(a.overlaps(SpaceSquare.of(16, 16, 16)), "corner to corner");
        assertTrue(a.overlaps(SpaceSquare.of(15, 15, 16)), "one column shared");
        assertTrue(a.overlaps(SpaceSquare.of(0, 0, 1024)), "one inside the other");
        assertTrue(SpaceSquare.of(0, 0, 1024).overlaps(a), "the other way round");
    }

    @Test
    void aSquareHasLimits() {
        assertThrows(IllegalArgumentException.class, () -> SpaceSquare.of(0, 0, SpaceSquare.MIN_SIZE - 1));
        assertThrows(IllegalArgumentException.class, () -> SpaceSquare.of(0, 0, SpaceSquare.MAX_SIZE + 1));
        assertThrows(IllegalArgumentException.class, () -> SpaceSquare.of(SpaceSquare.LIMIT, 0, 16));
        assertThrows(IllegalArgumentException.class, () -> SpaceSquare.of(0, -SpaceSquare.LIMIT, 16));
        SpaceSquare edge = SpaceSquare.of(SpaceSquare.LIMIT - 8, -SpaceSquare.LIMIT + 8, 16);
        assertEquals(SpaceSquare.LIMIT, edge.maxX());
        assertEquals(-SpaceSquare.LIMIT, edge.minZ());
    }

    @Test
    void anAtmosphereKeepsItsOrderAndCantBeChanged() {
        Map<ResourceLocation, Double> pressures = new LinkedHashMap<>();
        pressures.put(id("methane"), 6.0);
        pressures.put(id("nitrogen"), 2.5);
        PlanetAtmosphere air = new PlanetAtmosphere(pressures, 210, 150, false);
        pressures.put(id("oxygen"), 20.0);
        assertEquals(
                List.of(id("methane"), id("nitrogen")),
                new ArrayList<>(air.partialPressures().keySet()));
        assertEquals(8.5, air.totalPressure());
        assertEquals(0.0, air.partialPressure(id("oxygen")));
        assertThrows(
                UnsupportedOperationException.class,
                () -> air.partialPressures().clear());
    }

    @Test
    void anAtmosphereIsAirOrAVacuum() {
        assertEquals(0.0, new PlanetAtmosphere(Map.of(), 3, 3, true).totalPressure());
        assertThrows(IllegalArgumentException.class, () -> new PlanetAtmosphere(Map.of(id("n"), 1.0), 3, 3, true));
        assertThrows(IllegalArgumentException.class, () -> new PlanetAtmosphere(Map.of(), 3, 3, false));
        assertThrows(IllegalArgumentException.class, () -> new PlanetAtmosphere(Map.of(id("n"), 0.0), 3, 3, false));
        assertThrows(IllegalArgumentException.class, () -> new PlanetAtmosphere(Map.of(id("n"), -1.0), 3, 3, false));
        assertThrows(
                IllegalArgumentException.class, () -> new PlanetAtmosphere(Map.of(id("n"), Double.NaN), 3, 3, false));
        assertThrows(IllegalArgumentException.class, () -> new PlanetAtmosphere(Map.of(id("n"), 1.0), 0, 3, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlanetAtmosphere(Map.of(id("n"), 1.0), 3, Double.POSITIVE_INFINITY, false));
    }

    @Test
    void oresAndMobsCheckTheirNumbers() {
        assertTrue(new PlanetOre(IRON, 1, 8, PlanetOre.MIN_Y, PlanetOre.MAX_Y).anyHeight());
        assertFalse(new PlanetOre(IRON, 1, 8, 0, 0).anyHeight());
        assertThrows(IllegalArgumentException.class, () -> new PlanetOre(IRON, 0, 8, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PlanetOre(IRON, 1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PlanetOre(IRON, 1, PlanetOre.MAX_VEIN_SIZE + 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PlanetOre(IRON, 1, 8, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new PlanetOre(IRON, 1, 8, PlanetOre.MIN_Y - 1, 0));

        ResourceLocation stray = ResourceLocation.withDefaultNamespace("stray");
        new PlanetMob(stray, 1, 1, PlanetMob.MAX_GROUP);
        assertThrows(IllegalArgumentException.class, () -> new PlanetMob(stray, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new PlanetMob(stray, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new PlanetMob(stray, 1, 3, 2));
        assertThrows(IllegalArgumentException.class, () -> new PlanetMob(stray, 1, 1, PlanetMob.MAX_GROUP + 1));
    }

    @Test
    void mobListsAreCopied() {
        List<PlanetMob> monsters = new ArrayList<>();
        monsters.add(new PlanetMob(ResourceLocation.withDefaultNamespace("stray"), 10, 1, 3));
        PlanetMobs mobs = new PlanetMobs(true, Map.of(MobCategory.MONSTER, monsters));
        monsters.clear();
        assertEquals(1, mobs.spawns(MobCategory.MONSTER).size());
        assertTrue(mobs.spawns(MobCategory.CREATURE).isEmpty());
    }

    @Test
    void aStructureRefIsWrittenAsInAFile() {
        assertEquals(
                "#minecraft:village",
                new StructureRef(ResourceLocation.withDefaultNamespace("village"), true).toString());
        assertEquals(
                "minecraft:igloo", new StructureRef(ResourceLocation.withDefaultNamespace("igloo"), false).toString());
    }

    @Test
    void aPlanetChecksItsValues() {
        SpaceSquare space = SpaceSquare.of(0, 0, SpaceSquare.DEFAULT_SIZE);
        Planet planet = planet("kepler", space);
        assertEquals(1.0, planet.gravity());
        assertThrows(
                IllegalArgumentException.class,
                () -> new Planet(
                        planet.id(),
                        blockKeyAsDimension(planet.id()),
                        space,
                        PlanetSurface.DEFAULT,
                        Optional.empty(),
                        1,
                        List.of(),
                        1,
                        PlanetMobs.DEFAULT,
                        PlanetStructures.DEFAULT,
                        PlanetAccess.DEFAULT));
        assertThrows(IllegalArgumentException.class, () -> withGravity(planet, -0.1));
        assertThrows(IllegalArgumentException.class, () -> withGravity(planet, Planet.MAX_GRAVITY + 0.1));
        assertThrows(IllegalArgumentException.class, () -> withGravity(planet, Double.NaN));
        PlanetOre ore = new PlanetOre(IRON, 1, 8, 0, 0);
        assertThrows(
                IllegalArgumentException.class,
                () -> new Planet(
                        planet.id(),
                        planet.dimension(),
                        space,
                        PlanetSurface.DEFAULT,
                        Optional.empty(),
                        1,
                        List.of(ore, ore),
                        1,
                        PlanetMobs.DEFAULT,
                        PlanetStructures.DEFAULT,
                        PlanetAccess.DEFAULT));
    }

    @SuppressWarnings("unchecked")
    private static ResourceKey<Level> blockKeyAsDimension(ResourceLocation id) {
        return (ResourceKey<Level>) (ResourceKey<?>) ResourceKey.create(Registries.BLOCK, id);
    }

    private static Planet withGravity(Planet p, double gravity) {
        return new Planet(
                p.id(),
                p.dimension(),
                p.space(),
                p.surface(),
                p.atmosphere(),
                gravity,
                p.ores(),
                p.oreDensity(),
                p.mobs(),
                p.structures(),
                p.access());
    }
}
