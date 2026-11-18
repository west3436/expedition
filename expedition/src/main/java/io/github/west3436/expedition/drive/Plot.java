package io.github.west3436.expedition.drive;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The thrust model's answer for one trip: whether it may go, and what it would cost. The numbers are
 * worked out even for a refused trip, as far as they can be, so a pilot can see how far short the
 * ship falls.
 *
 * @param refusal why the trip can't go ({@link DriveRefusal}), or empty if it may
 * @param liftOffRatio thrust-to-weight where the ship stands: positive infinity in space
 * @param landingRatio thrust-to-weight where it lands: positive infinity in space
 * @param cruiseSpeed the speed it cruises at, in blocks per second: 0 for a trip of no distance, or
 *     with no drive
 * @param acceleration how fast the drives that fire at that speed push it, in blocks per second
 *     squared
 * @param time how long the trip takes, in seconds: accelerating, coasting and braking. Positive
 *     infinity with no drive
 * @param fuel how much of each fuel the trip burns, all of it at departure. A fuel it burns none of
 *     is missing
 */
public record Plot(
        Optional<String> refusal,
        double liftOffRatio,
        double landingRatio,
        double cruiseSpeed,
        double acceleration,
        double time,
        Map<String, Double> fuel) {
    /**
     * Checks the arguments and copies the map.
     *
     * @throws NullPointerException if the refusal or the fuel is null
     */
    public Plot {
        Objects.requireNonNull(refusal, "refusal");
        fuel = Map.copyOf(fuel);
    }

    /**
     * Whether the trip may go.
     *
     * @return true if nothing refuses it
     */
    public boolean feasible() {
        return refusal.isEmpty();
    }

    /**
     * How much of one fuel the trip burns.
     *
     * @param kind the fuel
     * @return the amount, 0 if it burns none
     */
    public double fuel(String kind) {
        return fuel.getOrDefault(Objects.requireNonNull(kind, "kind"), 0.0);
    }
}
