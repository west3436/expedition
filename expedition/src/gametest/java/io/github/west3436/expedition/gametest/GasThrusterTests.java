package io.github.west3436.expedition.gametest;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.drive.DriveRefusal;
import io.github.west3436.expedition.drive.Plot;
import io.github.west3436.expedition.drive.Trip;
import io.github.west3436.expedition.drive.gas.GasFuel;
import io.github.west3436.expedition.drive.gas.GasFuels;
import io.github.west3436.expedition.drive.gas.GasThrusters;
import io.github.west3436.expedition.registry.ExpeditionBlocks;
import io.github.west3436.expedition.ship.ShipBox;
import io.github.west3436.expedition.ship.ShipComputers;
import io.github.west3436.expedition.ship.ShipStatus;
import io.github.west3436.expedition.ship.ShuttleComputerBlockEntity;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The gas thruster (#28): counted in a ship's thrust with fuel and an open nozzle, not counted with a
 * blocked one, and its fuel leaving the tank at departure. The tanks are {@link TestGasTanks}, so the
 * tests run with ZAS³'s content mod on or off.
 *
 * <p>Each ship is a Shuttle Computer facing north, a thruster behind it (south) whose nozzle points
 * south, and a tank to the pilot's left (east) of the thruster.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GasThrusterTests {
    private static final double EPSILON = 1e-6;

    private static final ResourceLocation H2 = ResourceLocation.fromNamespaceAndPath("zas", "hydrogen");
    private static final ResourceLocation O2 = ResourceLocation.fromNamespaceAndPath("zas", "oxygen");

    private static final BlockPos COMPUTER = new BlockPos(1, 2, 1);
    private static final BlockPos THRUSTER = COMPUTER.south();
    private static final BlockPos NOZZLE = THRUSTER.south();
    private static final BlockPos TANK = THRUSTER.east();

    private GasThrusterTests() {}

    /** A thruster with fuel and an open nozzle adds its thrust and its fuel to the ship. */
    @GameTest(template = "empty")
    public static void a_fuelled_thruster_counts(GameTestHelper h) {
        ShuttleComputerBlockEntity computer = ship(h, Map.of(H2, 100.0, O2, 50.0));
        ShipStatus status = ShipComputers.status(h.getLevel(), computer);
        check(status.drives() == 1, "the ship has " + status.drives() + " drives, not 1");
        near(GasThrusters.drive().thrust(), status.thrust(), "the ship's thrust");
        // A mole of hydrogen is a unit, with oxygen enough to burn it all, breathing air or not.
        near(100, status.fuel().getOrDefault(GasThrusters.FUEL, 0.0), "the ship's gas");
        check(status.ratio() > 1, "a thrust-to-weight of " + status.ratio() + " can't lift off");
        h.succeed();
    }

    /** A thruster whose nozzle is blocked adds nothing, and its tanks don't count. */
    @GameTest(template = "empty")
    public static void a_blocked_thruster_does_not_count(GameTestHelper h) {
        ShuttleComputerBlockEntity computer = ship(h, Map.of(H2, 100.0, O2, 50.0));
        h.setBlock(NOZZLE, Blocks.STONE);
        ShipStatus status = ShipComputers.status(h.getLevel(), computer);
        check(status.drives() == 0, "the ship has " + status.drives() + " drives, not 0");
        near(0, status.thrust(), "the ship's thrust");
        check(!status.fuel().containsKey(GasThrusters.FUEL), "the blocked thruster's gas counts");
        Plot plot = ShipComputers.plot(h.getLevel(), computer, Trip.of(1_000, 1, 0));
        check(plot.refusal().equals(Optional.of(DriveRefusal.NO_DRIVE)), "refused for " + plot.refusal());
        // Open again, it counts again.
        h.setBlock(NOZZLE, Blocks.AIR);
        check(ShipComputers.status(h.getLevel(), computer).drives() == 1, "the reopened thruster doesn't count");
        h.succeed();
    }

    /** At departure the plotted fuel leaves the tank, all at once. */
    @GameTest(template = "empty")
    public static void fuel_leaves_the_tank_at_departure(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ShuttleComputerBlockEntity computer = ship(h, Map.of(H2, 100.0, O2, 50.0));
        Plot plot = ShipComputers.plot(level, computer, Trip.of(1_000, 1, 0));
        check(plot.feasible(), "the plot is refused for " + plot.refusal());
        double burn = plot.fuel(GasThrusters.FUEL);
        check(burn > 0 && burn < 100, "the trip burns " + burn);

        ShipComputers.burn(level, computer, plot);
        BlockPos tank = h.absolutePos(TANK);
        near(100 - burn, TestGasTanks.moles(tank, H2), "the hydrogen left");
        double oxygen = TestGasTanks.moles(tank, O2);
        boolean air = GasThrusters.breathesAir(level, h.absolutePos(THRUSTER));
        // Breathing air, the tank's oxygen stays; otherwise half a mole goes with each of hydrogen.
        near(air ? 50 : 50 - burn / 2, oxygen, "the oxygen left");
        near(100 - burn, ShipComputers.status(level, computer).fuel().get(GasThrusters.FUEL), "the gas left");
        TestGasTanks.clear(tank);
        h.succeed();
    }

    /** A thruster with an empty tank can't fly: the plot is refused as no_fuel. */
    @GameTest(template = "empty")
    public static void an_empty_tank_is_refused(GameTestHelper h) {
        ShuttleComputerBlockEntity computer = ship(h, Map.of(O2, 50.0));
        Plot plot = ShipComputers.plot(h.getLevel(), computer, Trip.of(1_000, 1, 0));
        check(plot.refusal().equals(Optional.of(DriveRefusal.NO_FUEL)), "refused for " + plot.refusal());
        TestGasTanks.clear(h.absolutePos(TANK));
        h.succeed();
    }

    /** The fuel files load, and a flammable gas with no file burns as standard. */
    @GameTest(template = "empty")
    public static void fuels_come_from_tags_and_files(GameTestHelper h) {
        Optional<GasFuel> hydrogen = GasFuels.fuel(H2);
        check(hydrogen.isPresent() && hydrogen.get().oxidiser() == 0.5, "hydrogen burns as " + hydrogen);
        Optional<GasFuel> methane = GasFuels.fuel(ResourceLocation.fromNamespaceAndPath("zas", "methane"));
        check(methane.isPresent() && methane.get().units() > 3, "methane burns as " + methane);
        check(GasFuels.fuel(O2).isEmpty(), "oxygen is a fuel");
        check(GasFuels.oxidiser(O2), "oxygen isn't an oxidiser");
        h.succeed();
    }

    /** Builds the ship, fills its tank, and sets its box round all three blocks. */
    private static ShuttleComputerBlockEntity ship(GameTestHelper h, Map<ResourceLocation, Double> gas) {
        h.setBlock(
                COMPUTER,
                ExpeditionBlocks.SHUTTLE_COMPUTER
                        .get()
                        .defaultBlockState()
                        .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
        h.setBlock(
                THRUSTER,
                ExpeditionBlocks.GAS_THRUSTER
                        .get()
                        .defaultBlockState()
                        .setValue(DirectionalBlock.FACING, Direction.SOUTH));
        h.setBlock(TANK, TestGasTanks.TANK);
        TestGasTanks.fill(h.absolutePos(TANK), gas);
        if (!(h.getBlockEntity(COMPUTER) instanceof ShuttleComputerBlockEntity computer)) {
            throw new GameTestAssertException("no computer at " + COMPUTER);
        }
        // Back one (south) to the thruster, left one (east) to the tank.
        ShipComputers.setBox(computer, new ShipBox(0, 1, 1, 0, 0, 0));
        return computer;
    }

    private static void near(double expected, double actual, String what) {
        check(Math.abs(expected - actual) < EPSILON, what + " is " + actual + ", not " + expected);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }
}
