package io.github.west3436.expedition;

import io.github.west3436.expedition.api.planet.SpaceSquare;
import io.github.west3436.expedition.drive.gas.GasThrusters;
import io.github.west3436.expedition.flight.Flights;
import io.github.west3436.expedition.mover.ShipCeiling;
import io.github.west3436.expedition.mover.ShipMover;
import io.github.west3436.expedition.planet.PlanetBorders;
import io.github.west3436.expedition.portal.PortalRules;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Expedition's server settings, in {@code serverconfig/expedition-server.toml} of each world. The
 * ship settings apply on the next move after the file is reloaded, with no restart.
 *
 * <p>The {@code planets.border} section bounds planets ({@link PlanetBorders}). It is off by
 * default, and applies when the server starts and whenever the file is reloaded.
 *
 * <p>The {@code portals} section turns Nether and End portals off ({@link PortalRules}). Both are on
 * by default, and a change applies at once.
 *
 * <p>The {@code flights} section scales trip times and sets how rough a landing site may be ({@link
 * Flights}).
 *
 * <p>The {@code ships} section is the server's ceiling on a ship's box ({@link ShipMover#ceiling()}).
 * It is a technical limit, not a game rule: ship size has no gameplay cap, since thrust against mass
 * limits a ship, but the mover, ZAS³'s relocation hand-over and the transit slots all need an upper
 * bound. The defaults come from the measurements in #8, on a 4-vCPU runner with a 3 GiB heap:
 *
 * <ul>
 *   <li>{@link #MAX_VOLUME}, 131,072 blocks (64 × 64 × 32): a full box at it takes about 30 ticks
 *       a move, 3 s a trip, and allocates about 200 MiB a move. 262,144 (64³) works, at about 6 s a
 *       trip and 400 MiB a move, which brought 50–200 ms garbage-collection pauses on a small heap.
 *   <li>{@link #MAX_LONGEST_SIDE}, 64 blocks: the largest side measured. It keeps a box within 6 × 6
 *       chunk columns and sets the transit slots' spacing. Going past it needs new measurements.
 *   <li>{@link #MAX_HOP_VOLUME}, 4,096 blocks (16³): a short hop, whose destination overlaps its own
 *       box, is carried whole in one tick, whatever the tick budget. A full 16³ box costs that tick
 *       about 6–10 ms; a full box at the volume ceiling would cost about 150 ms.
 * </ul>
 */
public final class ExpeditionConfig {
    private static final String KEY = ExpeditionMod.MOD_ID + ".configuration.";

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.comment(
                        "The server's ceiling on a ship's box. This is a technical limit, not a game rule: it keeps one",
                        "move from stalling the server. A move over it is refused as too_large, before anything is read.",
                        "Raising it makes the largest moves longer and heavier on memory; see each value.")
                .translation(KEY + "ships")
                .push("ships");
    }

    /** The most blocks a ship's box may hold, counting air. */
    public static final ModConfigSpec.IntValue MAX_VOLUME = BUILDER.comment(
                    "The most blocks a ship's box may hold, air included. The default, 131072, is 64 x 64 x 32.",
                    "A full box at the default takes about 1.5 s a move, 3 s a trip, and allocates about 200 MiB",
                    "a move. 262144 (64 x 64 x 64) works, but takes about 6 s a trip, allocates about 400 MiB a",
                    "move and can bring 50-200 ms lag spikes from garbage collection on a small heap.")
            .translation(KEY + "maxVolume")
            .defineInRange("maxVolume", 131_072, 1, Integer.MAX_VALUE);

    /** The most blocks a ship's box may be along any axis. */
    public static final ModConfigSpec.IntValue MAX_LONGEST_SIDE = BUILDER.comment(
                    "The most blocks a ship's box may be along any one axis. 64 is the largest size that has been",
                    "measured; a longer side spans more chunks, and needs wider spacing between transit slots.")
            .translation(KEY + "maxLongestSide")
            .defineInRange("maxLongestSide", 64, 1, Integer.MAX_VALUE);

    /** The most blocks a short hop's box may hold: a move whose destination overlaps its own box. */
    public static final ModConfigSpec.IntValue MAX_HOP_VOLUME = BUILDER.comment(
                    "The most blocks a ship's box may hold for a short hop: a move whose destination overlaps the",
                    "box itself. Such a hop is carried whole in one server tick, so it is refused as hop_too_large",
                    "over this, though the same ship may still move further. A full 16 x 16 x 16 box (4096) costs",
                    "that tick about 6-10 ms; every 4096 blocks more adds about as much.")
            .translation(KEY + "maxOverlappingHopVolume")
            .defineInRange("maxOverlappingHopVolume", 4_096, 1, Integer.MAX_VALUE);

    static {
        BUILDER.pop();
        BUILDER.comment("Planets on this server.").translation(KEY + "planets").push("planets");
        BUILDER.comment(
                        "World borders at each planet's edge, with chunks far past them left empty. Off by default.",
                        "The border follows the planet file: centred on surface.center, space.size blocks a side.",
                        "These apply when the server starts and when this file is reloaded. Chunks already",
                        "generated are never changed; only chunks generated afterwards are left empty.")
                .translation(KEY + "border")
                .push("border");
    }

    /** Whether planets get world borders and a void past them. */
    public static final ModConfigSpec.BooleanValue BORDER_ENABLED = BUILDER.comment(
                    "Give every planet a world border at its square's edge, and leave chunks far past it empty.")
            .translation(KEY + "borderEnabled")
            .define("enabled", false);

    /** How many blocks of terrain generate past a planet's border. */
    public static final ModConfigSpec.IntValue VOID_MARGIN = BUILDER.comment(
                    "Blocks of terrain kept past the border. Chunks wholly beyond are left empty, so terrain",
                    "runs on to the next chunk edge. -1 keeps terrain everywhere.")
            .translation(KEY + "voidMargin")
            .defineInRange("voidMargin", 200, -1, SpaceSquare.LIMIT);

    /** The planets, by dimension id, left unbounded. */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BORDER_EXCLUDE = BUILDER.comment(
                    "Planets (dimension ids) left unbounded even when enabled, such as [\"minecraft:overworld\"].")
            .translation(KEY + "exclude")
            .defineListAllowEmpty(
                    "exclude",
                    List.of(),
                    () -> "minecraft:overworld",
                    entry -> entry instanceof String id && ResourceLocation.tryParse(id) != null);

    static {
        BUILDER.pop(2);
        BUILDER.comment(
                        "Nether and End portals. Both work as in vanilla by default. Turned off, a portal still lights,",
                        "but entering it does nothing and a player is told why: a ship is the only way in. Only going",
                        "in is stopped: leaving the Nether or the End by portal always works, as does the End's exit",
                        "portal after the dragon, so nobody is stranded. Operators get no bypass.")
                .translation(KEY + "portals")
                .push("portals");
    }

    /** Whether a Nether portal takes anything into the Nether. */
    public static final ModConfigSpec.BooleanValue NETHER_PORTALS = BUILDER.comment(
                    "Whether a Nether portal takes anything into the Nether. Leaving the Nether by one always works.")
            .translation(KEY + "netherPortals")
            .define("nether", true);

    /** Whether an End portal takes anything into the End. */
    public static final ModConfigSpec.BooleanValue END_PORTALS = BUILDER.comment(
                    "Whether an End portal takes anything into the End. Leaving the End by one always works.")
            .translation(KEY + "endPortals")
            .define("end", true);

    static {
        BUILDER.pop();
        BUILDER.comment("Ship drives: each engine's numbers.")
                .translation(KEY + "drives")
                .push("drives");
        BUILDER.comment(
                        "The gas thruster: burns a fuel gas with an oxidiser, from the tanks beside it. High thrust,",
                        "low efficiency, low top speed: what lifts a ship off a planet.")
                .translation(KEY + "gasThruster")
                .push("gasThruster");
    }

    /** A gas thruster's thrust. */
    public static final ModConfigSpec.DoubleValue GAS_THRUST = BUILDER.comment(
                    "Thrust, in weight on the Overworld: 400 lifts a ship of mass 400 there, at a thrust-to-weight",
                    "of 1. Taking off and landing need more than 1.")
            .translation(KEY + "gasThrust")
            .defineInRange("thrust", GasThrusters.Settings.DEFAULT.thrust(), 0.001, 1e9);

    /** A gas thruster's efficiency. */
    public static final ModConfigSpec.DoubleValue GAS_EFFICIENCY = BUILDER.comment(
                    "Efficiency: momentum (mass x blocks per second) per unit of fuel. A trip burns",
                    "2 x mass x cruise speed / efficiency. One unit is a mole of hydrogen; methane gives 3.3.")
            .translation(KEY + "gasEfficiency")
            .defineInRange("efficiency", GasThrusters.Settings.DEFAULT.efficiency(), 0.001, 1e9);

    /** A gas thruster's top speed. */
    public static final ModConfigSpec.DoubleValue GAS_TOP_SPEED = BUILDER.comment(
                    "Top speed, in blocks per second. Above it the thruster stays cold.")
            .translation(KEY + "gasTopSpeed")
            .defineInRange("topSpeed", GasThrusters.Settings.DEFAULT.topSpeed(), 0.001, 1e6);

    /** The oxidisers' partial pressure at which a gas thruster breathes air. */
    public static final ModConfigSpec.DoubleValue GAS_AIR_BREATHING = BUILDER.comment(
                    "The partial pressure of oxidisers (#zas:oxidizer), in kPa, behind the nozzle at which a",
                    "thruster breathes the air and needs no oxidiser from its tanks. The Overworld's air has 21.")
            .translation(KEY + "gasAirBreathing")
            .defineInRange("airBreathingKpa", GasThrusters.Settings.DEFAULT.airBreathingKpa(), 0.0, 1e6);

    /** The temperature of a gas thruster's exhaust. */
    public static final ModConfigSpec.DoubleValue GAS_EXHAUST_TEMPERATURE = BUILDER.comment(
                    "The exhaust's temperature, in kelvin, as it leaves the nozzle.")
            .translation(KEY + "gasExhaustTemperature")
            .defineInRange("exhaustTemperature", GasThrusters.Settings.DEFAULT.exhaustTemperature(), 1.0, 1e5);

    /** The share of a burn's heat left behind a gas thruster's nozzle. */
    public static final ModConfigSpec.DoubleValue GAS_EXHAUST_HEAT = BUILDER.comment(
                    "The share of the burn's heat left behind the nozzle, 0 to 1. The rest leaves with the jet.")
            .translation(KEY + "gasExhaustHeat")
            .defineInRange("exhaustHeatShare", GasThrusters.Settings.DEFAULT.exhaustHeatShare(), 0.0, 1.0);

    static {
        BUILDER.pop(2);
        BUILDER.comment("Flights: how long trips take, and where ships may land.")
                .translation(KEY + "flights")
                .push("flights");
    }

    /** How much of the thrust model's time a trip spends in transit. */
    public static final ModConfigSpec.DoubleValue TRIP_TIME = BUILDER.comment(
                    "Scales every trip's time in transit. At 0.2, a well-powered ship takes about a minute to the",
                    "Moon and about 5 minutes to the Nether. 1 is the thrust model's own time; lower is faster.")
            .translation(KEY + "tripTimeMultiplier")
            .defineInRange("tripTimeMultiplier", Flights.Settings.DEFAULT.tripTimeMultiplier(), 0.0001, 1000.0);

    /** The most the ground under a landing ship may vary. */
    public static final ModConfigSpec.IntValue MAX_UNEVENNESS = BUILDER.comment(
                    "The most the ground under a ship's footprint may vary, in blocks, for it to land there. A",
                    "landing over rougher ground is refused as too_uneven.")
            .translation(KEY + "maxUnevenness")
            .defineInRange("maxUnevenness", Flights.Settings.DEFAULT.maxUnevenness(), 0, 4096);

    /** How long a launch counts down. */
    public static final ModConfigSpec.IntValue WARM_UP = BUILDER.comment(
                    "How long the drives warm up before a launch, in seconds, counted down in the action bar.")
            .translation(KEY + "warmUpSeconds")
            .defineInRange("warmUpSeconds", Flights.Settings.DEFAULT.warmUpTicks() / 20, 0, 600);

    static {
        BUILDER.pop();
    }

    /** The server settings' spec, registered as the mod's {@code SERVER} config. */
    public static final ModConfigSpec SPEC = BUILDER.build();

    /** The ceiling on a ship's box made from these settings, read at every move. */
    public static final ShipCeiling CEILING =
            ShipCeiling.of(() -> value(MAX_VOLUME), () -> value(MAX_LONGEST_SIDE), () -> value(MAX_HOP_VOLUME));

    /** The portal settings, read at every portal trip. */
    public static final PortalRules.Settings PORTALS =
            new PortalRules.Settings(() -> flag(NETHER_PORTALS), () -> flag(END_PORTALS));

    /** A gas thruster's numbers, read at every use. */
    public static final Supplier<GasThrusters.Settings> GAS_THRUSTER = () -> new GasThrusters.Settings(
            value(GAS_THRUST),
            value(GAS_EFFICIENCY),
            value(GAS_TOP_SPEED),
            value(GAS_AIR_BREATHING),
            value(GAS_EXHAUST_TEMPERATURE),
            value(GAS_EXHAUST_HEAT));

    /** The flight settings, read at every use. */
    public static final Supplier<Flights.Settings> FLIGHTS =
            () -> new Flights.Settings(value(TRIP_TIME), value(MAX_UNEVENNESS), value(WARM_UP) * 20);

    private ExpeditionConfig() {}

    /** A setting's value, or its default while no world's settings are loaded. */
    private static double value(ModConfigSpec.DoubleValue setting) {
        return SPEC.isLoaded() ? setting.getAsDouble() : setting.getDefault();
    }

    /** A setting's value, or its default while no world's settings are loaded. */
    private static boolean flag(ModConfigSpec.BooleanValue setting) {
        return SPEC.isLoaded() ? setting.getAsBoolean() : setting.getDefault();
    }

    /** A setting's value, or its default while no world's settings are loaded. */
    static int value(ModConfigSpec.IntValue setting) {
        return SPEC.isLoaded() ? setting.getAsInt() : setting.getDefault();
    }
}
