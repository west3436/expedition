package io.github.west3436.expedition.drive.gas;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;

/**
 * The arithmetic of a ship's gas propellant, with no game: how much fuel its tanks hold, what a burn
 * takes from each, and what it leaves behind the nozzles.
 *
 * <p>Fuel is counted in units ({@link GasFuel#units()}): a mole of hydrogen is one. Each fuel burns
 * with its own share of oxidiser ({@link GasFuel#oxidiser()}), from the ship's tanks, unless the
 * thrusters breathe the air around them. Fuel and oxidiser may sit in different tanks: the ship's
 * tanks are pooled, so short oxidiser burns only that share of the fuel.
 */
public final class Propellant {
    private Propellant() {}

    /** Which gases are fuels and oxidisers. */
    public interface Gases {
        /**
         * A gas's fuel, if it is one.
         *
         * @param gas the gas
         * @return its fuel, or empty if it doesn't burn in a thruster
         */
        Optional<GasFuel> fuel(ResourceLocation gas);

        /**
         * Whether a gas is an oxidiser. A gas that is a fuel counts only as a fuel.
         *
         * @param gas the gas
         * @return true if a thruster burns its fuel with it
         */
        boolean oxidiser(ResourceLocation gas);

        /**
         * Gases from a fuel lookup and an oxidiser test.
         *
         * @param fuels the fuels
         * @param oxidisers the oxidisers
         * @return the gases
         */
        static Gases of(Function<ResourceLocation, Optional<GasFuel>> fuels, Predicate<ResourceLocation> oxidisers) {
            return new Gases() {
                @Override
                public Optional<GasFuel> fuel(ResourceLocation gas) {
                    return fuels.apply(gas);
                }

                @Override
                public boolean oxidiser(ResourceLocation gas) {
                    return oxidisers.test(gas);
                }
            };
        }
    }

    /**
     * What a ship's tanks hold, pooled.
     *
     * @param units the fuel, in units
     * @param demand the moles of oxidiser all of it would burn with
     * @param oxidiser the moles of oxidiser in the tanks
     * @param airBreathing whether the thrusters take their oxidiser from the air instead
     */
    public record Measure(double units, double demand, double oxidiser, boolean airBreathing) {
        /**
         * How much of the fuel can burn: all of it if the thrusters breathe air or the tanks hold
         * oxidiser enough, else the share the oxidiser covers.
         *
         * @return the fuel that can burn, in units
         */
        public double usable() {
            if (airBreathing || demand <= oxidiser) {
                return units;
            }
            return units * (oxidiser / demand);
        }
    }

    /**
     * Pools a ship's tanks.
     *
     * @param tanks each tank's moles, by gas, each tank once
     * @param gases which gases are fuels and oxidisers
     * @param airBreathing whether the thrusters breathe the air
     * @return the measure
     */
    public static Measure measure(List<Map<ResourceLocation, Double>> tanks, Gases gases, boolean airBreathing) {
        double units = 0;
        double demand = 0;
        double oxidiser = 0;
        for (Map<ResourceLocation, Double> tank : tanks) {
            for (Map.Entry<ResourceLocation, Double> e : tank.entrySet()) {
                double moles = Math.max(0, e.getValue());
                Optional<GasFuel> fuel = gases.fuel(e.getKey());
                if (fuel.isPresent()) {
                    units += moles * fuel.get().units();
                    demand += moles * fuel.get().oxidiser();
                } else if (gases.oxidiser(e.getKey())) {
                    oxidiser += moles;
                }
            }
        }
        return new Measure(units, demand, oxidiser, airBreathing);
    }

    /**
     * What to take from each tank to burn some fuel: the same share of every fuel in every tank,
     * and, unless the thrusters breathe air, the same share of every oxidiser, enough for that fuel.
     *
     * @param tanks each tank's moles, by gas, as {@link #measure} saw them
     * @param gases which gases are fuels and oxidisers
     * @param airBreathing whether the thrusters breathe the air
     * @param amount the fuel to burn, in units; more than can burn burns what can
     * @return the moles to take from each tank, by gas, in the tanks' order
     */
    public static List<Map<ResourceLocation, Double>> drain(
            List<Map<ResourceLocation, Double>> tanks, Gases gases, boolean airBreathing, double amount) {
        Measure measure = measure(tanks, gases, airBreathing);
        double burn = Math.min(Math.max(0, amount), measure.usable());
        double fuelShare = measure.units > 0 ? burn / measure.units : 0;
        double oxidiserShare =
                airBreathing || measure.oxidiser <= 0 ? 0 : Math.min(1, fuelShare * measure.demand / measure.oxidiser);
        List<Map<ResourceLocation, Double>> plan = new ArrayList<>(tanks.size());
        for (Map<ResourceLocation, Double> tank : tanks) {
            Map<ResourceLocation, Double> take = new HashMap<>();
            for (Map.Entry<ResourceLocation, Double> e : tank.entrySet()) {
                double moles = Math.max(0, e.getValue());
                double share =
                        gases.fuel(e.getKey()).isPresent() ? fuelShare : gases.oxidiser(e.getKey()) ? oxidiserShare : 0;
                if (share > 0 && moles > 0) {
                    take.put(e.getKey(), moles * share);
                }
            }
            plan.add(Map.copyOf(take));
        }
        return plan;
    }

    /**
     * The oxidiser some fuel burns with as it leaves: what the fuels whose files give an exhaust
     * take, from the tanks or, for thrusters that breathe it, from the air. A fuel with no exhaust
     * given leaves unburned and takes none.
     *
     * @param drained the moles of each gas taken from the tanks; gases that aren't fuels are passed
     *     over
     * @param gases which gases are fuels
     * @return the moles of oxidiser
     */
    public static double oxidiserFor(Map<ResourceLocation, Double> drained, Gases gases) {
        double moles = 0;
        for (Map.Entry<ResourceLocation, Double> e : drained.entrySet()) {
            Optional<GasFuel> fuel = gases.fuel(e.getKey());
            if (fuel.isPresent() && !fuel.get().exhaust().isEmpty()) {
                moles += e.getValue() * fuel.get().oxidiser();
            }
        }
        return moles;
    }

    /**
     * What drained propellant leaves behind the nozzles. A fuel whose file gives an exhaust leaves
     * that, and the oxidiser it burned with ({@link #oxidiserFor}) is gone into it. A fuel with no
     * exhaust given leaves unburned, as does any oxidiser drained beyond what was burned, for the
     * air behind the nozzle to burn. Anything else drained leaves unchanged.
     *
     * @param drained the moles of each gas taken from the tanks
     * @param gases which gases are fuels and oxidisers
     * @return the exhaust, in moles by gas
     */
    public static Map<ResourceLocation, Double> exhaust(Map<ResourceLocation, Double> drained, Gases gases) {
        Map<ResourceLocation, Double> out = new HashMap<>();
        Map<ResourceLocation, Double> oxidisers = new HashMap<>();
        double oxidiser = 0;
        for (Map.Entry<ResourceLocation, Double> e : drained.entrySet()) {
            Optional<GasFuel> fuel = gases.fuel(e.getKey());
            if (fuel.isPresent()) {
                fuel.get()
                        .exhaustPerMole()
                        .forEach((gas, perMole) -> out.merge(gas, perMole * e.getValue(), Double::sum));
            } else if (gases.oxidiser(e.getKey())) {
                oxidisers.put(e.getKey(), e.getValue());
                oxidiser += e.getValue();
            } else {
                out.merge(e.getKey(), e.getValue(), Double::sum);
            }
        }
        double left = oxidiser - oxidiserFor(drained, gases);
        if (left > 0 && oxidiser > 0) {
            double share = left / oxidiser;
            oxidisers.forEach((gas, moles) -> out.merge(gas, moles * share, Double::sum));
        }
        out.values().removeIf(moles -> !(moles > 1e-12));
        return Map.copyOf(out);
    }

    /**
     * The heat drained propellant gives burning as it leaves: that of the fuels whose files give an
     * exhaust. A fuel that leaves unburned gives its heat only when the air behind the nozzle burns
     * it.
     *
     * @param drained the moles of each gas taken from the tanks
     * @param gases which gases are fuels
     * @return the heat in joules
     */
    public static double heat(Map<ResourceLocation, Double> drained, Gases gases) {
        double joules = 0;
        for (Map.Entry<ResourceLocation, Double> e : drained.entrySet()) {
            Optional<GasFuel> fuel = gases.fuel(e.getKey());
            if (fuel.isPresent() && !fuel.get().exhaust().isEmpty()) {
                joules += e.getValue() * fuel.get().heat();
            }
        }
        return joules;
    }
}
