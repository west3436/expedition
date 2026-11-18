package io.github.west3436.expedition.drive.gas;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.drive.Drive;
import io.github.west3436.expedition.drive.FuelSystem;
import io.github.west3436.zas.api.atmosphere.AtmosphereSample;
import io.github.west3436.zas.api.atmosphere.Atmospheres;
import io.github.west3436.zas.api.capability.IGasHandler;
import io.github.west3436.zas.api.gas.GasParcel;
import io.github.west3436.zas.api.mutation.GasMutations;
import io.github.west3436.zas.api.mutation.HeatMutations;
import io.github.west3436.zas.api.mutation.SourceId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * What gas thrusters do as a ship's drives: their numbers, and the {@link FuelSystem} of their fuel,
 * {@value #FUEL}.
 *
 * <ul>
 *   <li><b>Thrust.</b> A thruster adds its thrust whichever way it faces, provided the space behind
 *       its nozzle, the block it faces, is open: air, or anything a placed block would replace.
 *   <li><b>Fuel.</b> It draws on every block beside it, but not the one at its nozzle, that offers
 *       ZAS³'s gas-handler capability ({@link IGasHandler#BLOCK}): a tank, a pipe, or anything of
 *       another mod's. The ship's tanks are pooled, each counted once however many thrusters touch
 *       it, and measured by {@link Propellant}: fuels from ZAS³'s gas tags, how well each burns from
 *       the thruster fuel files ({@link GasFuels}).
 *   <li><b>Oxidiser.</b> Each fuel burns with its share of oxidiser from the tanks, unless every
 *       firing thruster's nozzle opens onto air whose oxidisers' partial pressure is at least the
 *       server's setting: then the thrusters breathe it, and the tanks' oxidiser stays.
 *   <li><b>Exhaust.</b> At departure the burn leaves the tanks all at once, and its exhaust goes out
 *       behind the nozzles through ZAS³'s API: the burned gases at the exhaust temperature, and a
 *       share of the heat. Air-breathing thrusters take their oxidiser from the air there. Launching
 *       inside a sealed hangar fills it.
 * </ul>
 */
public final class GasThrusters {
    /** The fuel gas thrusters burn, in units ({@link GasFuel#units()}). */
    public static final String FUEL = "gas";

    /** Who adds the exhaust, for ZAS³. */
    public static final SourceId SOURCE = SourceId.of(ExpeditionMod.MOD_ID, "gas_thruster");

    /**
     * A gas thruster's numbers, from the server's settings.
     *
     * @param thrust its thrust
     * @param efficiency momentum per unit of fuel
     * @param topSpeed its top speed, in blocks per second
     * @param airBreathingKpa the partial pressure of oxidisers, in kPa, at which it breathes air
     * @param exhaustTemperature the exhaust's temperature, in kelvin
     * @param exhaustHeatShare the share of the burn's heat left behind the nozzle, 0 to 1
     */
    public record Settings(
            double thrust,
            double efficiency,
            double topSpeed,
            double airBreathingKpa,
            double exhaustTemperature,
            double exhaustHeatShare) {
        /** The defaults: strong, wasteful and slow. */
        public static final Settings DEFAULT = new Settings(400, 50, 20, 15, 900, 0.1);
    }

    private static Supplier<Settings> settings = () -> Settings.DEFAULT;

    private GasThrusters() {}

    /**
     * Sets where the numbers come from, read at every use.
     *
     * @param source the settings
     */
    public static void setSettings(Supplier<Settings> source) {
        settings = Objects.requireNonNull(source, "source");
    }

    /**
     * The numbers now.
     *
     * @return the settings
     */
    public static Settings settings() {
        return settings.get();
    }

    /** Registers the fuel system. */
    public static void register() {
        FuelSystem.Registry.register(FUEL, new GasFuelSystem());
    }

    /**
     * A gas thruster's drive, from the settings.
     *
     * @return the drive
     */
    public static Drive drive() {
        Settings s = settings();
        return new Drive(FUEL, s.thrust(), s.efficiency(), s.topSpeed());
    }

    /**
     * The way a thruster's nozzle points.
     *
     * @param state the thruster
     * @return its facing
     */
    static Direction facing(BlockState state) {
        return state.hasProperty(DirectionalBlock.FACING) ? state.getValue(DirectionalBlock.FACING) : Direction.DOWN;
    }

    /**
     * Whether the space behind a thruster's nozzle is open: air, or anything a placed block would
     * replace.
     *
     * @param level the level
     * @param pos the thruster
     * @return true if it can fire
     */
    public static boolean nozzleOpen(ServerLevel level, BlockPos pos) {
        BlockPos out = pos.relative(facing(level.getBlockState(pos)));
        if (!level.isLoaded(out)) {
            return false;
        }
        BlockState state = level.getBlockState(out);
        return state.isAir() || state.canBeReplaced();
    }

    /** The middle of the space behind a thruster's nozzle. */
    private static Vec3 nozzle(ServerLevel level, BlockPos pos) {
        return pos.relative(facing(level.getBlockState(pos))).getCenter();
    }

    /**
     * Whether a thruster breathes the air behind its nozzle: whether the partial pressure of the
     * oxidisers there is at least the server's setting.
     *
     * @param level the level
     * @param pos the thruster
     * @return true if it needs no oxidiser from the tanks
     */
    public static boolean breathesAir(ServerLevel level, BlockPos pos) {
        return oxidiserKpa(Atmospheres.at(level, nozzle(level, pos)))
                >= settings().airBreathingKpa();
    }

    private static double oxidiserKpa(AtmosphereSample sample) {
        double kpa = 0;
        for (Map.Entry<ResourceLocation, Double> e : sample.partialPressures().entrySet()) {
            if (GasFuels.oxidiser(e.getKey())) {
                kpa += e.getValue();
            }
        }
        return kpa;
    }

    /** The gas handlers beside some thrusters, each once, in a fixed order. */
    static Map<BlockPos, IGasHandler> tanks(ServerLevel level, List<BlockPos> thrusters) {
        Map<BlockPos, IGasHandler> tanks = new LinkedHashMap<>();
        for (BlockPos pos : thrusters) {
            Direction nozzle = facing(level.getBlockState(pos));
            for (Direction side : Direction.values()) {
                BlockPos next = pos.relative(side);
                if (side == nozzle
                        || tanks.containsKey(next)
                        || !level.isLoaded(next)
                        || level.getBlockEntity(next) instanceof GasThrusterBlockEntity) {
                    continue;
                }
                IGasHandler handler = level.getCapability(IGasHandler.BLOCK, next, side.getOpposite());
                if (handler != null) {
                    tanks.put(next.immutable(), handler);
                }
            }
        }
        return tanks;
    }

    private static boolean allBreatheAir(ServerLevel level, List<BlockPos> thrusters) {
        for (BlockPos pos : thrusters) {
            if (!breathesAir(level, pos)) {
                return false;
            }
        }
        return !thrusters.isEmpty();
    }

    private static List<Map<ResourceLocation, Double>> contents(Map<BlockPos, IGasHandler> tanks) {
        List<Map<ResourceLocation, Double>> contents = new ArrayList<>(tanks.size());
        for (IGasHandler handler : tanks.values()) {
            contents.add(handler.contents().moles());
        }
        return contents;
    }

    /** The fuel system of {@value #FUEL}. */
    private static final class GasFuelSystem implements FuelSystem {
        @Override
        public double measure(ServerLevel level, List<BlockPos> drives) {
            Map<BlockPos, IGasHandler> tanks = tanks(level, drives);
            return Propellant.measure(contents(tanks), GasFuels.GASES, allBreatheAir(level, drives))
                    .usable();
        }

        @Override
        public void burn(ServerLevel level, List<BlockPos> drives, double amount) {
            Map<BlockPos, IGasHandler> tanks = tanks(level, drives);
            boolean air = allBreatheAir(level, drives);
            List<Map<ResourceLocation, Double>> plan = Propellant.drain(contents(tanks), GasFuels.GASES, air, amount);
            Map<ResourceLocation, Double> drained = new HashMap<>();
            int i = 0;
            for (IGasHandler handler : tanks.values()) {
                Map<ResourceLocation, Double> take = plan.get(i++);
                if (!take.isEmpty()) {
                    GasParcel given = handler.drain(take, IGasHandler.Action.EXECUTE);
                    given.moles().forEach((gas, moles) -> drained.merge(gas, moles, Double::sum));
                }
            }
            exhaust(level, drives, drained, air);
        }
    }

    /** Lets a burn's exhaust out behind the nozzles, split evenly between them. */
    private static void exhaust(
            ServerLevel level, List<BlockPos> drives, Map<ResourceLocation, Double> drained, boolean air) {
        if (drives.isEmpty() || drained.isEmpty()) {
            return;
        }
        Settings s = settings();
        int n = drives.size();
        Map<ResourceLocation, Double> exhaust = Propellant.exhaust(drained, GasFuels.GASES);
        double heat = Propellant.heat(drained, GasFuels.GASES) * Math.clamp(s.exhaustHeatShare(), 0, 1);
        double fromAir = air ? Propellant.oxidiserFor(drained, GasFuels.GASES) : 0;
        for (BlockPos pos : drives) {
            Vec3 point = nozzle(level, pos);
            if (fromAir > 0) {
                Map<ResourceLocation, Double> take = airOxidiser(Atmospheres.at(level, point), fromAir / n);
                if (!take.isEmpty()) {
                    GasMutations.remove(level, point, take, SOURCE);
                }
            }
            if (!exhaust.isEmpty()) {
                Map<ResourceLocation, Double> share = new HashMap<>();
                exhaust.forEach((gas, moles) -> share.put(gas, moles / n));
                GasMutations.add(level, point, new GasParcel(share, s.exhaustTemperature()), SOURCE);
            }
            if (heat > 0) {
                HeatMutations.add(level, point, heat / n, SOURCE);
            }
        }
    }

    /** Some moles of oxidiser from air, split between its oxidisers by their partial pressures. */
    private static Map<ResourceLocation, Double> airOxidiser(AtmosphereSample sample, double moles) {
        double total = oxidiserKpa(sample);
        Map<ResourceLocation, Double> take = new HashMap<>();
        if (total > 0) {
            sample.partialPressures().forEach((gas, kpa) -> {
                if (GasFuels.oxidiser(gas) && kpa > 0) {
                    take.put(gas, moles * kpa / total);
                }
            });
        }
        return take;
    }
}
