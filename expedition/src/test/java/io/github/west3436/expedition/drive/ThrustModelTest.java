package io.github.west3436.expedition.drive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The thrust and mass model, without the game. */
class ThrustModelTest {
    private static final double G = ThrustModel.STANDARD_GRAVITY;
    private static final double EPSILON = 1e-9;

    /** A gas thruster's kind of numbers: strong, wasteful and slow. */
    private static Drive gas(double thrust) {
        return new Drive("gas", thrust, 50, 20);
    }

    /** A drive with the opposite numbers: weak, frugal and fast. */
    private static Drive ion(double thrust) {
        return new Drive("ion", thrust, 2_000, 200);
    }

    private static ShipDrives ship(double mass, List<Drive> drives, double gas) {
        return new ShipDrives(mass, drives, Map.of("gas", gas, "ion", 1e12));
    }

    @Test
    void liftsOffOnlyAboveAThrustToWeightOfOne() {
        // 300 thrust against 200 mass: 1.5 on the Overworld, 0.75 on a planet of gravity 2.
        ShipDrives ship = ship(200, List.of(gas(300)), 1e9);
        Plot overworld = ThrustModel.plot(ship, Trip.of(1_000, 1, 0));
        assertTrue(overworld.feasible(), "refused: " + overworld.refusal());
        assertEquals(1.5, overworld.liftOffRatio(), EPSILON);

        Plot heavy = ThrustModel.plot(ship, Trip.of(1_000, 2, 0));
        assertEquals(Optional.of(DriveRefusal.THRUST_TOO_LOW), heavy.refusal());
        assertEquals(0.75, heavy.liftOffRatio(), EPSILON);

        Plot moon = ThrustModel.plot(ship, Trip.of(1_000, 0.2, 0));
        assertTrue(moon.feasible());
        assertEquals(7.5, moon.liftOffRatio(), EPSILON);

        // Exactly 1 isn't above 1.
        Plot balanced = ThrustModel.plot(ship(300, List.of(gas(300)), 1e9), Trip.of(1_000, 1, 0));
        assertEquals(Optional.of(DriveRefusal.THRUST_TOO_LOW), balanced.refusal());
    }

    @Test
    void landsOnlyAboveAThrustToWeightOfOne() {
        ShipDrives ship = ship(200, List.of(gas(300)), 1e9);
        assertTrue(ThrustModel.plot(ship, Trip.of(1_000, 0, 1)).feasible());
        Plot heavy = ThrustModel.plot(ship, Trip.of(1_000, 0, 1.6));
        assertEquals(Optional.of(DriveRefusal.THRUST_TOO_LOW_TO_LAND), heavy.refusal());
        assertEquals(300 / (200 * 1.6), heavy.landingRatio(), EPSILON);
        // Taking off is asked first.
        assertEquals(
                Optional.of(DriveRefusal.THRUST_TOO_LOW),
                ThrustModel.plot(ship, Trip.of(1_000, 2, 2)).refusal());
    }

    @Test
    void anyThrustLeavesSpace() {
        Plot plot = ThrustModel.plot(ship(1_000_000, List.of(ion(1)), 0), Trip.of(10, 0, 0));
        assertTrue(plot.feasible(), "refused: " + plot.refusal());
        assertEquals(Double.POSITIVE_INFINITY, plot.liftOffRatio());
        assertEquals(Double.POSITIVE_INFINITY, plot.landingRatio());
    }

    @Test
    void aLongTripReachesTopSpeedAndCoasts() {
        // a = 9.81 * 400 / 100 = 39.24; top speed 20, so sqrt(a * d) is far above it.
        ShipDrives ship = ship(100, List.of(gas(400)), 1e9);
        Plot plot = ThrustModel.plot(ship, Trip.of(10_000, 0, 0));
        double a = G * 400 / 100;
        assertEquals(a, plot.acceleration(), EPSILON);
        assertEquals(20, plot.cruiseSpeed(), EPSILON);
        assertEquals(10_000 / 20.0 + 20 / a, plot.time(), EPSILON);
        assertEquals(2 * 100 * 20 / 50.0, plot.fuel("gas"), EPSILON);
    }

    @Test
    void aShortTripBrakesBeforeTopSpeed() {
        // a = 9.81 * 10 / 100 = 0.981, d = 100: sqrt(a * d) = 9.9 is under the top speed of 20.
        ShipDrives ship = ship(100, List.of(gas(10)), 1e9);
        Plot plot = ThrustModel.plot(ship, Trip.of(100, 0, 0));
        double a = G * 10 / 100;
        double v = Math.sqrt(a * 100);
        assertTrue(v < 20);
        assertEquals(v, plot.cruiseSpeed(), EPSILON);
        // No coasting: half the way accelerating, half braking, 2 * sqrt(d / a).
        assertEquals(2 * Math.sqrt(100 / a), plot.time(), EPSILON);
        assertEquals(2 * 100 * v / 50, plot.fuel("gas"), EPSILON);
    }

    @Test
    void aSlowerCruiseTakesLongerAndBurnsLess() {
        ShipDrives ship = ship(100, List.of(gas(400)), 1e9);
        Plot fast = ThrustModel.plot(ship, Trip.of(10_000, 0, 0));
        Plot slow = ThrustModel.plot(ship, Trip.of(10_000, 0, 0).atSpeed(10));
        assertEquals(10, slow.cruiseSpeed(), EPSILON);
        assertEquals(fast.fuel("gas") / 2, slow.fuel("gas"), EPSILON);
        assertTrue(slow.time() > fast.time());
        // Asking for more than the top speed gets the top speed.
        assertEquals(
                20, ThrustModel.plot(ship, Trip.of(10_000, 0, 0).atSpeed(1_000)).cruiseSpeed(), EPSILON);
    }

    @Test
    void drivesShareTheBurnByThrust() {
        // Two fuels at one speed: each burns its share of 2 m v by thrust, over its own efficiency.
        Drive a = new Drive("gas", 300, 50, 20);
        Drive b = new Drive("ion", 100, 2_000, 20);
        Plot plot = ThrustModel.plot(ship(100, List.of(a, b), 1e9), Trip.of(10_000, 0, 0));
        double momentum = 2 * 100 * 20;
        assertEquals(momentum * 0.75 / 50, plot.fuel("gas"), EPSILON);
        assertEquals(momentum * 0.25 / 2_000, plot.fuel("ion"), EPSILON);
        assertEquals(G * 400 / 100, plot.acceleration(), EPSILON);
    }

    @Test
    void aSlowDriveStaysColdAboveItsTopSpeed() {
        ShipDrives ship = ship(100, List.of(gas(1_000), ion(50)), 1e9);
        // Lifting off, every drive counts.
        Plot plot = ThrustModel.plot(ship, Trip.of(1_000_000, 1, 0));
        assertEquals(10.5, plot.liftOffRatio(), EPSILON);
        // Cruising at 200, only the ion drive fires.
        assertEquals(200, plot.cruiseSpeed(), EPSILON);
        assertEquals(G * 50 / 100, plot.acceleration(), EPSILON);
        assertEquals(0, plot.fuel("gas"));
        assertFalse(plot.fuel().containsKey("gas"));
        assertEquals(2 * 100 * 200 / 2_000.0, plot.fuel("ion"), EPSILON);

        // Cruising at the gas drive's speed or under, both fire.
        Plot slow = ThrustModel.plot(ship, Trip.of(1_000_000, 1, 0).atSpeed(15));
        assertEquals(15, slow.cruiseSpeed(), EPSILON);
        assertEquals(G * 1_050 / 100, slow.acceleration(), EPSILON);
        assertTrue(slow.fuel("gas") > 0);
    }

    @Test
    void aShortHopTooShortForTheFastDriveUsesEveryDrive() {
        // The ion drive alone (a = 0.0981) could reach only sqrt(0.0981 * 100) = 3.1, under the gas
        // drive's top speed of 20, so the gas drive fires too.
        ShipDrives ship = ship(1_000, List.of(gas(1_000), ion(10)), 1e9);
        Plot plot = ThrustModel.plot(ship, Trip.of(100, 0, 0));
        double a = G * 1_010 / 1_000;
        assertEquals(a, plot.acceleration(), EPSILON);
        assertEquals(Math.min(20, Math.sqrt(a * 100)), plot.cruiseSpeed(), EPSILON);
    }

    @Test
    void refusesAShipWithNoDrive() {
        Plot plot = ThrustModel.plot(new ShipDrives(100, List.of(), Map.of()), Trip.of(100, 1, 0));
        assertEquals(Optional.of(DriveRefusal.NO_DRIVE), plot.refusal());
        assertEquals(0, plot.liftOffRatio());
        assertEquals(Double.POSITIVE_INFINITY, plot.time());
    }

    @Test
    void refusesATripLongerThanAllowed() {
        ShipDrives ship = ship(100, List.of(gas(400)), 1e9);
        Plot plot = ThrustModel.plot(ship, Trip.of(10_000, 0, 0).within(60));
        assertEquals(Optional.of(DriveRefusal.OUT_OF_RANGE), plot.refusal());
        assertTrue(plot.time() > 60);
        assertTrue(ThrustModel.plot(ship, Trip.of(10_000, 0, 0).within(1_000)).feasible());
    }

    @Test
    void refusesATripTheTanksCantFuel() {
        // 2 * 100 * 20 / 50 = 80 units of gas.
        Trip trip = Trip.of(10_000, 0, 0);
        Plot empty = ThrustModel.plot(ship(100, List.of(gas(400)), 79.9), trip);
        assertEquals(Optional.of(DriveRefusal.NO_FUEL), empty.refusal());
        assertEquals(80, empty.fuel("gas"), EPSILON);
        assertTrue(ThrustModel.plot(ship(100, List.of(gas(400)), 80), trip).feasible());
        // A fuel the ship doesn't carry at all.
        Plot none = ThrustModel.plot(new ShipDrives(100, List.of(gas(400)), Map.of()), trip);
        assertEquals(Optional.of(DriveRefusal.NO_FUEL), none.refusal());
        // Slowing down brings it within the tanks.
        assertTrue(ThrustModel.plot(ship(100, List.of(gas(400)), 40), trip.atSpeed(10))
                .feasible());
    }

    @Test
    void aTripOfNoDistanceCostsNothing() {
        Plot plot = ThrustModel.plot(ship(100, List.of(gas(400)), 0), Trip.of(0, 1, 1));
        assertTrue(plot.feasible(), "refused: " + plot.refusal());
        assertEquals(0, plot.time());
        assertEquals(0, plot.cruiseSpeed());
        assertTrue(plot.fuel().isEmpty());
    }

    @Test
    void aMasslessShipNeedsNoFuel() {
        Plot plot = ThrustModel.plot(new ShipDrives(0, List.of(gas(1)), Map.of()), Trip.of(100, 1, 1));
        assertTrue(plot.feasible(), "refused: " + plot.refusal());
        assertEquals(20, plot.cruiseSpeed(), EPSILON);
        assertEquals(5, plot.time(), EPSILON);
    }

    @Test
    void rejectsNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new Drive("gas", 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Drive("gas", 1, Double.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> new Drive("gas", 1, 1, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new ShipDrives(-1, List.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ShipDrives(1, List.of(), Map.of("gas", -1.0)));
        assertThrows(IllegalArgumentException.class, () -> Trip.of(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> Trip.of(1, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> Trip.of(1, 0, 0).atSpeed(0));
        assertThrows(IllegalArgumentException.class, () -> Trip.of(1, 0, 0).within(0));
    }
}
