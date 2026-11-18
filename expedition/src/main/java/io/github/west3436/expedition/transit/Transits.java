package io.github.west3436.expedition.transit;

import com.mojang.logging.LogUtils;
import io.github.west3436.expedition.api.space.Space;
import io.github.west3436.expedition.api.space.Transit;
import io.github.west3436.expedition.mover.MoveRefusal;
import io.github.west3436.expedition.mover.MoveRequest;
import io.github.west3436.expedition.mover.MoveResult;
import io.github.west3436.expedition.mover.ShipMover;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * The transit dimension's slots and the trips in them (#25): where a ship is for the length of a
 * trip. Every trip is two moves by the {@link ShipMover mover}: {@link #depart} takes a box from
 * where it is into a slot of its own, and {@link #arrive} brings it out at a destination at a given
 * game time. What happens before and after (take-off, landing, fuel) is the trip's caller's.
 *
 * <p><b>Slots.</b> Each trip takes the lowest free slot ({@link TransitSlots}), so no ship ever sees
 * another, and frees it when the ship arrives, for the next trip to reuse.
 *
 * <p><b>Loading.</b> A move needs both its ends loaded, so for each move the trip holds the chunks
 * at both ends and starts it once they and their entities are loaded. Between the moves it holds
 * nothing: with nobody aboard, the slot isn't kept loaded, and the ship arrives on schedule by the
 * Overworld's game time all the same. A move refused for something that passes (a chunk not yet
 * loaded, the air busy) is tried again a second later, up to {@link #ATTEMPTS} times.
 *
 * <p><b>Who is aboard.</b> The players the departure carried into the slot are aboard, and are
 * saved with the trip ({@link TransitData}). One who logs out mid-trip is in the slot when they
 * return, if the ship is still there; if it has arrived meanwhile, they are put back aboard where it
 * arrived. A player who dies in transit respawns at their usual spawn point, as anywhere: beds and
 * respawn anchors don't work in transit.
 *
 * <p><b>The rules of the dimension.</b> It holds ships and nothing else. Nothing can be placed
 * outside a ship's box, nothing spawns naturally, and no portal lights or takes anyone anywhere.
 * Anything that goes more than {@link #GRACE} blocks outside a ship's box, players included, drops
 * into Space at the point on the route the ship has reached, as far from that point as it was from
 * the box's middle.
 *
 * <p>Thread: server thread only.
 */
public final class Transits {
    static final Logger LOGGER = LogUtils.getLogger();

    /** A departure refused because the box is taller than the transit dimension. */
    public static final String TOO_TALL = "transit_too_tall";

    /** An arrival refused because its destination's dimension isn't on the server. */
    public static final String NO_DESTINATION = "no_destination";

    /** The action-bar message for a player who stepped outside their ship. */
    public static final String STEPPED_OUTSIDE = "expedition.transit.stepped_outside";

    /** The action-bar message for a player who tried to build outside their ship. */
    public static final String NO_BUILDING = "expedition.transit.no_building";

    /** How far outside a ship's box, in blocks, something may go before it is outside. */
    public static final int GRACE = 2;

    /** How many times a move refused for something that passes is tried. */
    public static final int ATTEMPTS = 30;

    /** Ticks between tries of a refused move. */
    static final int RETRY_TICKS = 20;

    /** Ticks between looks for anything outside the ships. */
    static final int OUTSIDE_EVERY = 10;

    /** The refusals that pass on their own, which a trip waits out. */
    static final Set<String> PASSING = Set.of(
            MoveRefusal.UNLOADED,
            MoveRefusal.BUSY,
            MoveRefusal.ATMOS_BUSY,
            MoveRefusal.ATMOS_PAUSED,
            MoveRefusal.ATMOS_UNLOADED,
            MoveRefusal.ATMOS_TIMED_OUT,
            MoveRefusal.ATMOS_ENDED);

    /** Holds a move's chunks loaded while a trip waits to start it; its value is the trip's id. */
    private static final TicketType<UUID> HOLD = TicketType.create("expedition_transit", UUID::compareTo);

    private static IntSupplier longestSide = () -> 64;

    private Transits() {}

    /**
     * Listens on the game bus.
     *
     * @param gameBus NeoForge's game event bus
     */
    public static void register(IEventBus gameBus) {
        gameBus.addListener(Transits::onServerTick);
        gameBus.addListener(Transits::onLoggedIn);
        gameBus.addListener(Transits::onPlace);
        gameBus.addListener(Transits::onPortalSpawn);
        gameBus.addListener(Transits::onSpawnCheck);
    }

    /**
     * Sets where the box ceiling's longest side is read from, which sets the slots' spacing. The mod
     * sets its server setting as it loads.
     *
     * @param side the most blocks a box may be along any axis
     */
    public static void setLongestSide(IntSupplier side) {
        longestSide = Objects.requireNonNull(side, "side");
    }

    // ---- The API --------------------------------------------------------------------------

    /**
     * Sends a box into transit: takes a slot, and moves the box into it once both ends are loaded.
     * The trip is in transit once its {@link TransitTrip#departure() departure} completes with a
     * move; if the move is refused, the slot is freed and nothing changed.
     *
     * @param from the level the box is in
     * @param box the box
     * @param route the route the ship flies, in Space
     * @return the trip
     * @throws IllegalStateException off the server thread, or if the server has no transit
     *     dimension
     */
    public static TransitTrip depart(ServerLevel from, BoundingBox box, TransitRoute route) {
        MinecraftServer server = from.getServer();
        requireServerThread(server);
        Objects.requireNonNull(box, "box");
        Objects.requireNonNull(route, "route");
        ServerLevel transit = level(server);
        TransitData data = data(server);
        int pitch = TransitSlots.pitch(Math.max(longestSide.getAsInt(), longest(box)));
        List<TransitSlots.Taken> taken = new ArrayList<>();
        for (TransitTrip trip : data.trips.values()) {
            taken.add(new TransitSlots.Taken(trip.slotOrigin(), trip.pitch()));
        }
        int slot = TransitSlots.allocate(taken, pitch);
        BlockPos corner = TransitSlots.corner(
                TransitSlots.origin(slot, pitch),
                box.getYSpan(),
                transit.getMinBuildHeight(),
                transit.getMaxBuildHeight() - 1);
        BoundingBox inSlot = corner == null
                ? box
                : box.moved(corner.getX() - box.minX(), corner.getY() - box.minY(), corner.getZ() - box.minZ());
        TransitTrip trip = new TransitTrip(UUID.randomUUID(), slot, pitch, inSlot, route, TransitTrip.Status.DEPARTING);
        if (corner == null) {
            trip.status = TransitTrip.Status.ARRIVED;
            trip.departure.complete(refused(TOO_TALL));
            return trip;
        }
        trip.from = from;
        trip.fromBox = box;
        data.trips.put(trip.id(), trip);
        hold(trip, from, box);
        hold(trip, transit, inSlot);
        return trip;
    }

    /**
     * Schedules a trip's arrival: at the game time given, its box leaves the slot for a destination,
     * whether or not anyone is aboard and whether or not the slot is loaded. A trip still departing
     * arrives once it is in transit. A refused arrival leaves the ship in its slot, with no arrival
     * scheduled, for the caller to schedule another.
     *
     * @param trip the trip
     * @param to the level the box goes to
     * @param origin where the box's lowest corner goes
     * @param gameTime when it arrives: the Overworld's game time, at which it starts the move
     * @return the arrival's result: the move, or its refusal
     * @throws IllegalStateException off the server thread, or if the trip has arrived or is arriving
     */
    public static CompletableFuture<MoveResult> arrive(
            TransitTrip trip, ServerLevel to, BlockPos origin, long gameTime) {
        MinecraftServer server = to.getServer();
        requireServerThread(server);
        if (trip.status == TransitTrip.Status.ARRIVED || trip.status == TransitTrip.Status.ARRIVING) {
            throw new IllegalStateException(trip + " has arrived or is arriving");
        }
        trip.arrival = new TransitTrip.Arrival(to.dimension(), origin.immutable(), gameTime);
        trip.arrived = new CompletableFuture<>();
        trip.attempts = 0;
        trip.retryAt = 0;
        data(server).setDirty();
        return trip.arrived;
    }

    /**
     * Every trip under way on a server: departing, in transit or arriving.
     *
     * @param server the server
     * @return the trips, oldest first
     */
    public static List<TransitTrip> trips(MinecraftServer server) {
        return List.copyOf(data(server).trips.values());
    }

    /**
     * The trip whose ship holds a position of the transit dimension.
     *
     * @param server the server
     * @param pos the position
     * @return the trip, or empty if the position is in no ship's box
     */
    public static Optional<TransitTrip> tripAt(MinecraftServer server, BlockPos pos) {
        return Optional.ofNullable(holding(data(server), pos, 0));
    }

    /**
     * The transit dimension's level.
     *
     * @param server the server
     * @return the level
     * @throws IllegalStateException if the server has none
     */
    public static ServerLevel level(MinecraftServer server) {
        ServerLevel level = server.getLevel(Transit.DIMENSION);
        if (level == null) {
            throw new IllegalStateException("the server has no " + Transit.DIMENSION.location() + " dimension");
        }
        return level;
    }

    /**
     * The clock trips keep: the Overworld's game time, which {@code /time set} doesn't change.
     *
     * @param server the server
     * @return the game time
     */
    public static long now(MinecraftServer server) {
        return server.overworld().getGameTime();
    }

    // ---- Each tick ------------------------------------------------------------------------

    private static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ServerLevel transit = server.getLevel(Transit.DIMENSION);
        if (transit == null) {
            return;
        }
        TransitData data = data(server);
        long now = now(server);
        for (TransitTrip trip : List.copyOf(data.trips.values())) {
            if (trip.job != null || now < trip.retryAt) {
                continue;
            }
            if (trip.status == TransitTrip.Status.DEPARTING) {
                startDeparture(server, data, transit, trip);
            } else if (trip.status == TransitTrip.Status.IN_TRANSIT
                    && trip.arrival != null
                    && now >= trip.arrival.at()) {
                startArrival(server, data, transit, trip);
            }
        }
        if (now % OUTSIDE_EVERY == 0) {
            sendOutsideToSpace(server, data, transit, now);
        }
    }

    private static void startDeparture(
            MinecraftServer server, TransitData data, ServerLevel transit, TransitTrip trip) {
        if (!loaded(trip.from, trip.fromBox) || !loaded(transit, trip.box())) {
            return;
        }
        MoveRequest request = new MoveRequest(
                trip.from,
                trip.fromBox,
                transit,
                new BlockPos(trip.box().minX(), trip.box().minY(), trip.box().minZ()));
        trip.job = ShipMover.start(request);
        trip.job.result().whenComplete((result, error) -> departed(server, data, transit, trip, result, error));
    }

    private static void departed(
            MinecraftServer server,
            TransitData data,
            ServerLevel transit,
            TransitTrip trip,
            @Nullable MoveResult result,
            @Nullable Throwable error) {
        trip.job = null;
        if (error != null) {
            LOGGER.error("The departure of {} failed part-way through", trip, error);
            free(data, trip);
            trip.departure.completeExceptionally(error);
            return;
        }
        if (result.moved()) {
            trip.status = TransitTrip.Status.IN_TRANSIT;
            trip.from = null;
            trip.fromBox = null;
            trip.attempts = 0;
            for (ServerPlayer player : transit.players()) {
                if (trip.box().isInside(player.blockPosition())) {
                    trip.aboardMutable().add(player.getUUID());
                }
            }
            release(trip, server);
            data.setDirty();
            trip.departure.complete(result);
            return;
        }
        String code = result.refusal().orElseThrow();
        if (PASSING.contains(code) && ++trip.attempts < ATTEMPTS) {
            trip.retryAt = now(server) + RETRY_TICKS;
            return;
        }
        free(data, trip);
        trip.departure.complete(result);
    }

    private static void startArrival(MinecraftServer server, TransitData data, ServerLevel transit, TransitTrip trip) {
        TransitTrip.Arrival arrival = trip.arrival;
        ServerLevel to = server.getLevel(arrival.to());
        if (to == null) {
            refuseArrival(server, data, trip, refused(NO_DESTINATION));
            return;
        }
        BoundingBox destination = trip.box()
                .moved(
                        arrival.origin().getX() - trip.box().minX(),
                        arrival.origin().getY() - trip.box().minY(),
                        arrival.origin().getZ() - trip.box().minZ());
        if (!trip.holding) {
            hold(trip, transit, trip.box());
            hold(trip, to, destination);
        }
        if (!loaded(transit, trip.box()) || !loaded(to, destination)) {
            return;
        }
        MoveRequest request = new MoveRequest(transit, trip.box(), to, arrival.origin());
        trip.status = TransitTrip.Status.ARRIVING;
        try {
            trip.job = ShipMover.start(request);
        } catch (IllegalArgumentException e) {
            // The destination leaves its level's build height: no world could let it go ahead.
            trip.status = TransitTrip.Status.IN_TRANSIT;
            refuseArrival(server, data, trip, refused(MoveRefusal.OUTSIDE_BORDER));
            return;
        }
        trip.job.result().whenComplete((result, error) -> arrived(server, data, trip, request, result, error));
    }

    private static void arrived(
            MinecraftServer server,
            TransitData data,
            TransitTrip trip,
            MoveRequest request,
            @Nullable MoveResult result,
            @Nullable Throwable error) {
        trip.job = null;
        if (error != null) {
            LOGGER.error("The arrival of {} failed part-way through", trip, error);
            trip.status = TransitTrip.Status.IN_TRANSIT;
            trip.arrival = null;
            release(trip, server);
            data.setDirty();
            trip.arrived.completeExceptionally(error);
            return;
        }
        if (result.moved()) {
            Vec3i offset = request.offset();
            for (UUID player : trip.aboard()) {
                // Whoever was in the slot went with the ship; anyone aboard who wasn't is put back
                // aboard when they next log in.
                if (!(request.to().getEntity(player) instanceof ServerPlayer)) {
                    data.returns.put(player, new TransitData.Return(request.to().dimension(), offset));
                }
            }
            free(data, trip);
            trip.arrived.complete(result);
            return;
        }
        trip.status = TransitTrip.Status.IN_TRANSIT;
        String code = result.refusal().orElseThrow();
        if (PASSING.contains(code) && ++trip.attempts < ATTEMPTS) {
            trip.retryAt = now(server) + RETRY_TICKS;
            return;
        }
        refuseArrival(server, data, trip, result);
    }

    /** Ends an arrival refused: the ship stays in its slot with no arrival scheduled. */
    private static void refuseArrival(MinecraftServer server, TransitData data, TransitTrip trip, MoveResult result) {
        LOGGER.warn(
                "The arrival of {} was refused ({}); the ship stays in transit",
                trip,
                result.refusal().orElse("?"));
        trip.arrival = null;
        trip.attempts = 0;
        release(trip, server);
        data.setDirty();
        trip.arrived.complete(result);
    }

    /** Frees a trip's slot: the trip is over, or never began. */
    private static void free(TransitData data, TransitTrip trip) {
        trip.status = TransitTrip.Status.ARRIVED;
        trip.from = null;
        trip.fromBox = null;
        data.trips.remove(trip.id());
        if (trip.server != null) {
            release(trip, trip.server);
        }
        data.setDirty();
    }

    // ---- Outside the ships -----------------------------------------------------------------

    /**
     * Sends everything more than {@link #GRACE} blocks outside every ship's box into Space, at the
     * point on its ship's route the ship has reached. Something with no ship near is a player sent
     * to their spawn point, or anything else discarded.
     */
    private static void sendOutsideToSpace(MinecraftServer server, TransitData data, ServerLevel transit, long now) {
        List<Entity> outside = new ArrayList<>();
        for (Entity entity : transit.getAllEntities()) {
            if (!entity.isRemoved()
                    && entity.getVehicle() == null
                    && holding(data, entity.blockPosition(), GRACE) == null) {
                outside.add(entity);
            }
        }
        if (outside.isEmpty()) {
            return;
        }
        ServerLevel space = server.getLevel(Space.DIMENSION);
        for (Entity entity : outside) {
            TransitTrip trip = nearest(data, entity.blockPosition());
            if (entity instanceof ServerPlayer player) {
                for (TransitTrip any : data.trips.values()) {
                    if (any.aboardMutable().remove(player.getUUID())) {
                        data.setDirty();
                    }
                }
            }
            if (trip == null || space == null) {
                if (entity instanceof ServerPlayer player) {
                    player.changeDimension(
                            player.findRespawnPositionAndUseSpawnBlock(true, DimensionTransition.DO_NOTHING));
                } else {
                    entity.discard();
                }
                continue;
            }
            BoundingBox box = trip.box();
            double x = trip.route().x(now) + entity.getX() - (box.minX() + box.maxX() + 1) / 2.0;
            double z = trip.route().z(now) + entity.getZ() - (box.minZ() + box.maxZ() + 1) / 2.0;
            double y = Math.max(space.getMinBuildHeight() + 1, Math.min(space.getMaxBuildHeight() - 2, entity.getY()));
            Entity moved = entity.changeDimension(new DimensionTransition(
                    space,
                    new Vec3(x, y, z),
                    entity.getDeltaMovement(),
                    entity.getYRot(),
                    entity.getXRot(),
                    DimensionTransition.DO_NOTHING));
            if (moved instanceof ServerPlayer player) {
                player.displayClientMessage(Component.translatable(STEPPED_OUTSIDE), true);
            }
        }
    }

    /** The trip whose box, grown by {@code grace} blocks, holds a position; null if none does. */
    @Nullable
    private static TransitTrip holding(TransitData data, BlockPos pos, int grace) {
        for (TransitTrip trip : data.trips.values()) {
            if (trip.box().inflatedBy(grace).isInside(pos)) {
                return trip;
            }
        }
        return null;
    }

    /** The trip whose slot a position is in: within half a pitch of its box. */
    @Nullable
    private static TransitTrip nearest(TransitData data, BlockPos pos) {
        for (TransitTrip trip : data.trips.values()) {
            if (trip.box()
                    .inflatedBy(trip.pitch() / 2)
                    .isInside(pos.getX(), trip.box().minY(), pos.getZ())) {
                return trip;
            }
        }
        return null;
    }

    // ---- Players --------------------------------------------------------------------------

    private static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            putBackAboard(player);
        }
    }

    /**
     * Where a player who was aboard a ship that arrived while they were away goes when they next log
     * in.
     *
     * @param server the server
     * @param player the player's id
     * @return the dimension the ship arrived in, or empty if the player has no return pending
     */
    public static Optional<ResourceKey<Level>> pendingReturn(MinecraftServer server, UUID player) {
        TransitData.Return back = data(server).returns.get(player);
        return back == null ? Optional.empty() : Optional.of(back.to());
    }

    /**
     * Puts a player who has just logged in back aboard their ship, if it arrived while they were
     * away: they are moved from where they are in the old slot to the same place on the ship where it
     * arrived. Called as each player logs in. A player in transit outside every ship otherwise is
     * sent to Space by the next look ({@link #sendOutsideToSpace}).
     *
     * @param player the player
     * @return true if the player was moved
     */
    public static boolean putBackAboard(ServerPlayer player) {
        TransitData data = data(player.getServer());
        TransitData.Return back = data.returns.remove(player.getUUID());
        if (back == null) {
            return false;
        }
        data.setDirty();
        if (!Transit.is(player.level())) {
            return false;
        }
        ServerLevel to = player.getServer().getLevel(back.to());
        if (to == null) {
            return false;
        }
        Vec3 there = player.position()
                .add(back.offset().getX(), back.offset().getY(), back.offset().getZ());
        player.teleportTo(to, there.x, there.y, there.z, player.getYRot(), player.getXRot());
        return true;
    }

    // ---- The dimension's rules ------------------------------------------------------------

    private static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !Transit.is(level)) {
            return;
        }
        if (holding(data(level.getServer()), event.getPos(), 0) == null) {
            event.setCanceled(true);
            if (event.getEntity() instanceof ServerPlayer player) {
                player.displayClientMessage(Component.translatable(NO_BUILDING), true);
            }
        }
    }

    private static void onPortalSpawn(BlockEvent.PortalSpawnEvent event) {
        LevelAccessor level = event.getLevel();
        if (level instanceof ServerLevel server && Transit.is(server)) {
            event.setCanceled(true);
        }
    }

    private static void onSpawnCheck(MobSpawnEvent.PositionCheck event) {
        MobSpawnType type = event.getSpawnType();
        boolean natural = type == MobSpawnType.NATURAL
                || type == MobSpawnType.CHUNK_GENERATION
                || type == MobSpawnType.PATROL
                || type == MobSpawnType.EVENT;
        if (natural && Transit.is(event.getLevel().getLevel())) {
            event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
        }
    }

    // ---- Helpers --------------------------------------------------------------------------

    /**
     * The server's transit data.
     *
     * @param server the server
     * @return the data, made empty if the world has none
     */
    static TransitData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TransitData.factory(), TransitData.NAME);
    }

    private static MoveResult refused(String code) {
        return new MoveResult(Optional.of(code), 0, 0, 0, 0, 0, 0, 0);
    }

    private static int longest(BoundingBox box) {
        return Math.max(box.getXSpan(), Math.max(box.getYSpan(), box.getZSpan()));
    }

    /** The chunks a box spans and those a block beyond it, as the mover checks them. */
    private static List<ChunkPos> chunks(BoundingBox box) {
        List<ChunkPos> chunks = new ArrayList<>();
        for (int x = (box.minX() - 1) >> 4; x <= (box.maxX() + 1) >> 4; x++) {
            for (int z = (box.minZ() - 1) >> 4; z <= (box.maxZ() + 1) >> 4; z++) {
                chunks.add(new ChunkPos(x, z));
            }
        }
        return chunks;
    }

    private static boolean loaded(ServerLevel level, BoundingBox box) {
        for (ChunkPos pos : chunks(box)) {
            if (!level.hasChunk(pos.x, pos.z) || !level.areEntitiesLoaded(pos.toLong())) {
                return false;
            }
        }
        return true;
    }

    private static void hold(TransitTrip trip, ServerLevel level, BoundingBox box) {
        for (ChunkPos pos : chunks(box)) {
            level.getChunkSource().addRegionTicket(HOLD, pos, 2, trip.id());
            trip.held.add(new TransitTrip.Held(level.dimension(), pos));
        }
        trip.holding = true;
        trip.server = level.getServer();
    }

    private static void release(TransitTrip trip, MinecraftServer server) {
        for (TransitTrip.Held held : trip.held) {
            ServerLevel level = server.getLevel(held.level());
            if (level != null) {
                level.getChunkSource().removeRegionTicket(HOLD, held.chunk(), 2, trip.id());
            }
        }
        trip.held.clear();
        trip.holding = false;
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Transits called off the server thread");
        }
    }
}
