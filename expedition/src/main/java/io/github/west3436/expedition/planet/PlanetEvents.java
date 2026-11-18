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
 *   <li>Once the server has started, bounded planets get their world borders ({@link
 *       PlanetBorders}).
 *   <li>Once the server has started, and after every {@code /reload}, the planets are checked for
 *       what only a finished load knows. A planet naming a gas ZAS³ doesn't define fails, with an
 *       error ({@link PlanetLoader#checkGases}); a dimension, structure or tag that isn't there is
 *       a warning ({@link PlanetLoader#checkWorld}).
 *   <li>Each planet's atmosphere is its dimension's open air in ZAS³ ({@link PlanetAir}), from
 *       every load on.
 *   <li>A joining player is sent every planet, and every player is sent them again after a {@code
 *       /reload}.
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
    }

    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new PlanetReloadListener());
    }

    private static void onServerStarted(ServerStartedEvent event) {
        finishLoad(event.getServer(), true);
    }

    private static void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() == null) {
            // A /reload: the planets are new.
            finishLoad(event.getPlayerList().getServer(), false);
        }
        PlanetsPayload payload =
                new PlanetsPayload(List.copyOf(PlanetRegistry.server().planets().values()));
        event.getRelevantPlayers().forEach(player -> PacketDistributor.sendToPlayer(player, payload));
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        PlanetRegistry.setServer(PlanetLoader.Result.EMPTY);
        PlanetAir.clear();
        StructureFilter.clearApplied();
    }

    /**
     * The checks a load can only make once it has finished. A gas ZAS³ doesn't define fails its
     * planet, which leaves the server's planets and loses its open air; the rest are warnings. As
     * the server starts, every level exists by now, so the borders go on here: after the planets
     * are final, before anyone joins, and before the check that counts region files past them.
     */
    private static void finishLoad(MinecraftServer server, boolean starting) {
        PlanetLoader.Result loaded = PlanetRegistry.server();
        PlanetLoader.Result checked = PlanetLoader.checkGases(loaded);
        if (checked != loaded) {
            PlanetRegistry.setServer(checked);
            PlanetAir.update(checked.planets().values(), PlanetAir.zasFiles());
            for (PlanetProblem problem : checked.problems()
                    .subList(loaded.problems().size(), checked.problems().size())) {
                LOGGER.error("Planet file problem: {}", problem);
            }
            LOGGER.error(
                    "{} planet(s) didn't load because they name gases ZAS³ doesn't define; {} planet(s) loaded",
                    loaded.planets().size() - checked.planets().size(),
                    checked.planets().size());
        }
        if (starting) {
            PlanetBorders.apply(server);
        }
        for (PlanetProblem problem : PlanetLoader.checkWorld(checked, server)) {
            LOGGER.warn("Planet file problem: {}", problem);
        }
    }
}
