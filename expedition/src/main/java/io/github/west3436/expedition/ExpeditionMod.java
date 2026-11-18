package io.github.west3436.expedition;

import com.mojang.logging.LogUtils;
import io.github.west3436.expedition.api.ExpeditionApi;
import io.github.west3436.expedition.command.MoveCommand;
import io.github.west3436.expedition.command.PlanetCommand;
import io.github.west3436.expedition.mover.ShipMover;
import io.github.west3436.expedition.planet.PlanetBorders;
import io.github.west3436.expedition.planet.PlanetEvents;
import io.github.west3436.expedition.planet.PlanetRegistry;
import io.github.west3436.expedition.planet.PlanetsPayload;
import io.github.west3436.expedition.portal.PortalRules;
import io.github.west3436.expedition.ship.BlockWeights;
import io.github.west3436.expedition.transit.Transits;
import io.github.west3436.expedition.worldgen.ExpeditionWorldgen;
import io.github.west3436.zas.api.ZasApi;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Expedition: shuttles, a Space dimension and datapack-defined planets, built on ZAS³'s
 * atmospherics. ZAS³ is its one required mod.
 */
@Mod(ExpeditionMod.MOD_ID)
public final class ExpeditionMod {
    /** The mod id, the same as the API's. */
    public static final String MOD_ID = ExpeditionApi.MOD_ID;

    private static final Logger LOGGER = LogUtils.getLogger();

    public ExpeditionMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, ExpeditionConfig.SPEC);
        modBus.addListener(ExpeditionMod::onCommonSetup);
        ExpeditionWorldgen.register(modBus);
        ShipMover.setCeiling(ExpeditionConfig.CEILING);
        PortalRules.setSettings(ExpeditionConfig.PORTALS);
        Transits.setLongestSide(() -> ExpeditionConfig.value(ExpeditionConfig.MAX_LONGEST_SIDE));
        Transits.register(NeoForge.EVENT_BUS);
        ShipMover.register(NeoForge.EVENT_BUS);
        MoveCommand.register(NeoForge.EVENT_BUS);
        PlanetRegistry.install();
        PlanetEvents.register(NeoForge.EVENT_BUS);
        PlanetBorders.register(modBus, NeoForge.EVENT_BUS);
        PlanetCommand.register(NeoForge.EVENT_BUS);
        modBus.addListener(PlanetsPayload::register);
        BlockWeights.register(modBus);
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        // ZAS³ is required, so this says true unless something stopped it starting.
        LOGGER.info("Expedition is loading; ZAS³ present: {}", ZasApi.isPresent());
    }
}
