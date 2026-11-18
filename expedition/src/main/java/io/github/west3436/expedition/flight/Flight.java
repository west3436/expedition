package io.github.west3436.expedition.flight;

import io.github.west3436.expedition.transit.TransitTrip;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * One flight a Shuttle Computer launched ({@link Flights#launch}), from its countdown until the
 * ship arrives. It is kept in memory: after a restart the trip goes on, and lands or goes into
 * orbit, but no one holds its {@code Flight}.
 *
 * <p>Thread: server thread.
 */
public final class Flight {
    /** Where a flight is. */
    public enum State {
        /** Counting down to launch. */
        COUNTDOWN,
        /** Leaving for transit. */
        DEPARTING,
        /** In transit. */
        IN_TRANSIT,
        /** Over: arrived, or refused. */
        OVER
    }

    /**
     * How a flight ended.
     *
     * @param refusal why it never left, or empty if it did
     * @param landingRefusal why it couldn't land where it was going, so went into orbit instead; or
     *     empty
     * @param level where the ship arrived, if it did
     * @param origin where the box's lowest corner arrived, if it did
     */
    public record Result(
            Optional<String> refusal,
            Optional<String> landingRefusal,
            Optional<ResourceKey<Level>> level,
            Optional<BlockPos> origin) {
        /**
         * Whether the ship arrived somewhere.
         *
         * @return true if it left and arrived
         */
        public boolean arrived() {
            return refusal.isEmpty() && level.isPresent();
        }

        static Result refused(String code) {
            return new Result(Optional.of(code), Optional.empty(), Optional.empty(), Optional.empty());
        }
    }

    final ServerLevel level;
    final BlockPos computer;
    final FlightOrder order;

    @Nullable
    final UUID pilot;

    State state = State.COUNTDOWN;
    FlightPlan plan;
    int countdown;

    @Nullable
    TransitTrip trip;

    /** The players aboard at departure, who are told how the flight goes. */
    final Set<UUID> aboard = new LinkedHashSet<>();

    /** Why the landing was refused, if it was. */
    @Nullable
    String landingRefusal;

    /** Where the ship goes into orbit instead, once its landing was refused. */
    @Nullable
    BlockPos fallbackOrigin;

    final CompletableFuture<Result> result = new CompletableFuture<>();

    Flight(ServerLevel level, BlockPos computer, FlightOrder order, @Nullable UUID pilot) {
        this.level = level;
        this.computer = computer.immutable();
        this.order = order;
        this.pilot = pilot;
    }

    /**
     * What the pilot asked.
     *
     * @return the order
     */
    public FlightOrder order() {
        return order;
    }

    /**
     * The plan, as last worked out: at launch, and again as the countdown ends.
     *
     * @return the plan
     */
    public FlightPlan plan() {
        return plan;
    }

    /**
     * Where the flight is.
     *
     * @return the state
     */
    public State state() {
        return state;
    }

    /**
     * The transit trip, once the ship is leaving.
     *
     * @return the trip, or empty during the countdown or if the flight never left
     */
    public Optional<TransitTrip> trip() {
        return Optional.ofNullable(trip);
    }

    /**
     * How the flight ends: once the ship has arrived, or the flight was refused.
     *
     * @return the future, the same one every time
     */
    public CompletableFuture<Result> result() {
        return result;
    }

    @Override
    public String toString() {
        return "Flight[" + level.dimension().location() + " " + computer.toShortString() + " to " + order + ", " + state
                + "]";
    }
}
