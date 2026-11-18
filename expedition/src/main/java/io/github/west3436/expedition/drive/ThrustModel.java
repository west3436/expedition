package io.github.west3436.expedition.drive;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The rules that turn a ship's mass and drives into what it can do. It is plain arithmetic, with no
 * game, and its one answer is a {@link Plot}.
 *
 * <ul>
 *   <li><b>Lift-off and landing.</b> A ship standing on a planet needs thrust-to-weight above 1 to
 *       lift off, and the same at its destination to land. Weight is mass times the planet's gravity,
 *       and every drive counts, whichever way it faces. A ship too heavy for a planet stays in orbit.
 *   <li><b>Time.</b> The ship accelerates to its cruise speed {@code v}, coasts, and brakes: with
 *       acceleration {@code a = STANDARD_GRAVITY * T / m}, a trip of distance {@code d} takes {@code d
 *       / v + v / a}. The cruise speed is at most the pilot's, at most the drives' top speed, and at
 *       most {@code sqrt(a * d)}, past which the ship would start braking before reaching it.
 *   <li><b>Fuel.</b> Fuel burns in proportion to thrust, so a trip burns {@code 2 * m * v /
 *       efficiency}: once to reach {@code v} and once to brake from it. Halving the cruise speed
 *       halves the fuel. Drives that fire together share the burn by their thrust, each from its own
 *       fuel, and all of it leaves the tanks at departure.
 *   <li><b>Mixed drives.</b> A drive fires only up to its own top speed. At a cruise speed above a
 *       slow drive's top speed, that drive stays cold and the faster ones push the ship alone, with
 *       their own thrust and efficiency.
 * </ul>
 *
 * <p>Lift-off costs no more than any other start, however close thrust-to-weight is to 1: the model
 * has no gravity losses.
 */
public final class ThrustModel {
    /**
     * The Overworld's gravity, in blocks per second squared: how fast a thrust equal to its mass
     * pushes a ship in space.
     */
    public static final double STANDARD_GRAVITY = 9.81;

    private ThrustModel() {}

    /**
     * Thrust-to-weight: how many times its own weight a ship's thrust can lift.
     *
     * @param thrust the ship's thrust
     * @param mass its mass
     * @param gravity the gravity, a multiple of the Overworld's
     * @return the ratio: positive infinity where there is no weight, and 0 with no thrust
     */
    public static double ratio(double thrust, double mass, double gravity) {
        double weight = mass * gravity;
        if (weight <= 0) {
            return thrust > 0 ? Double.POSITIVE_INFINITY : 0;
        }
        return thrust / weight;
    }

    /**
     * The thrust of some drives together.
     *
     * @param drives the drives
     * @return the sum of their thrusts
     */
    public static double thrust(Collection<Drive> drives) {
        double sum = 0;
        for (Drive drive : drives) {
            sum += drive.thrust();
        }
        return sum;
    }

    /**
     * Plots a trip: whether the ship can make it, how long it takes and what fuel it burns.
     *
     * @param ship the ship
     * @param trip the trip
     * @return the plot
     */
    public static Plot plot(ShipDrives ship, Trip trip) {
        double mass = ship.mass();
        double thrust = ship.thrust();
        double liftOff = ratio(thrust, mass, trip.fromGravity());
        double landing = ratio(thrust, mass, trip.toGravity());
        if (ship.drives().isEmpty()) {
            return new Plot(
                    Optional.of(DriveRefusal.NO_DRIVE),
                    liftOff,
                    landing,
                    0,
                    0,
                    trip.distance() > 0 ? Double.POSITIVE_INFINITY : 0,
                    Map.of());
        }

        Cruise cruise = cruise(ship.drives(), mass, trip);
        double time = time(trip.distance(), cruise.speed, cruise.acceleration);
        Map<String, Double> fuel = burn(cruise.firing, mass, cruise.speed);

        String refusal = null;
        if (!(liftOff > 1)) {
            refusal = DriveRefusal.THRUST_TOO_LOW;
        } else if (!(landing > 1)) {
            refusal = DriveRefusal.THRUST_TOO_LOW_TO_LAND;
        } else if (time > trip.maxTime()) {
            refusal = DriveRefusal.OUT_OF_RANGE;
        } else if (fuel.entrySet().stream().anyMatch(e -> e.getValue() > ship.fuel(e.getKey()))) {
            refusal = DriveRefusal.NO_FUEL;
        }
        return new Plot(Optional.ofNullable(refusal), liftOff, landing, cruise.speed, cruise.acceleration, time, fuel);
    }

    /**
     * How long a trip takes: accelerating to a speed, coasting, and braking from it.
     *
     * @param distance how far, in blocks
     * @param speed the cruise speed, in blocks per second, at most {@code sqrt(acceleration *
     *     distance)}
     * @param acceleration the acceleration, in blocks per second squared
     * @return the time in seconds: {@code d / v + v / a}, 0 for no distance
     */
    public static double time(double distance, double speed, double acceleration) {
        if (distance <= 0) {
            return 0;
        }
        return distance / speed + speed / acceleration;
    }

    /** The speed a trip cruises at, the drives that fire to reach it, and how fast they push. */
    private record Cruise(double speed, double acceleration, List<Drive> firing) {}

    /**
     * Finds the fastest cruise speed a trip allows. The drives' distinct top speeds split the speeds
     * into bands; in each band the same drives fire. From the fastest band down, the first whose
     * best speed lies inside it wins: a slower band only adds drives, so it can't be faster.
     */
    private static Cruise cruise(List<Drive> drives, double mass, Trip trip) {
        TreeSet<Double> tops = new TreeSet<>();
        for (Drive drive : drives) {
            tops.add(drive.topSpeed());
        }
        Cruise best = null;
        for (double top : tops.descendingSet()) {
            List<Drive> firing = new ArrayList<>();
            for (Drive drive : drives) {
                if (drive.topSpeed() >= top) {
                    firing.add(drive);
                }
            }
            double acceleration = mass > 0 ? STANDARD_GRAVITY * thrust(firing) / mass : Double.POSITIVE_INFINITY;
            double speed = Math.min(trip.cruiseSpeed(), top);
            if (trip.distance() <= 0) {
                speed = 0;
            } else if (Double.isFinite(acceleration)) {
                speed = Math.min(speed, Math.sqrt(acceleration * trip.distance()));
            }
            best = new Cruise(speed, acceleration, List.copyOf(firing));
            Double below = tops.lower(top);
            if (below == null || speed > below) {
                return best;
            }
        }
        return best;
    }

    /** The fuel the drives burn, by kind: each its share of {@code 2 * m * v} by thrust, over its efficiency. */
    private static Map<String, Double> burn(List<Drive> firing, double mass, double speed) {
        Map<String, Double> fuel = new HashMap<>();
        double momentum = 2 * mass * speed;
        if (momentum <= 0) {
            return fuel;
        }
        double thrust = thrust(firing);
        for (Drive drive : firing) {
            double share = momentum * (drive.thrust() / thrust) / drive.efficiency();
            fuel.merge(drive.fuel(), share, Double::sum);
        }
        return fuel;
    }
}
