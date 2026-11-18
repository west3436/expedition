package io.github.west3436.expedition;

import com.mojang.logging.LogUtils;
import io.github.west3436.expedition.api.ExpeditionApi;
import io.github.west3436.expedition.mover.ShipMover;
import io.github.west3436.zas.api.ZasApi;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
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

    public ExpeditionMod(IEventBus modBus) {
        modBus.addListener(ExpeditionMod::onCommonSetup);
        ShipMover.register(NeoForge.EVENT_BUS);
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        // ZAS³ is required, so this says true unless something stopped it starting.
        LOGGER.info("Expedition is loading; ZAS³ present: {}", ZasApi.isPresent());
    }
}
