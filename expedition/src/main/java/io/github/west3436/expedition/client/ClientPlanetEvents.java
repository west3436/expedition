package io.github.west3436.expedition.client;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.planet.PlanetRegistry;
import java.util.List;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * Forgets the server's planets when the client leaves it, so the next server's, or none, take their
 * place.
 */
@EventBusSubscriber(modid = ExpeditionMod.MOD_ID, value = Dist.CLIENT)
public final class ClientPlanetEvents {
    private ClientPlanetEvents() {}

    /**
     * Clears the client's planets.
     *
     * @param event the event
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        PlanetRegistry.setClient(List.of());
    }
}
