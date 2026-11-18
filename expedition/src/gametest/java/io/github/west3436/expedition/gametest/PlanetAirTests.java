package io.github.west3436.expedition.gametest;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.api.planet.Planet;
import io.github.west3436.expedition.api.planet.PlanetAtmosphere;
import io.github.west3436.expedition.api.planet.Planets;
import io.github.west3436.expedition.planet.PlanetAir;
import io.github.west3436.expedition.planet.PlanetRegistry;
import io.github.west3436.zas.api.atmosphere.AtmosphereSample;
import io.github.west3436.zas.api.atmosphere.AtmosphereState;
import io.github.west3436.zas.api.atmosphere.Atmospheres;
import io.github.west3436.zas.api.environment.AmbientDefinition;
import io.github.west3436.zas.api.environment.AmbientProviders;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.fml.LogicalSide;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A planet's atmosphere as its dimension's open air in ZAS³ (#15). The test planet {@code
 * expedition_test:kepler} declares 6.0 kPa of methane and 2.5 kPa of nitrogen, at 210 K by day and
 * 150 K by night. These tests read that back through ZAS³'s own API, and check that the providers
 * follow the planets when they change.
 *
 * <p>They use ZAS³'s simulation API only, never its content, so they pass with {@code
 * -Pcontent=off}.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PlanetAirTests {
    /** A column of kepler's flat ground, far above it: open air. */
    private static final BlockPos OPEN_AIR = new BlockPos(8, 120, 8);

    private static final long NOON = 6000;
    private static final long MIDNIGHT = 18000;

    private static final ResourceLocation METHANE = ResourceLocation.fromNamespaceAndPath("zas", "methane");
    private static final ResourceLocation NITROGEN = ResourceLocation.fromNamespaceAndPath("zas", "nitrogen");

    private PlanetAirTests() {}

    /**
     * ZAS³ reads kepler's open air as the planet file declares it: the mix and pressure always, 210
     * K at noon and 150 K at midnight. The level's day time is the Overworld's, as for every level
     * but the Overworld, so the test sets it there and puts it back after.
     */
    @GameTest(template = "empty", batch = "planet_air", timeoutTicks = 1200)
    public static void keplers_open_air_is_the_planets(GameTestHelper h) {
        ServerLevel overworld = h.getLevel().getServer().overworld();
        ServerLevel kepler = h.getLevel().getServer().getLevel(TestDimensions.KEPLER);
        h.assertTrue(kepler != null, "the test dimension isn't a level");
        long before = overworld.getDayTime();
        h.startSequence()
                .thenExecute(() -> {
                    h.assertTrue(PlanetAir.appliesTo(TestDimensions.KEPLER), "kepler's air isn't the planet's");
                    h.assertFalse(PlanetAir.appliesTo(Level.OVERWORLD), "the Overworld's air is a planet's");
                    kepler.setChunkForced(0, 0, true);
                    overworld.setDayTime(NOON);
                })
                .thenWaitUntil(() -> assertAir(h, kepler, 210))
                .thenExecute(() -> overworld.setDayTime(MIDNIGHT))
                .thenWaitUntil(() -> assertAir(h, kepler, 150))
                .thenExecute(() -> {
                    overworld.setDayTime(before);
                    kepler.setChunkForced(0, 0, false);
                })
                .thenSucceed();
    }

    /**
     * The providers follow the planets from load to load: a dimension that gains an atmosphere gets
     * a provider, one whose atmosphere changes answers with the new one at once, and one that loses
     * it loses its provider. Driven through {@link PlanetAir#update}, which every load calls, and put
     * back as the server loaded it after.
     */
    @GameTest(template = "empty", batch = "planet_air_reload")
    public static void the_providers_follow_the_planets(GameTestHelper h) {
        ServerLevel kepler = h.getLevel().getServer().getLevel(TestDimensions.KEPLER);
        Planet loaded = Planets.forDimension(TestDimensions.KEPLER, LogicalSide.SERVER)
                .orElseThrow(() -> new IllegalStateException("no test planet"));
        ResourceKey<Level> nowhere = ResourceKey.create(
                Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(TestDimensions.NAMESPACE, "nowhere"));
        Planet airless = withAir(
                loaded,
                nowhere,
                Optional.of(new PlanetAtmosphere(Map.of(), 40, 40, true)),
                ResourceLocation.fromNamespaceAndPath(TestDimensions.NAMESPACE, "nowhere"));
        Planet thicker = withAir(
                loaded,
                loaded.dimension(),
                Optional.of(new PlanetAtmosphere(Map.of(NITROGEN, 50.0), 300, 300, false)),
                loaded.id());
        Planet none = withAir(loaded, loaded.dimension(), Optional.empty(), loaded.id());
        try {
            // A planet with an atmosphere gains a provider; kepler's changes and answers anew.
            PlanetAir.update(List.of(thicker, airless), Set.of());
            h.assertTrue(PlanetAir.appliesTo(nowhere), "the new planet has no provider");
            AmbientDefinition now = provider(h, TestDimensions.KEPLER).ambient(kepler);
            h.assertValueEqual(now, new AmbientDefinition(Map.of(NITROGEN, 50.0), 300), "kepler's changed air");

            // Planets that lose their atmosphere, or go, lose their providers.
            PlanetAir.update(List.of(none), Set.of());
            h.assertTrue(AmbientProviders.registered(nowhere).isEmpty(), "the gone planet's provider stayed");
            h.assertFalse(
                    AmbientProviders.registered(TestDimensions.KEPLER).stream()
                            .anyMatch(r -> r.id().equals(PlanetAir.providerId(TestDimensions.KEPLER))),
                    "kepler's provider stayed without an atmosphere");
            h.assertFalse(PlanetAir.appliesTo(TestDimensions.KEPLER), "kepler's air is still the planet's");

            // A ZAS³ ambient file for the dimension wins: ZAS³ ships one for the Overworld.
            Planet overworld = withAir(loaded, Level.OVERWORLD, thicker.atmosphere(), loaded.id());
            h.assertValueEqual(
                    PlanetAir.zasFiles(
                            List.of(overworld, loaded), h.getLevel().getServer().getResourceManager()),
                    Set.of(Level.OVERWORLD),
                    "the dimensions with a ZAS³ ambient file");
            PlanetAir.update(List.of(overworld), Set.of(Level.OVERWORLD));
            h.assertTrue(provider(h, Level.OVERWORLD) != null, "the Overworld's planet has no provider to be beaten");
            h.assertFalse(PlanetAir.appliesTo(Level.OVERWORLD), "a planet's air beat a ZAS³ ambient file");
        } finally {
            PlanetAir.update(
                    PlanetRegistry.server().planets().values(),
                    PlanetAir.zasFiles(
                            PlanetRegistry.server().planets().values(),
                            h.getLevel().getServer().getResourceManager()));
        }
        h.assertTrue(
                AmbientProviders.registered(Level.OVERWORLD).isEmpty()
                        || AmbientProviders.registered(Level.OVERWORLD).stream()
                                .noneMatch(r -> r.id().equals(PlanetAir.providerId(Level.OVERWORLD))),
                "the Overworld kept a planet's provider");
        h.assertTrue(PlanetAir.appliesTo(TestDimensions.KEPLER), "kepler's provider wasn't put back");
        h.succeed();
    }

    /** The open air over kepler: ambient, the declared mix, and the temperature. */
    private static void assertAir(GameTestHelper h, ServerLevel kepler, double temperature) {
        AtmosphereSample air = Atmospheres.at(kepler, OPEN_AIR);
        h.assertValueEqual(air.state(), AtmosphereState.AMBIENT, "the state of the open air");
        Map<ResourceLocation, Double> pressures = air.partialPressures();
        h.assertTrue(
                pressures.keySet().equals(Set.of(METHANE, NITROGEN))
                        && Math.abs(pressures.get(METHANE) - 6.0) < 1e-3
                        && Math.abs(pressures.get(NITROGEN) - 2.5) < 1e-3,
                "the open air's mix is " + pressures);
        h.assertTrue(Math.abs(air.pressure() - 8.5) < 1e-3, "the open air's pressure is " + air.pressure());
        h.assertTrue(
                Math.abs(air.temperature() - temperature) < 0.5,
                "the open air is " + air.temperature() + " K, not " + temperature + " K");
    }

    private static io.github.west3436.zas.api.environment.AmbientProvider provider(
            GameTestHelper h, ResourceKey<Level> dimension) {
        return AmbientProviders.registered(dimension).stream()
                .filter(r -> r.id().equals(PlanetAir.providerId(dimension)))
                .findFirst()
                .map(AmbientProviders.Registration::provider)
                .orElseThrow(() -> new IllegalStateException("no planet provider for " + dimension.location()));
    }

    private static Planet withAir(
            Planet p, ResourceKey<Level> dimension, Optional<PlanetAtmosphere> air, ResourceLocation id) {
        return new Planet(
                id,
                dimension,
                p.space(),
                p.surface(),
                air,
                p.gravity(),
                p.ores(),
                p.oreDensity(),
                p.mobs(),
                p.structures(),
                p.access());
    }
}
