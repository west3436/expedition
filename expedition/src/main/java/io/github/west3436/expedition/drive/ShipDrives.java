package io.github.west3436.expedition.drive;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the thrust model needs to know of a ship: its mass, its drives and the fuel it carries.
 *
 * @param mass the ship's mass: the weights of its blocks (#54), at least 0. Cargo and the contents
 *     of tanks add none
 * @param drives every drive in the ship's box whose nozzle is open: each adds its thrust whichever
 *     way it faces
 * @param fuel how much of each fuel ({@link Drive#fuel()}) the ship's drives can draw on; a fuel
 *     missing from the map is none
 */
public record ShipDrives(double mass, List<Drive> drives, Map<String, Double> fuel) {
    /**
     * Checks the arguments and copies the lists.
     *
     * @throws IllegalArgumentException if the mass or an amount of fuel is negative or not finite
     * @throws NullPointerException if an argument or an element is null
     */
    public ShipDrives {
        if (!(mass >= 0) || Double.isInfinite(mass)) {
            throw new IllegalArgumentException("mass must be finite and at least 0, not " + mass);
        }
        drives = List.copyOf(drives);
        fuel = Map.copyOf(fuel);
        fuel.forEach((kind, amount) -> {
            if (!(amount >= 0) || Double.isInfinite(amount)) {
                throw new IllegalArgumentException("fuel " + kind + " must be finite and at least 0, not " + amount);
            }
        });
    }

    /**
     * The thrust of all the ship's drives together.
     *
     * @return the sum of their thrusts
     */
    public double thrust() {
        return ThrustModel.thrust(drives);
    }

    /**
     * How much of one fuel the ship carries.
     *
     * @param kind the fuel
     * @return the amount, 0 if it carries none
     */
    public double fuel(String kind) {
        return fuel.getOrDefault(Objects.requireNonNull(kind, "kind"), 0.0);
    }
}
