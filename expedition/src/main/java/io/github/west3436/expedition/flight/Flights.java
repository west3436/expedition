package io.github.west3436.expedition.flight;

import com.mojang.logging.LogUtils;
import io.github.west3436.expedition.api.planet.Planet;
import io.github.west3436.expedition.api.planet.PlanetGravity;
import io.github.west3436.expedition.api.planet.Planets;
import io.github.west3436.expedition.api.space.Space;
import io.github.west3436.expedition.api.space.Transit;
import io.github.west3436.expedition.drive.Plot;
import io.github.west3436.expedition.drive.Trip;
import io.github.west3436.expedition.mover.MoveCheck;
import io.github.west3436.expedition.mover.MoveRefusal;
import io.github.west3436.expedition.mover.MoveRequest;
import io.github.west3436.expedition.mover.MoveResult;
import io.github.west3436.expedition.mover.MoveSettings;
import io.github.west3436.expedition.mover.ZasRelocationHandover;
import io.github.west3436.expedition.ship.ShipComputers;
import io.github.west3436.expedition.ship.ShuttleComputerBlockEntity;
import io.github.west3436.expedition.transit.TransitRoute;
import io.github.west3436.expedition.transit.TransitTrip;
import io.github.west3436.expedition.transit.Transits;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.LogicalSide;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Flights (#29): a Shuttle Computer's ship takes off, crosses in transit and lands, or stays in
 * orbit. This is where the mover, the thrust model, the transit dimension and the planets meet.
 *
 * <ol>
 *   <li><b>Plot</b> ({@link #plan}): the legs ({@link FlightLegs}), the landing site ({@link
 *       LandingSite}), the thrust model's plot ({@link ShipComputers#plot}) and the time in transit,
 *       the plot's time scaled by {@code flights.tripTimeMultiplier}.
 *   <li><b>Launch</b> ({@link #launch}): the computer's owner or an operator only. A warm-up counts
 *       down in the action bar of the pilot and everyone in the box; then the plan is worked out
 *       again, since the world may have changed.
 *   <li><b>Departure</b>: the box goes into transit ({@link Transits#depart}), with the pilot's claim
 *       check ({@link MoveSettings#startedBy}). The fuel is taken as the move begins, once nothing
 *       can refuse it ({@link MoveCheck#cleared}), so a refused departure burns none. The arrival is
 *       scheduled at once, and so the landing site is reserved: another flight can't plan to arrive
 *       where this one will.
 *   <li><b>Arrival</b>: at the time the plan gave, the box leaves transit for its site. If the site
 *       is refused then (something was built there, or it was claimed), the ship arrives in orbit
 *       above it instead, and the pilot is told why. A ship is never left in transit: if the orbit is
 *       taken too, it tries higher, and then again every minute.
 * </ol>
 *
 * <p>The flights in transit are saved ({@link FlightData}), so the orbit fallback works across a
 * restart; a flight still counting down is not, and a restart cancels it.
 *
 * <p>Thread: server thread only.
 */
public final class Flights {
    static final Logger LOGGER = LogUtils.getLogger();

    /** Where a ship in orbit is: its box's lowest layer, in Space. */
    public static final int ORBIT_Y = 128;

    /** How many orbits, one above another, a refused landing tries before waiting a minute. */
    public static final int FALLBACK_ALTITUDES = 8;

    /** Ticks between rounds of orbits, once each altitude was refused. */
    static final int FALLBACK_WAIT = 1_200;

    private static final String KEY = "expedition.flight.";

    /**
     * The server's flight settings.
     *
     * @param tripTimeMultiplier how much of the thrust model's time a trip spends in transit
     * @param maxUnevenness the most the ground under a landing footprint may vary, in blocks
     * @param warmUpTicks how long the countdown to launch is, in ticks
     */
    public record Settings(double tripTimeMultiplier, int maxUnevenness, int warmUpTicks) {
        /** The defaults: a fifth of the model's time, 4 blocks, 5 seconds. */
        public static final Settings DEFAULT = new Settings(0.2, 4, 100);
    }

    private static Supplier<Settings> settings = () -> Settings.DEFAULT;

    /** The flights counting down. */
    private static final List<Flight> COUNTING = new ArrayList<>();

    /** The flights that have left, by their trip's id. */
    private static final Map<UUID, Flight> FLYING = new HashMap<>();

    private Flights() {}

    /**
     * Listens on the game bus.
     *
     * @param gameBus NeoForge's game event bus
     */
    public static void register(IEventBus gameBus) {
        gameBus.addListener(Flights::onServerTick);
        gameBus.addListener((ServerStoppedEvent e) -> {
            COUNTING.clear();
            FLYING.clear();
        });
    }

    /**
     * Sets where the settings are read from. The mod sets its server settings as it loads; a
     * GameTest may shorten the trips.
     *
     * @param source the settings, read at each use
     */
    public static void setSettings(Supplier<Settings> source) {
        settings = Objects.requireNonNull(source, "source");
    }

    /**
     * The settings now.
     *
     * @return the settings
     */
    public static Settings settings() {
        return settings.get();
    }

    // ---- Plotting -------------------------------------------------------------------------

    /**
     * The planets a pilot may fly to: every planet loaded, but those hidden until scanned.
     *
     * @param side the side whose planets to list
     * @return the planets
     */
    public static List<Planet> destinations(LogicalSide side) {
        return Planets.all(side).stream()
                .filter(p -> !p.access().hiddenUntilScanned())
                .toList();
    }

    /**
     * A planet's name for messages: {@code expedition.planet.<namespace>.<path>}, or its id's path.
     *
     * @param id the planet's id
     * @return the name
     */
    public static Component name(ResourceLocation id) {
        return Component.translatableWithFallback(
                "expedition.planet." + id.getNamespace() + "." + id.getPath(), id.getPath());
    }

    /**
     * Works out a flight for a computer's ship, without changing anything. Anyone may ask; only the
     * owner or an operator may {@link #launch}.
     *
     * @param level the computer's level
     * @param computer the computer
     * @param order where to go
     * @return the plan
     */
    public static FlightPlan plan(ServerLevel level, ShuttleComputerBlockEntity computer, FlightOrder order) {
        return plan(level, computer, order, null);
    }

    private static FlightPlan plan(
            ServerLevel level, ShuttleComputerBlockEntity computer, FlightOrder order, @Nullable Flight self) {
        MinecraftServer server = level.getServer();
        if (Transit.is(level)) {
            return FlightPlan.refused(MoveRefusal.BUSY, order);
        }
        Optional<Planet> found = Planets.byId(order.planet(), LogicalSide.SERVER)
                .filter(p -> !p.access().hiddenUntilScanned());
        if (found.isEmpty()) {
            return FlightPlan.refused(FlightRefusal.NO_PLANET, order);
        }
        Planet planet = found.get();
        BoundingBox box = computer.worldBox();
        int cx = box.minX() + box.getXSpan() / 2;
        int cz = box.minZ() + box.getZSpan() / 2;

        FlightLegs.End from;
        if (Space.is(level)) {
            from = FlightLegs.inSpace(cx, cz);
        } else {
            Optional<Planet> here = Planets.forLevel(level);
            if (here.isEmpty()) {
                return FlightPlan.refused(FlightRefusal.NO_PLANET, order);
            }
            BlockPos s = Space.fromSurface(here.get(), new BlockPos(cx, 0, cz));
            from = FlightLegs.over(here.get().space(), s.getX(), s.getZ(), true);
        }
        BlockPos t = Space.fromSurface(planet, new BlockPos(order.x(), 0, order.z()));
        FlightLegs.End end = FlightLegs.over(planet.space(), t.getX(), t.getZ(), !order.orbit());
        FlightLegs legs = FlightLegs.of(from, end);

        ResourceKey<Level> toKey = order.orbit() ? Space.DIMENSION : planet.dimension();
        ServerLevel to = server.getLevel(toKey);
        if (to == null) {
            return FlightPlan.refused(Transits.NO_DESTINATION, order);
        }
        BlockPos origin;
        if (order.orbit()) {
            origin = orbit(to, box, legs.toX(), legs.toZ(), 0);
        } else {
            LandingSite site = LandingSite.find(
                    to, box, order.x(), order.z(), settings().maxUnevenness(), to == level ? box : null);
            if (!site.ok()) {
                return new FlightPlan(
                        site.refusal(),
                        order,
                        Optional.of(legs),
                        Optional.empty(),
                        Optional.of(toKey),
                        Optional.empty(),
                        0);
            }
            origin = site.origin();
        }
        BoundingBox destination =
                box.moved(origin.getX() - box.minX(), origin.getY() - box.minY(), origin.getZ() - box.minZ());

        Trip trip = Trip.of(legs.distance(), PlanetGravity.of(level), order.orbit() ? 0 : planet.gravity());
        if (order.hasCruiseSpeed()) {
            trip = trip.atSpeed(order.cruiseSpeed());
        }
        Plot plot = ShipComputers.plot(level, computer, trip);
        long ticks = plot.feasible()
                ? Math.max(1, (long) Math.ceil(plot.time() * settings().tripTimeMultiplier() * 20))
                : 0;
        FlightPlan plan = new FlightPlan(
                plot.refusal(),
                order,
                Optional.of(legs),
                Optional.of(plot),
                Optional.of(toKey),
                Optional.of(destination),
                ticks);
        if (plan.feasible() && reserved(server, toKey, destination, self)) {
            return plan.refuse(FlightRefusal.RESERVED);
        }
        return plan;
    }

    /** Where a box goes into orbit over a point in Space: its middle there, at an orbit's height. */
    private static BlockPos orbit(ServerLevel space, BoundingBox box, double x, double z, int altitude) {
        int y = ORBIT_Y + altitude * (box.getYSpan() + 8);
        y = Math.min(y, space.getMaxBuildHeight() - box.getYSpan());
        return new BlockPos((int) Math.floor(x) - box.getXSpan() / 2, y, (int) Math.floor(z) - box.getZSpan() / 2);
    }

    /** Whether another flight will arrive, or plans to, where a box would. */
    private static boolean reserved(
            MinecraftServer server, ResourceKey<Level> to, BoundingBox box, @Nullable Flight self) {
        for (TransitTrip trip : Transits.trips(server)) {
            if ((self == null || trip != self.trip)
                    && trip.destination().equals(Optional.of(to))
                    && trip.arrivalBox().map(b -> b.intersects(box)).orElse(false)) {
                return true;
            }
        }
        for (Flight other : COUNTING) {
            if (other != self
                    && other.plan.to().equals(Optional.of(to))
                    && other.plan.destination().map(b -> b.intersects(box)).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    // ---- Launching ------------------------------------------------------------------------

    /**
     * Whether a player may launch a computer's ship: its owner, or an operator. Anyone may open the
     * window and plot.
     *
     * @param computer the computer
     * @param player the player, or null for a launch no player asked for, such as a command's
     * @return true if they may
     */
    public static boolean mayLaunch(ShuttleComputerBlockEntity computer, @Nullable ServerPlayer player) {
        if (player == null) {
            return true;
        }
        if (computer.owner().map(player.getUUID()::equals).orElse(false)) {
            return true;
        }
        return player.server.getPlayerList().isOp(player.getGameProfile());
    }

    /**
     * Launches a computer's ship: works out the flight, and if it may go, counts down to launch.
     *
     * @param level the computer's level
     * @param computer the computer
     * @param order where to go
     * @param pilot who launched it, or null for a launch no player asked for, which no claim refuses
     * @return the flight; its result is already complete if it was refused
     */
    public static Flight launch(
            ServerLevel level, ShuttleComputerBlockEntity computer, FlightOrder order, @Nullable ServerPlayer pilot) {
        Flight flight = new Flight(level, computer.getBlockPos(), order, pilot == null ? null : pilot.getUUID());
        flight.plan = FlightPlan.refused(FlightRefusal.NOT_OWNER, order);
        if (!mayLaunch(computer, pilot)) {
            return end(flight, Flight.Result.refused(FlightRefusal.NOT_OWNER));
        }
        if (launching(level, computer.getBlockPos())) {
            flight.plan = FlightPlan.refused(FlightRefusal.LAUNCHING, order);
            return end(flight, Flight.Result.refused(FlightRefusal.LAUNCHING));
        }
        flight.plan = plan(level, computer, order, null);
        if (!flight.plan.feasible()) {
            return end(flight, Flight.Result.refused(flight.plan.refusal().get()));
        }
        flight.countdown = Math.max(0, settings().warmUpTicks());
        COUNTING.add(flight);
        if (flight.countdown == 0) {
            go(flight);
        } else {
            tellCountdown(flight);
        }
        return flight;
    }

    /**
     * Whether a computer's ship is counting down to launch.
     *
     * @param level the computer's level
     * @param pos where the computer is
     * @return true if it is
     */
    public static boolean launching(ServerLevel level, BlockPos pos) {
        for (Flight f : COUNTING) {
            if (f.level == level && f.computer.equals(pos)) {
                return true;
            }
        }
        return false;
    }

    // ---- Each tick ------------------------------------------------------------------------

    private static void onServerTick(ServerTickEvent.Post event) {
        for (Flight flight : List.copyOf(COUNTING)) {
            if (--flight.countdown <= 0) {
                go(flight);
            } else if (flight.countdown % 20 == 0) {
                tellCountdown(flight);
            }
        }
        fallBack(event.getServer());
    }

    private static void tellCountdown(Flight flight) {
        long seconds = (flight.countdown + 19) / 20;
        tell(flight, Component.translatable(KEY + "countdown", name(flight.order.planet()), seconds), true);
    }

    /** The countdown is over: works the plan out again, and sends the ship into transit. */
    private static void go(Flight flight) {
        COUNTING.remove(flight);
        ServerLevel level = flight.level;
        if (!level.isLoaded(flight.computer)
                || !(level.getBlockEntity(flight.computer) instanceof ShuttleComputerBlockEntity computer)) {
            abort(flight, FlightRefusal.NO_COMPUTER);
            return;
        }
        FlightPlan plan = plan(level, computer, flight.order, flight);
        flight.plan = plan;
        if (!plan.feasible()) {
            abort(flight, plan.refusal().get());
            return;
        }
        MinecraftServer server = level.getServer();
        ServerPlayer pilot =
                flight.pilot == null ? null : server.getPlayerList().getPlayer(flight.pilot);
        ServerLevel to = server.getLevel(plan.to().get());
        long now = Transits.now(server);
        FlightLegs legs = plan.legs().get();
        TransitRoute route =
                new TransitRoute(legs.fromX(), legs.fromZ(), legs.toX(), legs.toZ(), now, now + plan.ticks());
        BoundingBox box = computer.worldBox();
        for (ServerPlayer p : level.getEntitiesOfClass(ServerPlayer.class, AABB.of(box))) {
            flight.aboard.add(p.getUUID());
        }

        // The cabin keeps its air pinned under the ship's own owner, wherever it lands.
        MoveSettings ship = MoveSettings.DEFAULT.withHandover(ZasRelocationHandover.keeping(computer.airOwner()));
        MoveSettings claims = ship.startedBy(pilot);
        List<MoveCheck> checks = new ArrayList<>(claims.checks());
        checks.add(new Burn(level, flight.computer, plan.plot().get()));
        TransitTrip trip = Transits.depart(level, box, route, claims.withChecks(checks));
        flight.trip = trip;
        flight.state = Flight.State.DEPARTING;
        if (trip.hasArrived()) {
            // Refused at once, such as a box too tall for transit.
            departed(flight, trip.departure().join(), null);
            return;
        }
        CompletableFuture<MoveResult> arrival =
                Transits.arrive(trip, to, plan.origin(), now + plan.ticks(), ship.startedBy(pilot));
        FLYING.put(trip.id(), flight);
        FlightData data = data(server);
        data.flights.put(trip.id(), new FlightData.Entry(Optional.ofNullable(flight.pilot), 0));
        data.setDirty();
        trip.departure().whenComplete((result, error) -> departed(flight, result, error));
        arrival.whenComplete((result, error) -> arrived(flight, result, error, false));
    }

    private static void abort(Flight flight, String code) {
        tell(flight, Component.translatable(KEY + "aborted", MoveRefusal.message(code)), false);
        end(flight, Flight.Result.refused(code));
    }

    private static void departed(Flight flight, @Nullable MoveResult result, @Nullable Throwable error) {
        if (flight.state == Flight.State.OVER) {
            return;
        }
        if (error != null || !result.moved()) {
            String code = error != null
                    ? MoveRefusal.HANDOVER_FAILED
                    : result.refusal().orElse(MoveRefusal.HANDOVER_FAILED);
            if (flight.trip != null) {
                FLYING.remove(flight.trip.id());
            }
            tell(flight, Component.translatable(KEY + "not_departed", MoveRefusal.message(code)), false);
            end(flight, Flight.Result.refused(code));
            return;
        }
        flight.state = Flight.State.IN_TRANSIT;
        flight.aboard.addAll(flight.trip.aboard());
        long left = flight.trip.arrivesAt().orElse(Transits.now(flight.level.getServer()))
                - Transits.now(flight.level.getServer());
        tell(
                flight,
                Component.translatable(
                        KEY + "departed", name(flight.order.planet()), FlightPlan.time(Math.max(0, left))),
                true);
    }

    private static void arrived(Flight flight, @Nullable MoveResult result, @Nullable Throwable error, boolean orbit) {
        if (flight.state == Flight.State.OVER) {
            return;
        }
        if (error != null || !result.moved()) {
            // Left in transit: the next tick sends it into orbit ({@link #fallBack}).
            flight.landingRefusal = error != null
                    ? MoveRefusal.HANDOVER_FAILED
                    : result.refusal().orElse(MoveRefusal.HANDOVER_FAILED);
            return;
        }
        FLYING.remove(flight.trip.id());
        BlockPos at = orbit ? flight.fallbackOrigin : flight.plan.origin();
        if (orbit || flight.order.orbit()) {
            tell(flight, Component.translatable(KEY + "in_orbit", name(flight.order.planet())), false);
        } else {
            tell(
                    flight,
                    Component.translatable(
                            KEY + "landed", name(flight.order.planet()), at.getX(), at.getY(), at.getZ()),
                    false);
        }
        ResourceKey<Level> where = orbit ? Space.DIMENSION : flight.plan.to().get();
        Optional<String> refused = orbit
                ? Optional.of(flight.landingRefusal == null ? MoveRefusal.BLOCKED : flight.landingRefusal)
                : Optional.empty();
        end(flight, new Flight.Result(Optional.empty(), refused, Optional.of(where), Optional.ofNullable(at)));
    }

    /**
     * Sends each flight whose arrival was refused into orbit over its site: transit leaves such a
     * ship in its slot with nothing scheduled.
     */
    private static void fallBack(MinecraftServer server) {
        FlightData data = data(server);
        if (data.flights.isEmpty()) {
            return;
        }
        Map<UUID, TransitTrip> trips = new HashMap<>();
        for (TransitTrip trip : Transits.trips(server)) {
            trips.put(trip.id(), trip);
        }
        for (Map.Entry<UUID, FlightData.Entry> e : List.copyOf(data.flights.entrySet())) {
            TransitTrip trip = trips.get(e.getKey());
            if (trip == null) {
                // Arrived, or never left.
                data.flights.remove(e.getKey());
                FLYING.remove(e.getKey());
                data.setDirty();
                continue;
            }
            if (!trip.inTransit() || trip.arrivesAt().isPresent()) {
                continue;
            }
            ServerLevel space = server.getLevel(Space.DIMENSION);
            if (space == null) {
                continue;
            }
            int round = e.getValue().fallbacks();
            Flight flight = FLYING.get(e.getKey());
            BlockPos origin =
                    orbit(space, trip.box(), trip.route().toX(), trip.route().toZ(), round % FALLBACK_ALTITUDES);
            long at = Transits.now(server) + (round > 0 && round % FALLBACK_ALTITUDES == 0 ? FALLBACK_WAIT : 0);
            data.flights.put(e.getKey(), new FlightData.Entry(e.getValue().pilot(), round + 1));
            data.setDirty();
            LOGGER.info("The landing of trip {} was refused; it arrives in orbit at {} instead", trip.id(), origin);
            // No claim check: a ship is never stranded in transit for want of an orbit.
            CompletableFuture<MoveResult> arrival = Transits.arrive(trip, space, origin, at, MoveSettings.DEFAULT);
            if (flight != null) {
                flight.fallbackOrigin = origin;
                if (round == 0) {
                    String why = flight.landingRefusal == null ? MoveRefusal.BLOCKED : flight.landingRefusal;
                    tell(
                            flight,
                            Component.translatable(
                                    KEY + "fallback", MoveRefusal.message(why), name(flight.order.planet())),
                            false);
                }
                arrival.whenComplete((result, error) -> arrived(flight, result, error, true));
            } else if (round == 0) {
                Component message = Component.translatable(KEY + "fallback.unknown");
                Set<UUID> told = new LinkedHashSet<>(trip.aboard());
                e.getValue().pilot().ifPresent(told::add);
                for (UUID id : told) {
                    ServerPlayer p = server.getPlayerList().getPlayer(id);
                    if (p != null) {
                        p.sendSystemMessage(message);
                    }
                }
            }
        }
    }

    // ---- Helpers --------------------------------------------------------------------------

    private static Flight end(Flight flight, Flight.Result result) {
        flight.state = Flight.State.OVER;
        COUNTING.remove(flight);
        flight.result.complete(result);
        return flight;
    }

    /** Tells the pilot and everyone aboard, in the action bar or in chat. */
    private static void tell(Flight flight, Component message, boolean actionBar) {
        MinecraftServer server = flight.level.getServer();
        Set<ServerPlayer> told = new LinkedHashSet<>();
        if (flight.pilot != null) {
            ServerPlayer pilot = server.getPlayerList().getPlayer(flight.pilot);
            if (pilot != null) {
                told.add(pilot);
            }
        }
        for (UUID id : flight.aboard) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                told.add(p);
            }
        }
        if (flight.state == Flight.State.COUNTDOWN
                && flight.level.getBlockEntity(flight.computer) instanceof ShuttleComputerBlockEntity computer) {
            told.addAll(flight.level.getEntitiesOfClass(ServerPlayer.class, AABB.of(computer.worldBox())));
        }
        for (ServerPlayer p : told) {
            p.displayClientMessage(message, actionBar);
        }
    }

    private static FlightData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FlightData.factory(), FlightData.NAME);
    }

    /**
     * Takes a departure's fuel as its move begins: once nothing can refuse it, so a refused
     * departure burns none.
     */
    private record Burn(ServerLevel level, BlockPos computer, Plot plot) implements MoveCheck {
        @Override
        public Optional<String> check(MoveRequest request) {
            return Optional.empty();
        }

        @Override
        public void cleared(MoveRequest request) {
            if (level.getBlockEntity(computer) instanceof ShuttleComputerBlockEntity c) {
                try {
                    ShipComputers.burn(level, c, plot);
                } catch (RuntimeException e) {
                    LOGGER.error("Taking the fuel for the departure from {} failed", computer, e);
                }
            }
        }
    }
}
