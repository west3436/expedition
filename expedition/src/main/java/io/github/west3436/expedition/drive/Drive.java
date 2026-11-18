package io.github.west3436.expedition.drive;

import java.util.Objects;

/**
 * One engine's numbers, which are all the thrust model knows of it. Every kind of drive is these
 * three numbers and the fuel it burns; nothing is special-cased.
 *
 * <ul>
 *   <li><b>Thrust</b> is in units of weight on the Overworld: a thrust of 100 holds up a mass of 100
 *       at gravity 1, and pushes the same mass at {@link ThrustModel#STANDARD_GRAVITY} blocks per
 *       second squared in space.
 *   <li><b>Efficiency</b> is how much momentum one unit of fuel buys: mass times blocks per second.
 *       A trip burns {@code 2 * m * v / efficiency}, to reach the cruise speed {@code v} and to brake
 *       from it.
 *   <li><b>Top speed</b>, in blocks per second, is the fastest the engine can push a ship. Above it
 *       the engine doesn't fire.
 * </ul>
 *
 * <p>The numbers come from data or the server's settings, never from code: each drive block reads
 * its own.
 *
 * @param fuel what the engine burns, such as {@code "gas"}: engines of one fuel share its supply
 * @param thrust its thrust, above 0
 * @param efficiency momentum per unit of fuel, above 0
 * @param topSpeed its top speed in blocks per second, above 0
 */
public record Drive(String fuel, double thrust, double efficiency, double topSpeed) {
    /**
     * Checks the numbers.
     *
     * @throws IllegalArgumentException if a number isn't finite and above 0
     * @throws NullPointerException if the fuel is null
     */
    public Drive {
        Objects.requireNonNull(fuel, "fuel");
        positive("thrust", thrust);
        positive("efficiency", efficiency);
        positive("topSpeed", topSpeed);
    }

    private static void positive(String name, double value) {
        if (!(value > 0) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be finite and above 0, not " + value);
        }
    }
}
