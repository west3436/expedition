package io.github.west3436.expedition.gametest;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.api.planet.Planet;
import io.github.west3436.expedition.api.planet.Planets;
import io.github.west3436.expedition.planet.PlanetBorders;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.LogicalSide;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

/**
 * Bounds one test planet, {@code expedition_test:bounded}, and no other: the other GameTests run
 * millions of blocks out and move ships between dimensions, so every other planet is excluded, as
 * an operator would with {@code exclude}. The config itself stays at its default, off. Set before
 * the server builds its levels, so the bounded planet's first chunk already has its cutoff.
 */
@EventBusSubscriber(modid = ExpeditionMod.MOD_ID)
public final class TestPlanetBorders {
    /** The bounded test planet's dimension. */
    public static final ResourceKey<Level> BOUNDED = ResourceKey.create(
            Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(TestDimensions.NAMESPACE, "bounded"));

    /** Blocks of terrain past its border: small, so the cutoff is close to it. */
    public static final int VOID_MARGIN = 32;

    private TestPlanetBorders() {}

    /**
     * Sets the settings.
     *
     * @param event the event
     */
    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        PlanetBorders.setSettings(() -> {
            Set<ResourceLocation> others = Planets.all(LogicalSide.SERVER).stream()
                    .map(Planet::dimension)
                    .filter(dimension -> !dimension.equals(BOUNDED))
                    .map(ResourceKey::location)
                    .collect(Collectors.toSet());
            return new PlanetBorders.Settings(true, VOID_MARGIN, others);
        });
    }
}
