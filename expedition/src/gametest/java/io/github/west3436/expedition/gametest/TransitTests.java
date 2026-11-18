package io.github.west3436.expedition.gametest;

import com.mojang.authlib.GameProfile;
import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.api.space.Space;
import io.github.west3436.expedition.api.space.Transit;
import io.github.west3436.expedition.mover.MoveResult;
import io.github.west3436.expedition.portal.PortalRules;
import io.github.west3436.expedition.transit.TransitRoute;
import io.github.west3436.expedition.transit.TransitTrip;
import io.github.west3436.expedition.transit.Transits;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The transit dimension and its slots (#25): ships in transit at once each have a slot of their
 * own, a slot is freed when its ship arrives and the next trip reuses it, a ship arrives on time
 * with nobody aboard and its slot unloaded, a player aboard goes with the ship both ways, one who
 * was away when it arrived is put back aboard when they return, and anything outside a ship drops
 * into Space.
 *
 * <p>Each ship is a 3-block cube with a stone floor, built far from the GameTest grid high in the
 * Overworld's air. Each test is in a batch of its own, so no two run at once, and each brings its
 * ships out of transit before it ends.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TransitTests {
    /**
     * Each test's limit in game ticks. A GameTest server ticks as fast as it can, and a far chunk's
     * entities load from disk in real time, which on a busy machine can take thousands of ticks.
     */
    private static final int TIMEOUT = 20_000;

    /** Keeps a test's areas loaded; lapses when the test would time out. */
    private static final TicketType<ChunkPos> HELD =
            TicketType.create("expedition_gametest_transit", Comparator.comparingLong(ChunkPos::toLong), TIMEOUT);

    /** A ship's side. */
    private static final int SIDE = 3;

    private TransitTests() {}

    /**
     * Two ships go into transit at once and take slots a pitch apart; the first arrives, freeing its
     * slot, and a third ship takes that slot.
     */
    @GameTest(template = "empty", batch = "transit_slots", timeoutTicks = TIMEOUT)
    public static void ships_take_separate_slots_and_a_freed_slot_is_reused(GameTestHelper h) {
        ServerLevel overworld = h.getLevel();
        MinecraftServer server = overworld.getServer();
        ServerLevel transit = transit(h);
        BlockPos a = area(0);
        BlockPos b = area(1);
        BlockPos d = destination(0);
        Run run = new Run();
        h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, overworld, box(a));
                    hold(h, overworld, box(b));
                    hold(h, overworld, box(d));
                })
                .thenExecute(() -> {
                    build(overworld, a);
                    build(overworld, b);
                    wipe(overworld, box(d));
                    run.first = Transits.depart(overworld, box(a), route(server));
                    run.second = Transits.depart(overworld, box(b), route(server));
                    h.assertTrue(run.first.slot() != run.second.slot(), "both ships took slot " + run.first.slot());
                    BlockPos p = run.first.slotOrigin();
                    BlockPos q = run.second.slotOrigin();
                    int apart = Math.max(Math.abs(p.getX() - q.getX()), Math.abs(p.getZ() - q.getZ()));
                    h.assertTrue(apart >= run.first.pitch(), "the slots are " + apart + " blocks apart");
                })
                .thenWaitUntil(() -> {
                    departed(h, run.first);
                    departed(h, run.second);
                })
                .thenExecute(() -> {
                    for (TransitTrip trip : List.of(run.first, run.second)) {
                        h.assertTrue(trip.inTransit(), trip + " isn't in transit");
                        assertShip(h, transit, corner(trip.box()));
                        h.assertTrue(
                                Transits.tripAt(server, corner(trip.box())).orElse(null) == trip,
                                "no trip holds " + trip + "'s box");
                    }
                    assertEmpty(h, overworld, box(a));
                    assertEmpty(h, overworld, box(b));
                    h.assertTrue(
                            Transits.trips(server).containsAll(List.of(run.first, run.second)),
                            "the trips under way are " + Transits.trips(server));
                    run.arrival = Transits.arrive(run.first, overworld, d, Transits.now(server));
                })
                .thenWaitUntil(() -> arrived(h, run.arrival))
                .thenExecute(() -> {
                    h.assertTrue(run.first.hasArrived(), run.first + " hasn't arrived");
                    assertShip(h, overworld, d);
                    assertEmpty(h, transit, run.first.box());
                    h.assertFalse(Transits.trips(server).contains(run.first), "the arrived trip is still listed");
                    h.assertTrue(
                            Transits.tripAt(server, corner(run.first.box())).isEmpty(), "the freed slot is still held");
                    run.third = Transits.depart(overworld, box(d), route(server));
                    h.assertValueEqual(run.third.slot(), run.first.slot(), "the slot the third ship took");
                })
                .thenWaitUntil(() -> departed(h, run.third))
                .thenExecute(() -> {
                    run.arrival = Transits.arrive(run.second, overworld, b, Transits.now(server));
                    run.other = Transits.arrive(run.third, overworld, d, Transits.now(server));
                })
                .thenWaitUntil(() -> {
                    arrived(h, run.arrival);
                    arrived(h, run.other);
                })
                .thenExecute(() -> {
                    assertShip(h, overworld, b);
                    assertShip(h, overworld, d);
                    for (BlockPos p : List.of(a, b, d)) {
                        wipe(overworld, box(p));
                        let(overworld, box(p));
                    }
                })
                .thenSucceed();
    }

    /**
     * A ship with a pig and nobody aboard goes into transit, its slot unloads, and it arrives at the
     * game time given, not before, with the pig.
     */
    @GameTest(template = "empty", batch = "transit_arrival", timeoutTicks = TIMEOUT)
    public static void a_ship_arrives_on_time_with_nobody_aboard(GameTestHelper h) {
        ServerLevel overworld = h.getLevel();
        MinecraftServer server = overworld.getServer();
        ServerLevel transit = transit(h);
        BlockPos a = area(2);
        BlockPos d = destination(2);
        Run run = new Run();
        h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, overworld, box(a));
                    hold(h, overworld, box(d));
                })
                .thenExecute(() -> {
                    build(overworld, a);
                    wipe(overworld, box(d));
                    run.pig = pig(h, overworld, Vec3.atBottomCenterOf(a.offset(1, 1, 1)));
                    run.first = Transits.depart(overworld, box(a), route(server));
                })
                .thenWaitUntil(() -> departed(h, run.first))
                .thenExecute(() -> h.assertTrue(run.first.aboard().isEmpty(), "players aboard: " + run.first.aboard()))
                .thenWaitUntil(() -> {
                    ChunkPos slot = new ChunkPos(corner(run.first.box()));
                    h.assertFalse(transit.hasChunk(slot.x, slot.z), "the slot is still loaded");
                })
                .thenExecute(() -> {
                    run.at = Transits.now(server) + 100;
                    run.arrival = Transits.arrive(run.first, overworld, d, run.at);
                    h.assertValueEqual(run.first.arrivesAt(), Optional.of(run.at), "when the trip arrives");
                    h.assertValueEqual(
                            run.first.destination(), Optional.of(Level.OVERWORLD), "where the trip is going");
                    run.arrival.whenComplete((r, e) -> run.doneAt = Transits.now(server));
                })
                .thenWaitUntil(() -> h.assertTrue(
                        Transits.now(server) >= run.at - 5,
                        "it is " + (run.at - Transits.now(server)) + " ticks early"))
                .thenExecute(() -> {
                    h.assertFalse(run.arrival.isDone(), "the ship arrived early");
                    h.assertTrue(run.first.inTransit(), "the ship left its slot early");
                })
                .thenWaitUntil(() -> arrived(h, run.arrival))
                .thenExecute(() -> {
                    h.assertTrue(run.doneAt >= run.at, "the ship arrived at " + run.doneAt + ", before " + run.at);
                    assertShip(h, overworld, d);
                    Entity pig = overworld.getEntity(run.pig);
                    h.assertTrue(
                            pig instanceof Pig && box(d).isInside(pig.blockPosition()),
                            "the pig didn't arrive with the ship: " + pig);
                    h.assertFalse(Transits.trips(server).contains(run.first), "the arrived trip is still listed");
                    pig.discard();
                    for (BlockPos p : List.of(a, d)) {
                        wipe(overworld, box(p));
                        let(overworld, box(p));
                    }
                })
                .thenSucceed();
    }

    /**
     * A player standing in a ship goes into transit with it, is aboard, is still aboard and in the
     * slot a while later, and goes out with the ship when it arrives.
     */
    @GameTest(template = "empty", batch = "transit_aboard", timeoutTicks = TIMEOUT)
    public static void a_player_aboard_goes_with_the_ship_both_ways(GameTestHelper h) {
        ServerLevel overworld = h.getLevel();
        MinecraftServer server = overworld.getServer();
        BlockPos a = area(3);
        BlockPos d = destination(3);
        Run run = new Run();
        h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, overworld, box(a));
                    hold(h, overworld, box(d));
                })
                .thenExecute(() -> {
                    build(overworld, a);
                    wipe(overworld, box(d));
                    run.player = player(overworld, UUID.randomUUID(), Vec3.atBottomCenterOf(a.offset(1, 1, 1)));
                    run.first = Transits.depart(overworld, box(a), route(server));
                })
                .thenWaitUntil(() -> departed(h, run.first))
                .thenExecute(() -> {
                    h.assertTrue(
                            run.first.aboard().equals(Set.of(run.player.getUUID())), "aboard: " + run.first.aboard());
                    assertIn(h, run.player, Transit.DIMENSION, run.first.box());
                })
                .thenIdle(3 * 10)
                .thenExecute(() -> {
                    assertIn(h, run.player, Transit.DIMENSION, run.first.box());
                    h.assertTrue(run.first.aboard().contains(run.player.getUUID()), "the player is no longer aboard");
                    run.arrival = Transits.arrive(run.first, overworld, d, Transits.now(server));
                })
                .thenWaitUntil(() -> arrived(h, run.arrival))
                .thenExecute(() -> {
                    assertIn(h, run.player, Level.OVERWORLD, box(d));
                    h.assertTrue(
                            Transits.pendingReturn(server, run.player.getUUID()).isEmpty(),
                            "a player who went with the ship has a return pending");
                    remove(run.player);
                    for (BlockPos p : List.of(a, d)) {
                        wipe(overworld, box(p));
                        let(overworld, box(p));
                    }
                })
                .thenSucceed();
    }

    /**
     * A player aboard logs out in transit; the ship arrives while they are away; when they log back
     * in, in the old slot, they are put back aboard where the ship arrived.
     */
    @GameTest(template = "empty", batch = "transit_return", timeoutTicks = TIMEOUT)
    public static void a_player_away_when_the_ship_arrives_is_put_back_aboard(GameTestHelper h) {
        ServerLevel overworld = h.getLevel();
        MinecraftServer server = overworld.getServer();
        ServerLevel transit = transit(h);
        BlockPos a = area(4);
        BlockPos d = destination(4);
        UUID id = UUID.randomUUID();
        Run run = new Run();
        h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, overworld, box(a));
                    hold(h, overworld, box(d));
                })
                .thenExecute(() -> {
                    build(overworld, a);
                    wipe(overworld, box(d));
                    run.player = player(overworld, id, Vec3.atBottomCenterOf(a.offset(1, 1, 1)));
                    run.first = Transits.depart(overworld, box(a), route(server));
                })
                .thenWaitUntil(() -> departed(h, run.first))
                .thenExecute(() -> {
                    h.assertTrue(run.first.aboard().contains(id), "the player isn't aboard");
                    run.left = run.player.position();
                    // Logs out.
                    remove(run.player);
                    run.arrival = Transits.arrive(run.first, overworld, d, Transits.now(server));
                })
                .thenWaitUntil(() -> arrived(h, run.arrival))
                .thenExecute(() -> {
                    assertShip(h, overworld, d);
                    h.assertValueEqual(
                            Transits.pendingReturn(server, id),
                            Optional.of(Level.OVERWORLD),
                            "where the player goes when they log in");
                    // Logs back in, where they logged out.
                    run.player = player(transit, id, run.left);
                    h.assertTrue(Transits.putBackAboard(run.player), "the player wasn't put back aboard");
                    assertIn(h, run.player, Level.OVERWORLD, box(d));
                    h.assertTrue(Transits.pendingReturn(server, id).isEmpty(), "the return is still pending");
                    h.assertFalse(Transits.putBackAboard(run.player), "the player was put back aboard twice");
                    remove(run.player);
                    for (BlockPos p : List.of(a, d)) {
                        wipe(overworld, box(p));
                        let(overworld, box(p));
                    }
                })
                .thenSucceed();
    }

    /**
     * A pig more than {@link Transits#GRACE} blocks outside a ship in transit drops into Space at the
     * point the ship has reached on its route, as far from it as it was from the ship's middle; a pig
     * inside stays, and no portal takes it anywhere.
     */
    @GameTest(template = "empty", batch = "transit_outside", timeoutTicks = TIMEOUT)
    public static void outside_the_ship_is_space(GameTestHelper h) {
        ServerLevel overworld = h.getLevel();
        MinecraftServer server = overworld.getServer();
        ServerLevel transit = transit(h);
        ServerLevel space = level(h, Space.DIMENSION);
        BlockPos a = area(5);
        BlockPos landing = new BlockPos(4_000 + 7, 1, 100);
        Run run = new Run();
        h.startSequence()
                .thenWaitUntil(() -> hold(h, overworld, box(a)))
                .thenExecute(() -> {
                    build(overworld, a);
                    long now = Transits.now(server);
                    // A route that stays put, so where the pig lands doesn't depend on the tick.
                    run.first = Transits.depart(overworld, box(a), new TransitRoute(4_000, 100, 4_000, 100, now, now));
                })
                .thenWaitUntil(() -> departed(h, run.first))
                .thenWaitUntil(() -> {
                    hold(h, transit, run.first.box().inflatedBy(8));
                    hold(h, space, new BoundingBox(landing));
                })
                .thenExecute(() -> {
                    BoundingBox box = run.first.box();
                    run.inside = pig(h, transit, new Vec3(box.minX() + 1.5, box.minY() + 1, box.minZ() + 1.5));
                    run.pig = pig(h, transit, new Vec3(box.maxX() + 6.5, box.minY() + 1, box.minZ() + 1.5));
                    Entity inside = transit.getEntity(run.inside);
                    h.assertTrue(
                            PortalRules.refuses(Blocks.NETHER_PORTAL, transit, inside),
                            "a Nether portal works in transit");
                    h.assertTrue(
                            PortalRules.refuses(Blocks.END_PORTAL, transit, inside), "an End portal works in transit");
                })
                .thenWaitUntil(() -> h.assertTrue(space.getEntity(run.pig) instanceof Pig, "the pig isn't in Space"))
                .thenExecute(() -> {
                    Entity pig = space.getEntity(run.pig);
                    h.assertTrue(
                            Math.abs(pig.getX() - landing.getX()) < 0.01
                                    && Math.abs(pig.getZ() - landing.getZ()) < 0.01,
                            "the pig landed at " + pig.position() + ", not " + landing);
                    h.assertTrue(transit.getEntity(run.pig) == null, "the pig is still in transit");
                    Entity inside = transit.getEntity(run.inside);
                    h.assertTrue(
                            inside instanceof Pig && !inside.isRemoved(), "the pig inside the ship left: " + inside);
                    pig.discard();
                    inside.discard();
                    run.arrival = Transits.arrive(run.first, overworld, a, Transits.now(server));
                })
                .thenWaitUntil(() -> arrived(h, run.arrival))
                .thenExecute(() -> {
                    wipe(overworld, box(a));
                    let(overworld, box(a));
                    let(transit, run.first.box().inflatedBy(8));
                    let(space, new BoundingBox(landing));
                })
                .thenSucceed();
    }

    /** A box taller than the transit dimension is refused at once, and takes no slot. */
    @GameTest(template = "empty", batch = "transit_too_tall")
    public static void a_box_too_tall_for_transit_is_refused(GameTestHelper h) {
        ServerLevel overworld = h.getLevel();
        MinecraftServer server = overworld.getServer();
        ServerLevel transit = transit(h);
        BlockPos a = area(6);
        int height = transit.getMaxBuildHeight() - transit.getMinBuildHeight() + 1;
        BoundingBox tall = new BoundingBox(
                a.getX(),
                overworld.getMinBuildHeight(),
                a.getZ(),
                a.getX() + SIDE - 1,
                overworld.getMinBuildHeight() + height - 1,
                a.getZ() + SIDE - 1);
        TransitTrip trip = Transits.depart(overworld, tall, route(server));
        h.assertTrue(trip.departure().isDone(), "the departure of a box too tall is still going");
        h.assertValueEqual(
                trip.departure().join().refusal(), Optional.of(Transits.TOO_TALL), "why the box was refused");
        h.assertTrue(trip.hasArrived(), "the refused trip isn't over");
        h.assertFalse(Transits.trips(server).contains(trip), "the refused trip holds a slot");
        h.succeed();
    }

    // ---- Helpers ------------------------------------------------------------------------

    private static final class Run {
        TransitTrip first;
        TransitTrip second;
        TransitTrip third;
        CompletableFuture<MoveResult> arrival;
        CompletableFuture<MoveResult> other;
        UUID pig;
        UUID inside;
        ServerPlayer player;
        Vec3 left;
        long at;
        long doneAt;
    }

    private static BlockPos area(int index) {
        return new BlockPos(24_576 + 512 * index, 200, 24_576);
    }

    private static BlockPos destination(int index) {
        return area(index).offset(0, 0, 256);
    }

    private static BoundingBox box(BlockPos corner) {
        return BoundingBox.fromCorners(corner, corner.offset(SIDE - 1, SIDE - 1, SIDE - 1));
    }

    private static BlockPos corner(BoundingBox box) {
        return new BlockPos(box.minX(), box.minY(), box.minZ());
    }

    private static TransitRoute route(MinecraftServer server) {
        long now = Transits.now(server);
        return new TransitRoute(0, 0, 0, -7_120, now, now + 1_200);
    }

    private static ServerLevel transit(GameTestHelper h) {
        return level(h, Transit.DIMENSION);
    }

    private static ServerLevel level(GameTestHelper h, ResourceKey<Level> dimension) {
        ServerLevel level = h.getLevel().getServer().getLevel(dimension);
        if (level == null) {
            throw new GameTestAssertException("no " + dimension.location());
        }
        return level;
    }

    /** Holds the chunks around a box loaded, failing until they and their entities are. */
    private static void hold(GameTestHelper h, ServerLevel level, BoundingBox box) {
        for (int x = (box.minX() - 1) >> 4; x <= (box.maxX() + 1) >> 4; x++) {
            for (int z = (box.minZ() - 1) >> 4; z <= (box.maxZ() + 1) >> 4; z++) {
                ChunkPos c = new ChunkPos(x, z);
                level.getChunkSource().addRegionTicket(HELD, c, 2, c);
                level.getChunk(x, z);
                h.assertTrue(
                        level.areEntitiesLoaded(c.toLong()),
                        "entities aren't loaded yet in " + c + " of "
                                + level.dimension().location());
            }
        }
    }

    /** Lets go of the chunks {@link #hold} held. */
    private static void let(ServerLevel level, BoundingBox box) {
        for (int x = (box.minX() - 1) >> 4; x <= (box.maxX() + 1) >> 4; x++) {
            for (int z = (box.minZ() - 1) >> 4; z <= (box.maxZ() + 1) >> 4; z++) {
                ChunkPos c = new ChunkPos(x, z);
                level.getChunkSource().removeRegionTicket(HELD, c, 2, c);
            }
        }
    }

    /** A ship: a stone floor with air above it. */
    private static void build(ServerLevel level, BlockPos corner) {
        wipe(level, box(corner));
        for (int x = 0; x < SIDE; x++) {
            for (int z = 0; z < SIDE; z++) {
                level.setBlock(corner.offset(x, 0, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    private static void wipe(ServerLevel level, BoundingBox box) {
        for (BlockPos p :
                BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    private static void assertShip(GameTestHelper h, ServerLevel level, BlockPos corner) {
        for (int x = 0; x < SIDE; x++) {
            for (int z = 0; z < SIDE; z++) {
                BlockPos p = corner.offset(x, 0, z);
                h.assertTrue(
                        level.getBlockState(p).is(Blocks.STONE),
                        "no floor at " + p + " of " + level.dimension().location() + ": " + level.getBlockState(p));
            }
        }
    }

    private static void assertEmpty(GameTestHelper h, ServerLevel level, BoundingBox box) {
        for (BlockPos p :
                BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            h.assertTrue(
                    level.getBlockState(p).isAir(),
                    level.getBlockState(p) + " was left at " + p + " of "
                            + level.dimension().location());
        }
    }

    private static void departed(GameTestHelper h, TransitTrip trip) {
        h.assertTrue(trip.departure().isDone(), "the departure of " + trip + " is still going");
        MoveResult result = trip.departure().join();
        if (!result.moved()) {
            throw new GameTestAssertException(trip + "'s departure was refused: " + result.refusal());
        }
    }

    private static void arrived(GameTestHelper h, CompletableFuture<MoveResult> arrival) {
        h.assertTrue(arrival.isDone(), "the arrival is still going");
        MoveResult result = arrival.join();
        if (!result.moved()) {
            throw new GameTestAssertException("the arrival was refused: " + result.refusal());
        }
    }

    private static void assertIn(GameTestHelper h, ServerPlayer player, ResourceKey<Level> level, BoundingBox box) {
        h.assertTrue(
                player.level().dimension() == level && box.isInside(player.blockPosition()) && !player.isRemoved(),
                "the player is at " + player.blockPosition() + " of "
                        + player.level().dimension().location() + ", not in " + box + " of " + level.location());
    }

    private static UUID pig(GameTestHelper h, ServerLevel level, Vec3 at) {
        Pig pig = EntityType.PIG.create(level);
        if (pig == null) {
            throw new GameTestAssertException("no pig");
        }
        pig.moveTo(at);
        pig.setNoAi(true);
        pig.setPersistenceRequired();
        h.assertTrue(level.addFreshEntity(pig), "the pig wasn't added");
        return pig.getUUID();
    }

    /**
     * A test player on a connection to nowhere, added to a level but not to the player list, so no
     * other test sees it and nothing negotiates the connection's channels.
     */
    private static ServerPlayer player(ServerLevel level, UUID id, Vec3 at) {
        MinecraftServer server = level.getServer();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(id, "transit-test"), false);
        ServerPlayer player = new ServerPlayer(server, level, cookie.gameProfile(), ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        new ServerGamePacketListenerImpl(server, connection, player, cookie);
        player.moveTo(at.x, at.y, at.z, 0, 0);
        level.addNewPlayer(player);
        return player;
    }

    private static void remove(ServerPlayer player) {
        player.serverLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
    }
}
