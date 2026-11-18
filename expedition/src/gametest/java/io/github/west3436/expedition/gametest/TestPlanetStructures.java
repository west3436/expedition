package io.github.west3436.expedition.gametest;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.planet.StructureFilter;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

/**
 * The structure lists the GameTests run with: the Nether denies bastions. They are set before the
 * server builds its levels, which is when a planet's lists apply.
 */
@EventBusSubscriber(modid = ExpeditionMod.MOD_ID)
public final class TestPlanetStructures {
    /** The Nether's lists. */
    public static final StructureFilter.Lists NETHER =
            new StructureFilter.Lists(List.of(), List.of("minecraft:bastion_remnant"));

    private TestPlanetStructures() {}

    /**
     * Sets the lists.
     *
     * @param event the event
     */
    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        StructureFilter.setSource(dimension -> dimension.equals(Level.NETHER) ? Optional.of(NETHER) : Optional.empty());
    }
}
