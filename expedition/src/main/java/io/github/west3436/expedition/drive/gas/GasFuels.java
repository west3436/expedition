package io.github.west3436.expedition.drive.gas;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import io.github.west3436.zas.api.gas.GasTags;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.slf4j.Logger;

/**
 * Which gases burn in a gas thruster, and how well. Which: the gases in ZAS³'s {@code #zas:flammable}
 * tag are fuels, and those in {@code #zas:oxidizer} oxidisers, so a pack's own gas becomes a fuel by
 * joining the tag, with no code. How well: the thruster fuel files ({@link GasFuel}), loaded on every
 * {@code /reload}; a flammable gas with no file burns as {@link GasFuel#standard}. A file for a gas
 * that isn't flammable is kept but unused until the gas joins the tag.
 */
public final class GasFuels {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** The directory of the thruster fuel files, under {@code data/<namespace>/}. */
    public static final String DIRECTORY = "expedition/thruster_fuel";

    private static volatile Map<ResourceLocation, GasFuel> files = Map.of();

    /** The gases as gas thrusters see them. */
    public static final Propellant.Gases GASES = Propellant.Gases.of(GasFuels::fuel, GasFuels::oxidiser);

    private GasFuels() {}

    /**
     * Registers the fuel files' reload listener.
     *
     * @param gameBus the game's event bus
     */
    public static void register(IEventBus gameBus) {
        gameBus.addListener(AddReloadListenerEvent.class, event -> event.addListener(new Listener()));
    }

    /**
     * A gas's fuel.
     *
     * @param gas the gas
     * @return its file's fuel, or {@link GasFuel#standard} if it has none; empty if the gas isn't in
     *     {@code #zas:flammable}
     */
    public static Optional<GasFuel> fuel(ResourceLocation gas) {
        if (!GasTags.contains(GasTags.FLAMMABLE, gas)) {
            return Optional.empty();
        }
        GasFuel file = files.get(gas);
        return Optional.of(file != null ? file : GasFuel.standard(gas));
    }

    /**
     * Whether a gas is an oxidiser.
     *
     * @param gas the gas
     * @return true if it is in {@code #zas:oxidizer}
     */
    public static boolean oxidiser(ResourceLocation gas) {
        return GasTags.contains(GasTags.OXIDIZER, gas);
    }

    /**
     * The fuels the files give, whatever their tags.
     *
     * @return the fuels, by gas
     */
    public static Map<ResourceLocation, GasFuel> files() {
        return files;
    }

    /** Reads the thruster fuel files. A file that doesn't parse is logged and skipped. */
    private static final class Listener extends SimpleJsonResourceReloadListener {
        Listener() {
            super(new Gson(), DIRECTORY);
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> json, ResourceManager resources, ProfilerFiller p) {
            Map<ResourceLocation, GasFuel> loaded = new HashMap<>();
            json.forEach((file, element) -> GasFuel.CODEC
                    .parse(JsonOps.INSTANCE, element)
                    .resultOrPartial(error -> LOGGER.error("Thruster fuel file {} is invalid: {}", file, error))
                    .ifPresent(fuel -> {
                        if (loaded.put(fuel.gas(), fuel) != null) {
                            LOGGER.warn(
                                    "Thruster fuel file {} tunes {} again; it replaces the one before",
                                    file,
                                    fuel.gas());
                        }
                    }));
            files = Map.copyOf(loaded);
            LOGGER.info("Loaded {} thruster fuel file(s)", loaded.size());
        }
    }
}
