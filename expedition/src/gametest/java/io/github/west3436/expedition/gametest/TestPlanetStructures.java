package io.github.west3436.expedition.gametest;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.api.planet.PlanetStructures;
import io.github.west3436.expedition.api.planet.StructureRef;
import io.github.west3436.expedition.planet.StructureFilter;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

/**
 * Structure lists for the Nether, which no planet file in the GameTests covers: it denies bastions,
 * so the lists are tested on vanilla's own noise generator. Every other dimension gets its planet
 * file's lists, as on a real server. They are set before the server builds its levels, which is when
 * lists apply.
 */
@EventBusSubscriber(modid = ExpeditionMod.MOD_ID)
public final class TestPlanetStructures {
    /** The Nether's lists. */
    public static final PlanetStructures NETHER = new PlanetStructures(
            List.of(), List.of(new StructureRef(ResourceLocation.withDefaultNamespace("bastion_remnant"), false)));

    private TestPlanetStructures() {}

    /**
     * Sets the lists.
     *
     * @param event the event
     */
    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        StructureFilter.setSource(dimension ->
                dimension.equals(Level.NETHER) ? Optional.of(NETHER) : StructureFilter.PLANETS.structures(dimension));
    }
}
