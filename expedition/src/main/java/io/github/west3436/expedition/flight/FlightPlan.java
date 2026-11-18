package io.github.west3436.expedition.flight;

import io.github.west3436.expedition.drive.Plot;
import io.github.west3436.expedition.mover.MoveRefusal;
import io.github.west3436.expedition.mover.Obstacle;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * A flight worked out before it goes (#29): its legs, the thrust model's plot, where the ship's box
 * arrives and how many ticks it spends in transit; or why it can't go.
 *
 * @param refusal why the flight can't go, or empty if it can
 * @param order what the pilot asked
 * @param legs the trip's legs, once both ends are known
 * @param plot the thrust model's answer, once the legs are known
 * @param to the level the ship arrives in: the planet's, or Space for an orbit
 * @param destination where the box arrives, once known
 * @param ticks how many game ticks the trip spends in transit, once the plot is feasible
 */
public record FlightPlan(
        Optional<String> refusal,
        FlightOrder order,
        Optional<FlightLegs> legs,
        Optional<Plot> plot,
        Optional<ResourceKey<Level>> to,
        Optional<BoundingBox> destination,
        long ticks) {
    /**
     * A flight refused before anything was worked out.
     *
     * @param code why
     * @param order what the pilot asked
     * @return the plan
     */
    static FlightPlan refused(String code, FlightOrder order) {
        return new FlightPlan(
                Optional.of(code), order, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0);
    }

    /**
     * Whether the flight may go.
     *
     * @return true if nothing refuses it
     */
    public boolean feasible() {
        return refusal.isEmpty();
    }

    /**
     * Where the box's lowest corner arrives.
     *
     * @return the corner
     * @throws java.util.NoSuchElementException if the destination isn't known
     */
    public BlockPos origin() {
        BoundingBox box = destination.orElseThrow();
        return new BlockPos(box.minX(), box.minY(), box.minZ());
    }

    /**
     * How long the trip spends in transit.
     *
     * @return the seconds
     */
    public double seconds() {
        return ticks / 20.0;
    }

    /**
     * What the window says of the plan: where the ship arrives, how far and how long, and what it
     * burns; or why it can't go.
     *
     * @param planet the destination planet's name
     * @return the message
     */
    public Component message(Component planet) {
        if (refusal.isPresent()) {
            return Component.translatable(KEY + "refused", MoveRefusal.message(refusal.get()));
        }
        BlockPos o = origin();
        MutableComponent where = order.orbit()
                ? Component.translatable(KEY + "orbit", planet)
                : Component.translatable(KEY + "ground", planet, o.getX(), o.getY(), o.getZ());
        Map<String, Double> fuel = new TreeMap<>(plot.orElseThrow().fuel());
        StringBuilder burns = new StringBuilder();
        fuel.forEach((kind, amount) -> {
            if (!burns.isEmpty()) {
                burns.append(", ");
            }
            burns.append(kind).append(' ').append(String.format(Locale.ROOT, "%.1f", amount));
        });
        return Component.translatable(
                KEY + "plotted",
                where,
                Obstacle.OverLimit.number(Math.round(legs.orElseThrow().distance())),
                time(ticks),
                burns.isEmpty() ? "-" : burns.toString());
    }

    /**
     * A time as the messages write it: seconds, or minutes and seconds.
     *
     * @param ticks game ticks
     * @return such as {@code 45 s} or {@code 5 min 12 s}
     */
    public static String time(long ticks) {
        long s = (ticks + 19) / 20;
        return s < 60 ? s + " s" : (s / 60) + " min " + (s % 60) + " s";
    }

    /**
     * The same plan refused, with everything else kept.
     *
     * @param code why
     * @return the plan
     */
    FlightPlan refuse(String code) {
        return new FlightPlan(Optional.of(code), order, legs, plot, to, destination, ticks);
    }

    private static final String KEY = "expedition.flight.plan.";
}
