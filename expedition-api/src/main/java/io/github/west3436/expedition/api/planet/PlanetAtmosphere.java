package io.github.west3436.expedition.api.planet;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * The open air of a planet's surface, in ZAS³'s units: the shape of ZAS³'s own ambient air files,
 * with a temperature for the day and one for the night.
 *
 * <p>A planet with no atmosphere at all ({@link Planet#atmosphere()} empty) leaves its dimension's
 * air to ZAS³, as the Overworld, the Nether and the End do. A vacuum is an atmosphere that says
 * there is no air.
 *
 * @param partialPressures each gas's partial pressure in kPa, by ZAS³ gas id, in the file's order;
 *     empty for a vacuum
 * @param dayTemperature the temperature by day, in kelvin
 * @param nightTemperature the temperature by night, in kelvin; the same as by day when the file
 *     gives one temperature
 * @param vacuum whether the surface has no air
 */
public record PlanetAtmosphere(
        Map<ResourceLocation, Double> partialPressures,
        double dayTemperature,
        double nightTemperature,
        boolean vacuum) {
    /**
     * Checks the atmosphere, and copies the pressures into an unmodifiable map that keeps their
     * order.
     *
     * @param partialPressures each gas's partial pressure in kPa, by ZAS³ gas id
     * @param dayTemperature the temperature by day, in kelvin
     * @param nightTemperature the temperature by night, in kelvin
     * @param vacuum whether the surface has no air
     * @throws NullPointerException if the map, or a key or value in it, is null
     * @throws IllegalArgumentException if a pressure is negative or not finite, a temperature isn't
     *     finite and above 0 K, a vacuum lists a gas, or an atmosphere that isn't a vacuum has no
     *     pressure
     */
    public PlanetAtmosphere {
        Map<ResourceLocation, Double> copy = new LinkedHashMap<>();
        double total = 0;
        for (Map.Entry<ResourceLocation, Double> e : partialPressures.entrySet()) {
            ResourceLocation gas = Objects.requireNonNull(e.getKey(), "gas");
            double kPa = Objects.requireNonNull(e.getValue(), "pressure");
            if (!(kPa >= 0) || Double.isInfinite(kPa)) {
                throw new IllegalArgumentException("the pressure of " + gas + " is " + kPa + " kPa");
            }
            copy.put(gas, kPa);
            total += kPa;
        }
        if (!(dayTemperature > 0) || Double.isInfinite(dayTemperature)) {
            throw new IllegalArgumentException("the day temperature is " + dayTemperature + " K");
        }
        if (!(nightTemperature > 0) || Double.isInfinite(nightTemperature)) {
            throw new IllegalArgumentException("the night temperature is " + nightTemperature + " K");
        }
        if (vacuum && !copy.isEmpty()) {
            throw new IllegalArgumentException("a vacuum lists gases: " + copy.keySet());
        }
        if (!vacuum && !(total > 0)) {
            throw new IllegalArgumentException("an atmosphere that isn't a vacuum has no pressure");
        }
        partialPressures = Collections.unmodifiableMap(copy);
    }

    /**
     * The surface's total pressure.
     *
     * @return the sum of the partial pressures, in kPa; 0 for a vacuum
     */
    public double totalPressure() {
        double total = 0;
        for (double kPa : partialPressures.values()) {
            total += kPa;
        }
        return total;
    }

    /**
     * One gas's partial pressure.
     *
     * @param gas the ZAS³ gas id
     * @return its pressure in kPa, or 0 if the atmosphere doesn't list it
     */
    public double partialPressure(ResourceLocation gas) {
        return partialPressures.getOrDefault(gas, 0.0);
    }
}
