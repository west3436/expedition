package io.github.west3436.expedition.drive.gas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** The arithmetic of gas propellant, without the game. */
class PropellantTest {
    private static final double EPSILON = 1e-9;

    private static final ResourceLocation H2 = ResourceLocation.fromNamespaceAndPath("zas", "hydrogen");
    private static final ResourceLocation CH4 = ResourceLocation.fromNamespaceAndPath("zas", "methane");
    private static final ResourceLocation O2 = ResourceLocation.fromNamespaceAndPath("zas", "oxygen");
    private static final ResourceLocation N2 = ResourceLocation.fromNamespaceAndPath("zas", "nitrogen");
    private static final ResourceLocation H2O = ResourceLocation.fromNamespaceAndPath("zas", "water_vapor");
    private static final ResourceLocation CO2 = ResourceLocation.fromNamespaceAndPath("zas", "carbon_dioxide");
    private static final ResourceLocation ODD = ResourceLocation.fromNamespaceAndPath("pack", "odd_fuel");

    private static final Map<ResourceLocation, GasFuel> FUELS = Map.of(
            H2, new GasFuel(H2, 242_000, 0.5, Map.of(H2O, 1.0)),
            CH4, new GasFuel(CH4, 802_000, 2, Map.of(CO2, 1.0, H2O, 2.0)),
            ODD, GasFuel.standard(ODD));

    private static final Propellant.Gases GASES =
            Propellant.Gases.of(gas -> Optional.ofNullable(FUELS.get(gas)), Set.of(O2)::contains);

    @Test
    void poolsFuelAndOxidiserAcrossTanks() {
        // Fuel in one tank, oxidiser in another.
        List<Map<ResourceLocation, Double>> tanks = List.of(Map.of(H2, 100.0, N2, 5.0), Map.of(O2, 50.0));
        Propellant.Measure m = Propellant.measure(tanks, GASES, false);
        assertEquals(100, m.units(), EPSILON);
        assertEquals(50, m.demand(), EPSILON);
        assertEquals(50, m.oxidiser(), EPSILON);
        assertEquals(100, m.usable(), EPSILON);
    }

    @Test
    void shortOxidiserBurnsOnlyItsShare() {
        List<Map<ResourceLocation, Double>> tanks = List.of(Map.of(H2, 100.0, O2, 20.0));
        assertEquals(40, Propellant.measure(tanks, GASES, false).usable(), EPSILON);
        // Breathing the air, the tanks' oxidiser doesn't matter.
        assertEquals(100, Propellant.measure(tanks, GASES, true).usable(), EPSILON);
        assertEquals(
                100, Propellant.measure(List.of(Map.of(H2, 100.0)), GASES, true).usable(), EPSILON);
        assertEquals(
                0, Propellant.measure(List.of(Map.of(H2, 100.0)), GASES, false).usable(), EPSILON);
    }

    @Test
    void richerFuelsGiveMoreUnits() {
        Propellant.Measure m = Propellant.measure(List.of(Map.of(CH4, 10.0)), GASES, true);
        assertEquals(10 * 802_000 / GasFuel.REFERENCE_HEAT, m.units(), EPSILON);
        assertEquals(20, m.demand(), EPSILON);
    }

    @Test
    void drainsTheSameShareOfEveryTank() {
        List<Map<ResourceLocation, Double>> tanks =
                List.of(Map.of(H2, 60.0, N2, 5.0), Map.of(H2, 40.0), Map.of(O2, 100.0));
        // Burn 25 of 100 units: a quarter of the hydrogen, and 12.5 of the oxygen's 100.
        List<Map<ResourceLocation, Double>> plan = Propellant.drain(tanks, GASES, false, 25);
        assertEquals(15, plan.get(0).get(H2), EPSILON);
        assertFalse(plan.get(0).containsKey(N2), "nitrogen is drained");
        assertEquals(10, plan.get(1).get(H2), EPSILON);
        assertEquals(12.5, plan.get(2).get(O2), EPSILON);

        // Breathing the air, no oxidiser leaves the tanks.
        List<Map<ResourceLocation, Double>> air = Propellant.drain(tanks, GASES, true, 25);
        assertTrue(air.get(2).isEmpty());
        // More than can burn burns what can.
        List<Map<ResourceLocation, Double>> all = Propellant.drain(tanks, GASES, false, 1_000);
        assertEquals(60, all.get(0).get(H2), EPSILON);
        assertEquals(50, all.get(2).get(O2), EPSILON);
    }

    @Test
    void exhaustIsTheProductsAndHeat() {
        Map<ResourceLocation, Double> drained = Map.of(H2, 10.0, O2, 5.0, N2, 1.0);
        Map<ResourceLocation, Double> out = Propellant.exhaust(drained, GASES);
        assertEquals(Map.of(H2O, 10.0, N2, 1.0), out);
        assertEquals(10 * 242_000, Propellant.heat(drained, GASES), EPSILON);
        assertEquals(5, Propellant.oxidiserFor(drained, GASES), EPSILON);

        // A fuel with no exhaust given leaves unburned, with its oxidiser, and gives no heat here.
        Map<ResourceLocation, Double> odd = Propellant.exhaust(Map.of(ODD, 4.0, O2, 4.0), GASES);
        assertEquals(Map.of(ODD, 4.0, O2, 4.0), odd);
        assertEquals(0, Propellant.heat(Map.of(ODD, 4.0), GASES), EPSILON);
        assertEquals(0, Propellant.oxidiserFor(Map.of(ODD, 4.0), GASES), EPSILON);
    }
}
