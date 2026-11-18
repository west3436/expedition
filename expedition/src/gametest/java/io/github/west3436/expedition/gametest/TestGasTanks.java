package io.github.west3436.expedition.gametest;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.zas.api.capability.IGasHandler;
import io.github.west3436.zas.api.gas.GasMixture;
import io.github.west3436.zas.api.gas.GasParcel;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Gas tanks for the GameTests, which work with ZAS³'s content mod on or off: in the GameTest run
 * only, a lodestone offers ZAS³'s gas-handler capability on every face, backed by a plain map of
 * moles kept here by position. A test fills one with {@link #fill}, reads it with {@link #moles}, and
 * empties it with {@link #clear}.
 */
@EventBusSubscriber(modid = ExpeditionMod.MOD_ID)
public final class TestGasTanks {
    /** The block that is a test tank. */
    public static final Block TANK = Blocks.LODESTONE;

    private static final double VOLUME = 70;
    private static final double TEMPERATURE = 293.15;

    private static final Map<BlockPos, Map<ResourceLocation, Double>> TANKS = new HashMap<>();

    private TestGasTanks() {}

    /**
     * Gives the tank block the capability.
     *
     * @param event the event
     */
    @SubscribeEvent
    public static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlock(IGasHandler.BLOCK, (level, pos, state, entity, side) -> new Handler(pos.immutable()), TANK);
    }

    /**
     * Sets what a tank holds.
     *
     * @param pos the tank's absolute position
     * @param moles its moles, by gas
     */
    public static void fill(BlockPos pos, Map<ResourceLocation, Double> moles) {
        TANKS.put(pos.immutable(), new HashMap<>(moles));
    }

    /**
     * What a tank holds of a gas.
     *
     * @param pos the tank's absolute position
     * @param gas the gas
     * @return its moles
     */
    public static double moles(BlockPos pos, ResourceLocation gas) {
        return TANKS.getOrDefault(pos, Map.of()).getOrDefault(gas, 0.0);
    }

    /**
     * Forgets a tank.
     *
     * @param pos the tank's absolute position
     */
    public static void clear(BlockPos pos) {
        TANKS.remove(pos);
    }

    private record Handler(BlockPos pos) implements IGasHandler {
        private Map<ResourceLocation, Double> tank() {
            return TANKS.computeIfAbsent(pos, p -> new HashMap<>());
        }

        @Override
        public GasMixture contents() {
            return new GasMixture(tank(), TEMPERATURE, VOLUME);
        }

        @Override
        public GasParcel fill(GasParcel gas, Action action) {
            if (action == Action.EXECUTE) {
                gas.moles().forEach((id, moles) -> tank().merge(id, moles, Double::sum));
            }
            return gas;
        }

        @Override
        public GasParcel drain(Map<ResourceLocation, Double> moles, Action action) {
            Map<ResourceLocation, Double> given = new HashMap<>();
            Map<ResourceLocation, Double> tank = tank();
            moles.forEach((id, wanted) -> {
                double give = Math.min(wanted, tank.getOrDefault(id, 0.0));
                if (give > 0) {
                    given.put(id, give);
                    if (action == Action.EXECUTE) {
                        tank.put(id, tank.get(id) - give);
                    }
                }
            });
            return given.isEmpty() ? GasParcel.EMPTY : new GasParcel(given, TEMPERATURE);
        }
    }
}
