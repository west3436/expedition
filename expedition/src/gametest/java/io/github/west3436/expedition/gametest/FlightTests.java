package io.github.west3436.expedition.gametest;

import com.mojang.authlib.GameProfile;
import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.api.space.Space;
import io.github.west3436.expedition.flight.Flight;
import io.github.west3436.expedition.flight.FlightOrder;
import io.github.west3436.expedition.flight.FlightPlan;
import io.github.west3436.expedition.flight.FlightRefusal;
import io.github.west3436.expedition.flight.Flights;
import io.github.west3436.expedition.mover.MoveRefusal;
import io.github.west3436.expedition.mover.QuietBlocks;
import io.github.west3436.expedition.registry.ExpeditionBlocks;
import io.github.west3436.expedition.ship.ShipBox;
import io.github.west3436.expedition.ship.ShipComputers;
import io.github.west3436.expedition.ship.ShuttleComputerBlockEntity;
import io.github.west3436.zas.api.atmosphere.Atmospheres;
import io.github.west3436.zas.api.atmosphere.ZoneInfo;
import io.github.west3436.zas.api.gas.GasParcel;
import io.github.west3436.zas.api.mutation.GasMutations;
import io.github.west3436.zas.api.mutation.MutationOutcome;
import io.github.west3436.zas.api.mutation.MutationResult;
import io.github.west3436.zas.api.mutation.SourceId;
import io.github.west3436.zas.api.relocation.Relocations;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Flights (#29), and the M4 gate: a ship with a sealed cabin, a Shuttle Computer, a gas thruster and
 * a tank takes off from the Overworld, crosses in transit, lands on the Moon, flies back and lands
 * in the Overworld, and its cabin holds the same air at the end as at the start.
 *
 * <p>The ship's cabin is a hollow cube of Ship Hull, 5 blocks a side, whose 27 cells of air no heat
 * crosses; the computer, the thruster and the tank stand on its roof, so the box is 5 × 6 × 5. The
 * tanks are {@link TestGasTanks}, which keep their gas by position, so a ship that has moved is
 * refuelled where it is before it flies on.
 *
 * <p>Trips are kept short: the GameTests' flight settings scale the thrust model's time by {@value
 * #TIME_SCALE} and warm up for a second, so the round trip takes a few hundred ticks.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FlightTests {
    /** The gate's batch. */
    public static final String GATE = "m4_gate";

    private static final String BATCH = "flights";

    /** How much of the model's time the GameTests' trips take. */
    static final double TIME_SCALE = 0.002;

    /** As the other far tests: a far chunk's entities load in real time, which can be slow. */
    private static final int TIMEOUT = 20_000;

    private static final TicketType<ChunkPos> HELD =
            TicketType.create("expedition_gametest_flight", Comparator.comparingLong(ChunkPos::toLong), TIMEOUT);

    private static final SourceId SOURCE = SourceId.of(ExpeditionMod.MOD_ID, "gametest/flight");
    private static final ResourceLocation CO2 = ResourceLocation.fromNamespaceAndPath("zas", "carbon_dioxide");
    private static final ResourceLocation H2 = ResourceLocation.fromNamespaceAndPath("zas", "hydrogen");
    private static final ResourceLocation O2 = ResourceLocation.fromNamespaceAndPath("zas", "oxygen");

    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("expedition", "overworld");
    private static final ResourceLocation MOON = ResourceLocation.fromNamespaceAndPath("expedition", "moon");
    private static final ResourceKey<Level> MOON_LEVEL = ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath("expedition", "moon"));

    /** The cabin's side, and its cells of air. */
    private static final int SIDE = 5;

    private static final int CELLS = 27;

    private static final BlockPos COMPUTER = new BlockPos(2, SIDE, 2);
    private static final BlockPos THRUSTER = COMPUTER.south();
    private static final BlockPos TANK = THRUSTER.east();

    private static final GameProfile PILOT =
            new GameProfile(UUID.fromString("6f1c3a52-31a0-4c5e-9d0e-31a7c1a1e290"), "expedition_flight_pilot");
    private static final GameProfile STRANGER =
            new GameProfile(UUID.fromString("6f1c3a52-31a0-4c5e-9d0e-31a7c1a1e291"), "expedition_flight_stranger");

    static {
        Flights.setSettings(() -> new Flights.Settings(TIME_SCALE, 4, 20));
    }

    private FlightTests() {}

    // ---- The M4 gate --------------------------------------------------------------------

    /**
     * The M4 gate: the ship flies from the Overworld to the Moon, landing on a pad there, and back to
     * the Overworld's ground where it started. Its cabin arrives each time with the same cells, the
     * same moles of each gas and the same temperature, sealed.
     */
    @GameTest(template = "empty", batch = GATE, timeoutTicks = TIMEOUT)
    public static void a_ship_flies_to_the_moon_and_back_and_keeps_its_air(GameTestHelper h) {
        ServerLevel overworld = h.getLevel();
        ServerLevel moon = level(h, MOON_LEVEL);
        BlockPos a = area(0);
        // The Moon's pad, 13 blocks a side at y 120, above every hill; the ship's middle lands on 256, 256.
        BlockPos pad = new BlockPos(250, 120, 250);
        Run run = new Run();
        h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, overworld, box(a));
                    hold(h, moon, new BoundingBox(250, 0, 250, 262, 0, 262));
                })
                .thenExecute(() -> {
                    force(moon, new BoundingBox(250, 0, 250, 262, 0, 262), true);
                    for (BlockPos p : BlockPos.betweenClosed(pad, pad.offset(12, 0, 12))) {
                        moon.setBlock(p, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                    wipe(moon, new BoundingBox(250, 121, 250, 262, 130, 262));
                    build(overworld, a);
                    run.fill = seed(overworld, a);
                })
                .thenWaitUntil(() -> run.before = sealed(h, overworld, a, run))
                .thenExecute(() -> {
                    ShuttleComputerBlockEntity computer = computer(h, overworld, a);
                    FlightPlan plan = Flights.plan(overworld, computer, FlightOrder.land(MOON, 256, 256));
                    h.assertTrue(plan.feasible(), "the flight to the Moon is refused: " + plan.refusal());
                    h.assertValueEqual(plan.origin(), new BlockPos(254, 121, 254), "where the ship lands on the Moon");
                    h.assertTrue(plan.legs().get().liftOff() >= 256, "no lift-off leg: " + plan.legs());
                    h.assertTrue(plan.legs().get().landing() >= 256, "no landing leg: " + plan.legs());
                    run.flight =
                            Flights.launch(overworld, computer, FlightOrder.land(MOON, 256, 256), pilot(overworld));
                    h.assertFalse(run.flight.result().isDone(), "the launch was refused: " + run.flight.result());
                    h.assertValueEqual(run.flight.state(), Flight.State.COUNTDOWN, "the flight's state");
                })
                .thenWaitUntil(() -> landed(h, run.flight))
                .thenWaitUntil(() -> settled(h, moon, run.flight))
                .thenExecute(() -> {
                    Flight.Result result = run.flight.result().join();
                    h.assertValueEqual(result.level(), Optional.of(MOON_LEVEL), "where the ship arrived");
                    BlockPos b = result.origin().orElseThrow();
                    assertShip(h, moon, b);
                    h.assertTrue(overworld.getBlockState(a).isAir(), "the ship is still in the Overworld");
                    sameAir(h, run.before, zone(h, moon, b), "on the Moon");
                    run.moonCorner = b;
                    // The test tank keeps its gas by position: refuel the moved tank for the way back.
                    TestGasTanks.clear(a.offset(TANK));
                    TestGasTanks.fill(b.offset(TANK), Map.of(H2, 100.0, O2, 60.0));
                    ShuttleComputerBlockEntity computer = computer(h, moon, b);
                    int x = a.getX() + SIDE / 2;
                    int z = a.getZ() + SIDE / 2;
                    run.flight = Flights.launch(moon, computer, FlightOrder.land(OVERWORLD, x, z), pilot(overworld));
                    h.assertFalse(run.flight.result().isDone(), "the launch home was refused: " + run.flight.result());
                })
                .thenWaitUntil(() -> landed(h, run.flight))
                .thenWaitUntil(() -> settled(h, overworld, run.flight))
                .thenExecute(() -> {
                    Flight.Result result = run.flight.result().join();
                    h.assertValueEqual(result.level(), Optional.of(Level.OVERWORLD), "where the ship arrived home");
                    BlockPos c = result.origin().orElseThrow();
                    h.assertTrue(
                            c.getX() == a.getX() && c.getZ() == a.getZ(),
                            "the ship landed at " + c + ", not over " + a);
                    h.assertTrue(c.getY() < a.getY(), "the ship landed at " + c + ", not on the ground");
                    h.assertTrue(
                            overworld.getBlockState(c.below()).blocksMotion(), "the ship isn't on the ground at " + c);
                    assertShip(h, overworld, c);
                    h.assertTrue(moon.getBlockState(run.moonCorner).isAir(), "the ship is still on the Moon");
                    sameAir(h, run.before, zone(h, overworld, c), "home again");
                    TestGasTanks.clear(run.moonCorner.offset(TANK));
                    TestGasTanks.clear(c.offset(TANK));
                    wipe(overworld, box(c));
                    wipe(moon, new BoundingBox(250, 120, 250, 262, 130, 262));
                    force(moon, new BoundingBox(250, 0, 250, 262, 0, 262), false);
                })
                .thenSucceed();
    }

    // ---- The rules ----------------------------------------------------------------------

    /**
     * Anyone may plot, but only the computer's owner or an operator may launch: a stranger is refused
     * as not_owner, and nothing counts down.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void only_the_owner_or_an_operator_may_launch(GameTestHelper h) {
        ServerLevel overworld = h.getLevel();
        BlockPos a = area(1);
        h.startSequence()
                .thenWaitUntil(() -> hold(h, overworld, box(a)))
                .thenExecute(() -> {
                    build(overworld, a);
                    ShuttleComputerBlockEntity computer = computer(h, overworld, a);
                    ServerPlayer stranger = FakePlayerFactory.get(overworld, STRANGER);
                    FlightOrder order = FlightOrder.orbit(OVERWORLD, a.getX(), a.getZ());
                    h.assertTrue(Flights.plan(overworld, computer, order).feasible(), "a stranger can't plot");
                    Flight flight = Flights.launch(overworld, computer, order, stranger);
                    h.assertTrue(
                            flight.result().isDone()
                                    && flight.result().join().refusal().equals(Optional.of(FlightRefusal.NOT_OWNER)),
                            "a stranger's launch gave " + flight.result());
                    h.assertFalse(
                            Flights.launching(overworld, computer.getBlockPos()), "a stranger's launch counts down");
                    h.assertTrue(Flights.mayLaunch(computer, pilot(overworld)), "the owner may not launch");
                    PlayerList players = overworld.getServer().getPlayerList();
                    players.op(STRANGER);
                    try {
                        h.assertTrue(Flights.mayLaunch(computer, stranger), "an operator may not launch");
                    } finally {
                        players.deop(STRANGER);
                    }
                    TestGasTanks.clear(a.offset(TANK));
                    wipe(overworld, box(a));
                })
                .thenSucceed();
    }

    /**
     * A landing over ground that varies by more than 4 blocks is refused as too_uneven, and one over
     * ground that varies by 4 is allowed.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void a_landing_on_uneven_ground_is_refused(GameTestHelper h) {
        ServerLevel overworld = h.getLevel();
        BlockPos a = area(2);
        int x = a.getX() + 64 + SIDE / 2;
        int z = a.getZ() + SIDE / 2;
        BoundingBox site = new BoundingBox(x - 2, overworld.getMinBuildHeight(), z - 2, x + 2, a.getY(), z + 2);
        h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, overworld, box(a));
                    hold(h, overworld, site);
                })
                .thenExecute(() -> {
                    build(overworld, a);
                    ShuttleComputerBlockEntity computer = computer(h, overworld, a);
                    FlightOrder order = FlightOrder.land(OVERWORLD, x, z);
                    int ground =
                            overworld.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, x, z)
                                    - 1;
                    BlockPos corner = new BlockPos(x - 2, ground + 1, z - 2);
                    for (int i = 0; i < 5; i++) {
                        overworld.setBlock(corner.above(i), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                    FlightPlan rough = Flights.plan(overworld, computer, order);
                    h.assertValueEqual(rough.refusal(), Optional.of(FlightRefusal.TOO_UNEVEN), "a 5-block pillar");
                    overworld.setBlock(corner.above(4), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    FlightPlan fine = Flights.plan(overworld, computer, order);
                    h.assertTrue(fine.feasible(), "a 4-block pillar is refused: " + fine.refusal());
                    h.assertValueEqual(fine.origin().getY(), ground + 5, "the ship rests on the highest ground");
                    for (int i = 0; i < 4; i++) {
                        overworld.setBlock(corner.above(i), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                    TestGasTanks.clear(a.offset(TANK));
                    wipe(overworld, box(a));
                })
                .thenSucceed();
    }

    /**
     * The landing site is reserved at departure: another ship can't plan to land there. When the
     * trip ends with the site blocked, the ship arrives in orbit above it instead, in Space, and the
     * flight says why.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void a_blocked_landing_site_sends_the_ship_into_orbit(GameTestHelper h) {
        ServerLevel overworld = h.getLevel();
        ServerLevel space = level(h, Space.DIMENSION);
        BlockPos a = area(3);
        BlockPos other = area(4);
        int x = a.getX() + 64 + SIDE / 2;
        int z = a.getZ() + SIDE / 2;
        BoundingBox site = new BoundingBox(x - 2, overworld.getMinBuildHeight(), z - 2, x + 2, a.getY(), z + 2);
        Run run = new Run();
        h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, overworld, box(a));
                    hold(h, overworld, box(other));
                    hold(h, overworld, site);
                })
                .thenExecute(() -> {
                    build(overworld, a);
                    build(overworld, other);
                    run.flight = Flights.launch(
                            overworld, computer(h, overworld, a), FlightOrder.land(OVERWORLD, x, z), pilot(overworld));
                    h.assertFalse(run.flight.result().isDone(), "the launch was refused: " + run.flight.result());
                })
                .thenWaitUntil(() -> h.assertTrue(
                        run.flight.state() == Flight.State.IN_TRANSIT, "the flight is " + run.flight.state()))
                .thenExecute(() -> {
                    FlightPlan second =
                            Flights.plan(overworld, computer(h, overworld, other), FlightOrder.land(OVERWORLD, x, z));
                    h.assertValueEqual(second.refusal(), Optional.of(FlightRefusal.RESERVED), "a second ship's plan");
                    BlockPos in = run.flight.plan().origin().offset(2, 1, 2);
                    overworld.setBlock(in, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    run.stone = in;
                })
                .thenWaitUntil(() -> landed(h, run.flight))
                .thenExecute(() -> {
                    Flight.Result result = run.flight.result().join();
                    h.assertValueEqual(result.level(), Optional.of(Space.DIMENSION), "where the ship arrived");
                    h.assertValueEqual(result.landingRefusal(), Optional.of(MoveRefusal.BLOCKED), "why it didn't land");
                    BlockPos b = result.origin().orElseThrow();
                    h.assertValueEqual(b.getY(), Flights.ORBIT_Y, "the orbit's height");
                    assertShip(h, space, b);
                    overworld.setBlock(run.stone, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    TestGasTanks.clear(a.offset(TANK));
                    TestGasTanks.clear(other.offset(TANK));
                    wipe(space, box(b));
                    wipe(overworld, box(other));
                })
                .thenSucceed();
    }

    // ---- The ship -----------------------------------------------------------------------

    private static final class Run {
        CompletableFuture<MutationResult> fill;
        ZoneInfo before;
        Flight flight;
        BlockPos moonCorner;
        BlockPos stone;
    }

    /** A test's area: chunk-aligned, inside the Overworld's square and far from the other tests'. */
    private static BlockPos area(int index) {
        return new BlockPos(-3_584 + 1_024 * index, 100, 3_584);
    }

    /** The ship's box, with its cabin's lowest corner at {@code corner}. */
    private static BoundingBox box(BlockPos corner) {
        return BoundingBox.fromCorners(corner, corner.offset(SIDE - 1, SIDE, SIDE - 1));
    }

    /** The pilot, who owns every computer the tests build. */
    private static ServerPlayer pilot(ServerLevel level) {
        return FakePlayerFactory.get(level, PILOT);
    }

    /**
     * Builds the ship: the cabin of Ship Hull, and on its roof the computer facing north, the thruster
     * south of it with its nozzle pointing south, and the tank east of the thruster, full.
     */
    private static void build(ServerLevel level, BlockPos corner) {
        wipe(level, box(corner));
        BlockState hull = ExpeditionBlocks.SHIP_HULL.get().defaultBlockState();
        for (int x = 0; x < SIDE; x++) {
            for (int y = 0; y < SIDE; y++) {
                for (int z = 0; z < SIDE; z++) {
                    boolean wall = x == 0 || y == 0 || z == 0 || x == SIDE - 1 || y == SIDE - 1 || z == SIDE - 1;
                    if (wall) {
                        level.setBlock(corner.offset(x, y, z), hull, Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        level.setBlock(
                corner.offset(COMPUTER),
                ExpeditionBlocks.SHUTTLE_COMPUTER
                        .get()
                        .defaultBlockState()
                        .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH),
                Block.UPDATE_ALL);
        level.setBlock(
                corner.offset(THRUSTER),
                ExpeditionBlocks.GAS_THRUSTER
                        .get()
                        .defaultBlockState()
                        .setValue(DirectionalBlock.FACING, Direction.SOUTH),
                Block.UPDATE_ALL);
        level.setBlock(corner.offset(TANK), TestGasTanks.TANK.defaultBlockState(), Block.UPDATE_ALL);
        TestGasTanks.fill(corner.offset(TANK), Map.of(H2, 100.0, O2, 60.0));
        if (level.getBlockEntity(corner.offset(COMPUTER)) instanceof ShuttleComputerBlockEntity computer) {
            computer.setOwner(PILOT.getId(), PILOT.getName());
            ShipComputers.setBox(computer, ShipBox.fromWorld(box(corner), computer.getBlockPos(), computer.facing()));
        }
    }

    private static ShuttleComputerBlockEntity computer(GameTestHelper h, ServerLevel level, BlockPos corner) {
        if (level.getBlockEntity(corner.offset(COMPUTER)) instanceof ShuttleComputerBlockEntity computer) {
            h.assertValueEqual(computer.worldBox(), box(corner), "the computer's box");
            return computer;
        }
        throw new GameTestAssertException("no computer at " + corner.offset(COMPUTER) + " in " + level.dimension());
    }

    /** The ship is at {@code corner}: its hull, computer, thruster and tank. */
    private static void assertShip(GameTestHelper h, ServerLevel level, BlockPos corner) {
        h.assertTrue(
                level.getBlockState(corner).is(ExpeditionBlocks.SHIP_HULL.get()),
                "no hull at " + corner + " in " + level.dimension().location() + ": " + level.getBlockState(corner));
        h.assertTrue(
                level.getBlockState(corner.offset(THRUSTER)).is(ExpeditionBlocks.GAS_THRUSTER.get()),
                "no thruster at " + corner.offset(THRUSTER));
        h.assertTrue(level.getBlockState(corner.offset(TANK)).is(TestGasTanks.TANK), "no tank");
        computer(h, level, corner);
    }

    // ---- The cabin's air ----------------------------------------------------------------

    private static BlockPos middle(BlockPos corner) {
        return corner.offset(SIDE / 2, SIDE / 2, SIDE / 2);
    }

    /** A little carbon dioxide, which makes the cabin a zone if it isn't one yet. */
    private static CompletableFuture<MutationResult> seed(ServerLevel level, BlockPos corner) {
        return GasMutations.add(
                level, Vec3.atCenterOf(middle(corner)), new GasParcel(Map.of(CO2, 0.5), 288.15), SOURCE);
    }

    /** The cabin's zone once it is sealed, its 27 cells and the carbon dioxide in it; throws until then. */
    private static ZoneInfo sealed(GameTestHelper h, ServerLevel level, BlockPos corner, Run run) {
        h.assertTrue(run.fill.isDone(), "the gas hasn't landed");
        if (run.fill.join().outcome() != MutationOutcome.ZONE) {
            run.fill = seed(level, corner);
            throw new GameTestAssertException("the cabin isn't a zone yet");
        }
        ZoneInfo zone = zone(h, level, corner);
        h.assertTrue(zone.cellCount() == CELLS, "the cabin has " + zone.cellCount() + " cells");
        h.assertTrue(
                zone.mixture().moles(CO2) >= 0.5,
                "the cabin's mix is " + zone.mixture().moles());
        return zone;
    }

    private static ZoneInfo zone(GameTestHelper h, ServerLevel level, BlockPos corner) {
        ZoneInfo zone =
                Atmospheres.zoneAt(level, Vec3.atCenterOf(middle(corner))).orElse(null);
        h.assertTrue(
                zone != null && zone.enclosed() && !zone.paused(),
                "the cabin in " + level.dimension().location() + " isn't sealed: " + zone);
        return zone;
    }

    /** The cabin holds what it held: its cells, each gas's moles and its temperature. */
    private static void sameAir(GameTestHelper h, ZoneInfo before, ZoneInfo after, String where) {
        h.assertTrue(after.cellCount() == before.cellCount(), where + ": " + after.cellCount() + " cells");
        Map<ResourceLocation, Double> was = before.mixture().moles();
        Map<ResourceLocation, Double> is = after.mixture().moles();
        h.assertTrue(is.keySet().equals(was.keySet()), where + ": the gases are " + is.keySet() + ", not " + was);
        was.forEach((gas, n) -> {
            double m = is.get(gas);
            h.assertTrue(Math.abs(m - n) <= 1e-9 * Math.max(1, n), where + ": " + gas + " was " + n + " mol, is " + m);
        });
        double t0 = before.mixture().temperature();
        double t1 = after.mixture().temperature();
        h.assertTrue(Math.abs(t1 - t0) <= 1e-9 * t0, where + ": the temperature was " + t0 + " K, is " + t1);
    }

    /** Throws until the cabin's air has arrived with the ship and is a sealed zone again. */
    private static void settled(GameTestHelper h, ServerLevel level, Flight flight) {
        BlockPos corner = flight.result().join().origin().orElseThrow();
        h.assertFalse(Relocations.isMoving(level, middle(corner)), "the cabin's air hasn't arrived");
        zone(h, level, corner);
    }

    /** Throws until a flight is over, and then unless the ship arrived. */
    private static void landed(GameTestHelper h, Flight flight) {
        h.assertTrue(flight.result().isDone(), "the flight is " + flight.state());
        Flight.Result result = flight.result().join();
        h.assertTrue(result.arrived(), "the flight ended " + result);
    }

    // ---- Helpers ------------------------------------------------------------------------

    private static ServerLevel level(GameTestHelper h, ResourceKey<Level> dimension) {
        ServerLevel level = h.getLevel().getServer().getLevel(dimension);
        if (level == null) {
            throw new GameTestAssertException("no " + dimension.location());
        }
        return level;
    }

    /** Loads the chunks around a box and keeps them loaded; throws until their entities have loaded. */
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

    /** Force-loads a box's chunks, or lets them go, so a level nobody is in keeps ticking its air. */
    private static void force(ServerLevel level, BoundingBox box, boolean forced) {
        for (int x = (box.minX() - 1) >> 4; x <= (box.maxX() + 1) >> 4; x++) {
            for (int z = (box.minZ() - 1) >> 4; z <= (box.maxZ() + 1) >> 4; z++) {
                level.setChunkForced(x, z, forced);
            }
        }
    }

    /** Takes every block out of a box, quietly. */
    private static void wipe(ServerLevel level, BoundingBox box) {
        for (BlockPos p :
                BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            LevelChunk chunk = level.getChunkAt(p);
            QuietBlocks.takeBlockEntity(level, chunk, p);
            QuietBlocks.set(level, chunk, p, Blocks.AIR.defaultBlockState());
        }
    }
}
