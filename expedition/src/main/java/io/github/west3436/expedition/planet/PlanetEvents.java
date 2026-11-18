package io.github.west3436.expedition.planet;

import com.mojang.logging.LogUtils;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

/**
 * The server's side of planets: loading them with the datapacks, checking them against the running
 * world, and sending them to clients.
 *
 * <ul>
 *   <li>The planets load with every datapack load: as the server starts, and on {@code /reload}.
 *   <li>Once the server has started, and after every {@code /reload}, the planets are checked for
 *       what only a running server knows ({@link PlanetLoader#checkWorld}), and any warning is
 *       logged.
 *   <li>A joining player is sent every planet, and every player is sent them again after a {@code
 *       /reload}.
 *   <li>A planet's gravity is put on its living entities ({@link PlanetGravityEvents}), and put on
 *       every entity again after a {@code /reload}.
 * </ul>
 */
public final class PlanetEvents {
    private static final Logger LOGGER = LogUtils.getLogger();

    private PlanetEvents() {}

    /**
     * Listens on the game bus.
     *
     * @param gameBus NeoForge's game event bus
     */
    public static void register(IEventBus gameBus) {
        gameBus.addListener(PlanetEvents::onAddReloadListeners);
        gameBus.addListener(PlanetEvents::onServerStarted);
        gameBus.addListener(PlanetEvents::onDatapackSync);
        gameBus.addListener(PlanetEvents::onServerStopped);
        PlanetGravityEvents.register(gameBus);
    }

    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new PlanetReloadListener());
    }

    private static void onServerStarted(ServerStartedEvent event) {
        checkWorld(event.getServer());
    }

    private static void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() == null) {
            // A /reload: the planets are new.
            checkWorld(event.getPlayerList().getServer());
            PlanetGravityEvents.refresh(event.getPlayerList().getServer());
        }
        PlanetsPayload payload =
                new PlanetsPayload(List.copyOf(PlanetRegistry.server().planets().values()));
        event.getRelevantPlayers().forEach(player -> PacketDistributor.sendToPlayer(player, payload));
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        PlanetRegistry.setServer(PlanetLoader.Result.EMPTY);
        StructureFilter.clearApplied();
    }

    private static void checkWorld(MinecraftServer server) {
        for (PlanetProblem problem : PlanetLoader.checkWorld(PlanetRegistry.server(), server)) {
            LOGGER.warn("Planet file problem: {}", problem);
        }
    }
}
