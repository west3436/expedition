package io.github.west3436.expedition.drive.gas;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * How well one gas burns in a gas thruster: what a thruster fuel file says of it. Every gas in ZAS³'s
 * {@code #zas:flammable} tag is a fuel; a file only tunes one, and a flammable gas with none burns as
 * {@link #standard}.
 *
 * <p>Files live at {@code data/<namespace>/expedition/thruster_fuel/<path>.json}:
 *
 * <pre>{@code
 * {
 *   "gas": "zas:methane",
 *   "heat": 802000,
 *   "oxidiser": 2,
 *   "exhaust": { "zas:carbon_dioxide": 1, "zas:water_vapor": 2 }
 * }
 * }</pre>
 *
 * @param gas the gas's id
 * @param heat the heat one mole of it gives burning, in joules, above 0. It sets how much thrust a
 *     mole buys: one unit of a gas thruster's fuel is {@link #REFERENCE_HEAT}
 * @param oxidiser the moles of oxidiser one mole of it burns with, at least 0
 * @param exhaust what one mole of it, burned with its oxidiser, leaves behind the nozzle, by gas, in
 *     moles. Empty for the gas itself, unburned
 */
public record GasFuel(ResourceLocation gas, double heat, double oxidiser, Map<ResourceLocation, Double> exhaust) {
    /** One unit of fuel, in joules: what a mole of hydrogen gives burning. */
    public static final double REFERENCE_HEAT = 242_000;

    private static final Codec<Double> POSITIVE = Codec.DOUBLE.validate(d -> d > 0 && Double.isFinite(d)
            ? DataResult.success(d)
            : DataResult.error(() -> "must be finite and above 0, not " + d));

    private static final Codec<Double> NOT_NEGATIVE = Codec.DOUBLE.validate(d -> d >= 0 && Double.isFinite(d)
            ? DataResult.success(d)
            : DataResult.error(() -> "must be finite and at least 0, not " + d));

    /** A thruster fuel file. */
    public static final Codec<GasFuel> CODEC = RecordCodecBuilder.create(i -> i.group(
                    ResourceLocation.CODEC.fieldOf("gas").forGetter(GasFuel::gas),
                    POSITIVE.fieldOf("heat").forGetter(GasFuel::heat),
                    NOT_NEGATIVE.fieldOf("oxidiser").forGetter(GasFuel::oxidiser),
                    Codec.unboundedMap(ResourceLocation.CODEC, NOT_NEGATIVE)
                            .optionalFieldOf("exhaust", Map.of())
                            .forGetter(GasFuel::exhaust))
            .apply(i, GasFuel::new));

    /**
     * Checks the arguments and copies the exhaust.
     *
     * @throws IllegalArgumentException if the heat isn't above 0 or the oxidiser is negative
     */
    public GasFuel {
        Objects.requireNonNull(gas, "gas");
        if (!(heat > 0) || Double.isInfinite(heat)) {
            throw new IllegalArgumentException("heat must be finite and above 0, not " + heat);
        }
        if (!(oxidiser >= 0) || Double.isInfinite(oxidiser)) {
            throw new IllegalArgumentException("oxidiser must be finite and at least 0, not " + oxidiser);
        }
        exhaust = Map.copyOf(exhaust);
    }

    /**
     * A flammable gas no file tunes: one unit of fuel a mole, burning with a mole of oxidiser, and
     * leaving itself unburned, for the air behind the nozzle to burn.
     *
     * @param gas the gas
     * @return its fuel
     */
    public static GasFuel standard(ResourceLocation gas) {
        return new GasFuel(gas, REFERENCE_HEAT, 1.0, Map.of());
    }

    /**
     * Units of a gas thruster's fuel in one mole of this gas.
     *
     * @return the heat over {@link #REFERENCE_HEAT}
     */
    public double units() {
        return heat / REFERENCE_HEAT;
    }

    /**
     * What one mole leaves behind the nozzle.
     *
     * @return the exhaust, or the gas itself if none is given
     */
    public Map<ResourceLocation, Double> exhaustPerMole() {
        return exhaust.isEmpty() ? Map.of(gas, 1.0) : exhaust;
    }
}
