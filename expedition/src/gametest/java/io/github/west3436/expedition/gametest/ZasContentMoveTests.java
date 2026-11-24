package io.github.west3436.expedition.gametest;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.mover.MoveJob;
import io.github.west3436.expedition.mover.MoveRequest;
import io.github.west3436.expedition.mover.MoveResult;
import io.github.west3436.expedition.mover.MoveSettings;
import io.github.west3436.expedition.mover.QuietBlocks;
import io.github.west3436.expedition.mover.RelocationHandover;
import io.github.west3436.expedition.mover.ShipMover;
import io.github.west3436.expedition.mover.ZasRelocationHandover;
import io.github.west3436.ss13.api.label.Labelled;
import io.github.west3436.ss13.device.DeviceTicker;
import io.github.west3436.zas.api.gas.GasParcel;
import io.github.west3436.zas.api.gas.StandardAir;
import io.github.west3436.zas.api.relocation.RelocationOutcome;
import io.github.west3436.zas.api.relocation.RelocationResult;
import io.github.west3436.zas.api.relocation.Relocations;
import io.github.west3436.zas.atmos.ZasAtmosMod;
import io.github.west3436.zas.atmos.airlock.AirSensorBlockEntity;
import io.github.west3436.zas.atmos.airlock.AirlockBlock;
import io.github.west3436.zas.atmos.airlock.AirlockBlockEntity;
import io.github.west3436.zas.atmos.airlock.AirlockControllerBlockEntity;
import io.github.west3436.zas.atmos.canister.CanisterBlock;
import io.github.west3436.zas.atmos.canister.CanisterBlockEntity;
import io.github.west3436.zas.atmos.controller.AreaAirControllerBlockEntity;
import io.github.west3436.zas.atmos.device.AtmosDevice;
import io.github.west3436.zas.atmos.pipe.PipeCapBlock;
import io.github.west3436.zas.atmos.pipe.PipeNetwork;
import io.github.west3436.zas.atmos.pipe.PipeNodeEntity;
import io.github.west3436.zas.atmos.registry.AtmosBlocks;
import io.github.west3436.zas.atmos.scrubber.ScrubberBlock;
import io.github.west3436.zas.atmos.scrubber.ScrubberBlockEntity;
import io.github.west3436.zas.atmos.vent.VentBlock;
import io.github.west3436.zas.atmos.vent.VentBlockEntity;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * ZAS³'s own blocks through a move (#10): a capped pipe run holding gas, a canister, a vent and a
 * scrubber linked to an area air controller, and an airlock, an air sensor and a vent linked to an
 * airlock controller. Each ship is a deck in the open air, so no room forms around it and a
 * controller reaches its devices through its links alone. It moves 100 blocks east within the
 * Overworld, then into the Nether.
 *
 * <p>Each ship moves twice over, once with each hand-over:
 *
 * <ul>
 *   <li><b>None</b> ({@link RelocationHandover#NONE}, the mover's default before #11). Gas, block
 *       states and saved data all arrive, but a controller's links are absolute positions, and
 *       nothing tells it the blocks moved: it arrives naming the old place, and drops each link at
 *       its next step, since the old place is now air. Its devices arrive unlinked. The tests below
 *       assert exactly that, so they pass while recording it; each leg links the devices again
 *       before it moves.
 *   <li><b>ZAS³'s relocation</b> ({@link ZasRelocationHandover}, the mover's default since #11, over
 *       {@link Relocations}). Its {@code
 *       complete} fires {@code BlocksMovedEvent}, and ZAS³'s {@code MovedDevices} re-bases every
 *       link inside the box onto where it went, within a dimension and into another. The links
 *       work at the destination.
 * </ul>
 *
 * <p>Pipes and canisters keep their gas either way, in a move done in one tick: a node leaves as its
 * chunk unloading would let it go, keeping its share in its saved data. A pipe run moved over many
 * ticks vents most of its gas either way, which the sliced tests record (west3436/zas-cubed#524).
 *
 * <p>With ZAS³'s content off ({@code -Pcontent=off}) none of these blocks is registered, so every
 * test here passes at once.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ZasContentMoveTests {
    private static final String BATCH = "zas_content";

    /** Each test's limit in game ticks, as {@link MoverTests}'s. */
    private static final int TIMEOUT = 20_000;

    private static final TicketType<ChunkPos> HELD =
            TicketType.create("expedition_gametest_zas_held", Comparator.comparingLong(ChunkPos::toLong), TIMEOUT);

    private static final ResourceLocation CO2 = ResourceLocation.fromNamespaceAndPath("zas", "carbon_dioxide");

    /** Moles of carbon dioxide in the pipe run and in the canister: well under either's rating. */
    private static final double MOLES = 500;

    private static final double EPSILON = 1.0E-6;

    /**
     * Block state properties that are a device's live readout rather than how it was built: an area
     * air controller's and an air sensor's {@code status}, a vent's and a scrubber's {@code mode},
     * which follow their controller's orders, and an airlock controller's {@code cycling}. A move may
     * land between two steps that change them, so the state comparisons leave them out.
     */
    private static final Set<String> LIVE = Set.of("status", "mode", "cycling");

    private ZasContentMoveTests() {}

    // ---- The tests ----------------------------------------------------------------------

    /** A capped run of four pipes holding 500 mol of carbon dioxide keeps all of it, in one network. */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void a_pipe_run_keeps_its_gas_without_the_handover(GameTestHelper h) {
        run(h, 0, new PipeRun(false), false);
    }

    /** The same with ZAS³'s relocation. */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void a_pipe_run_keeps_its_gas_with_zas_relocation(GameTestHelper h) {
        run(h, 1, new PipeRun(false), true);
    }

    /** A canister keeps its gas, its release pressure and its closed valve. */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void a_canister_keeps_its_gas_without_the_handover(GameTestHelper h) {
        run(h, 2, new Canister(), false);
    }

    /** The same with ZAS³'s relocation. */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void a_canister_keeps_its_gas_with_zas_relocation(GameTestHelper h) {
        run(h, 3, new Canister(), true);
    }

    /**
     * An area air controller arrives naming its vent's and scrubber's old places, and drops both
     * links at its next step: the moved vent and scrubber are no longer controlled.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void an_area_air_controller_loses_its_links_without_the_handover(GameTestHelper h) {
        run(h, 4, new AreaAir(), false);
    }

    /** With ZAS³'s relocation its links follow the vent and scrubber, which it goes on controlling. */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void an_area_air_controller_keeps_its_links_with_zas_relocation(GameTestHelper h) {
        run(h, 5, new AreaAir(), true);
    }

    /**
     * An airlock controller arrives with its door, sensor and vent at their old places, and empties
     * those slots at its next step: the moved airlock has no controller.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void an_airlock_controller_loses_its_links_without_the_handover(GameTestHelper h) {
        run(h, 6, new Airlock(), false);
    }

    /** With ZAS³'s relocation its slots follow the airlock and sensor, and it goes on ordering the vent. */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void an_airlock_controller_keeps_its_links_with_zas_relocation(GameTestHelper h) {
        run(h, 7, new Airlock(), true);
    }

    /**
     * A pipe run moved one position a tick, so that its pipes' atmos steps fall while only part of it
     * has moved, vents most of its gas: each pipe whose neighbour has gone, or hasn't arrived yet,
     * takes its arm back from the air there, is left with one arm, an open end, and leaks into the
     * open air until the move's settling fits its arms again. This records the loss; it is
     * west3436/zas-cubed#524.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void a_sliced_pipe_run_vents_its_gas_without_the_handover(GameTestHelper h) {
        run(h, 8, new PipeRun(true), false);
    }

    /** The same with ZAS³'s relocation, which doesn't stop it. */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void a_sliced_pipe_run_vents_its_gas_with_zas_relocation(GameTestHelper h) {
        run(h, 9, new PipeRun(true), true);
    }

    // ---- How each test runs -------------------------------------------------------------

    /**
     * Builds a ship, moves it 100 blocks east within the Overworld and then into the Nether, and after
     * each move checks what it kept and whether its links work.
     */
    private static void run(GameTestHelper h, int index, Ship ship, boolean zas) {
        if (!ZasAtmosMod.contentEnabled()) {
            // -Pcontent=off: ZAS³'s blocks aren't registered, so there is nothing to move.
            h.succeed();
            return;
        }
        ServerLevel overworld = h.getLevel();
        ServerLevel nether = level(h, Level.NETHER);
        BlockPos a = area(index);
        BlockPos b = a.offset(100, 0, 0);
        BlockPos c = new BlockPos(b.getX(), 64, b.getZ());
        Run run = new Run(ship, zas);
        h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, overworld, run.box(a));
                    hold(h, overworld, run.box(b));
                    hold(h, nether, run.box(c));
                })
                .thenExecute(() -> {
                    wipe(overworld, run.box(a));
                    wipe(overworld, run.box(b));
                    wipe(nether, run.box(c));
                    ship.build(overworld, a);
                    ship.link(overworld, a);
                })
                .thenWaitUntil(() -> ship.ready(h, overworld, a))
                .thenExecute(() -> run.start(overworld, a, overworld, b))
                .thenWaitUntil(() -> h.assertTrue(run.job.isDone(), "the move within the Overworld is still going"))
                .thenExecute(() -> run.arrived(h, overworld, a, overworld, b))
                .thenWaitUntil(() -> run.settled(h, overworld, b))
                .thenExecute(() -> {
                    if (!zas) {
                        ship.link(overworld, b);
                    }
                    ship.refill(overworld, b);
                })
                .thenWaitUntil(() -> ship.ready(h, overworld, b))
                .thenExecute(() -> run.start(overworld, b, nether, c))
                .thenWaitUntil(() -> h.assertTrue(run.job.isDone(), "the move into the Nether is still going"))
                .thenExecute(() -> run.arrived(h, overworld, b, nether, c))
                .thenWaitUntil(() -> run.settled(h, nether, c))
                .thenExecute(() -> {
                    wipe(overworld, run.box(a));
                    wipe(overworld, run.box(b));
                    wipe(nether, run.box(c));
                    release(overworld, run.box(a));
                    release(overworld, run.box(b));
                    release(nether, run.box(c));
                })
                .thenSucceed();
    }

    /** One test's ship as it goes. */
    private static final class Run {
        final Ship ship;
        final boolean zas;
        Map<Vec3i, BlockState> before;
        MoveJob job;
        /** The mover's own ZAS³ ticket for the last move, when it moved with ZAS³'s relocation. */
        ZasRelocationHandover.ZasTicket zasTicket;
        /** The controller's links as the hand-over completed: the moment the blocks had all arrived. */
        Set<BlockPos> linksOnArrival;

        Run(Ship ship, boolean zas) {
            this.ship = ship;
            this.zas = zas;
        }

        BoundingBox box(BlockPos corner) {
            return ZasContentMoveTests.box(corner, ship.size());
        }

        void start(ServerLevel from, BlockPos corner, ServerLevel to, BlockPos origin) {
            before = blocks(from, corner, ship.size());
            linksOnArrival = null;
            zasTicket = null;
            RelocationHandover inner = zas ? ZasRelocationHandover.INSTANCE : RelocationHandover.NONE;
            RelocationHandover peek = (level, box) -> {
                RelocationHandover.Ticket ticket = inner.begin(level, box);
                if (ticket instanceof ZasRelocationHandover.ZasTicket own) {
                    zasTicket = own;
                }
                return new RelocationHandover.Ticket() {
                    @Override
                    public CompletableFuture<Optional<String>> begun() {
                        return ticket.begun();
                    }

                    @Override
                    public void complete(ServerLevel arrivedIn, Vec3i offset) {
                        ticket.complete(arrivedIn, offset);
                        linksOnArrival = ship.links(arrivedIn, origin);
                    }

                    @Override
                    public void cancel() {
                        ticket.cancel();
                    }
                };
            };
            MoveSettings settings = MoveSettings.DEFAULT
                    .withBudget(ship.workPerTick(), MoveSettings.DEFAULT.nanosPerTick())
                    .withHandover(peek);
            job = ShipMover.start(new MoveRequest(from, box(corner), to, origin), settings);
        }

        /** At once after a move: the blocks, the saved data and the links as they arrived. */
        void arrived(GameTestHelper h, ServerLevel from, BlockPos was, ServerLevel to, BlockPos now) {
            MoveResult result = job.result().join();
            h.assertTrue(result.moved(), "the move was refused: " + result.refusal());
            h.assertTrue(result.entitiesLeft() == 0, "the move left entities behind: " + result);
            assertBlocks(h, before, to, now);
            assertClear(h, from, box(was));
            assertNothingDropped(h, from, box(was));
            assertNothingDropped(h, to, box(now));
            ship.kept(h, to, now);
            Set<BlockPos> expected = ship.links(now).isEmpty() ? Set.of() : ship.links(zas ? now : was);
            h.assertTrue(
                    expected.equals(linksOnArrival),
                    (zas ? "ZAS³'s relocation didn't re-base the links: " : "the links aren't the old places: ")
                            + linksOnArrival + ", expected " + expected);
        }

        /** Throws until the ship has settled at its new place, then checks what works there. */
        void settled(GameTestHelper h, ServerLevel level, BlockPos corner) {
            if (zas) {
                CompletableFuture<RelocationResult> completed = zasTicket == null ? null : zasTicket.ended();
                h.assertTrue(completed != null && completed.isDone(), "ZAS³'s relocation hasn't finished");
                RelocationResult result = completed.join();
                h.assertTrue(
                        result.outcome() == RelocationOutcome.MOVED, "ZAS³'s relocation ended " + result.outcome());
                ship.works(h, level, corner);
            } else {
                ship.unlinked(h, level, corner);
            }
        }
    }

    /**
     * A ship of ZAS³'s blocks. Positions are relative to its lowest corner, and every method but
     * {@link #links(BlockPos)} takes the corner where the ship is now.
     */
    private interface Ship {
        /** Its box. */
        Vec3i size();

        /** The work units a tick its moves may spend. */
        default int workPerTick() {
            return MoveSettings.DEFAULT.workPerTick();
        }

        /** Builds the ship, with any gas in it. */
        void build(ServerLevel level, BlockPos corner);

        /** Links its devices to their controller, if it has one. */
        default void link(ServerLevel level, BlockPos corner) {}

        /** Before a second move, gives back any gas the first lost, so each move starts full. */
        default void refill(ServerLevel level, BlockPos corner) {}

        /** Throws until the ship is ready to move: its gas in place, its controller working its links. */
        void ready(GameTestHelper h, ServerLevel level, BlockPos corner);

        /** The positions its controller links, for a ship at {@code corner}; empty for none. */
        default Set<BlockPos> links(BlockPos corner) {
            return Set.of();
        }

        /** The positions its controller links now. */
        default Set<BlockPos> links(ServerLevel level, BlockPos corner) {
            return Set.of();
        }

        /** At once after a move: what its block entities kept. */
        void kept(GameTestHelper h, ServerLevel level, BlockPos corner);

        /** Throws until it works at its new place, its links followed, and checks that it does. */
        void works(GameTestHelper h, ServerLevel level, BlockPos corner);

        /**
         * Throws until its controller has dropped the links that named the old place, and checks that
         * its devices are unlinked. Without a controller, the same as {@link #works}.
         */
        default void unlinked(GameTestHelper h, ServerLevel level, BlockPos corner) {
            works(h, level, corner);
        }
    }

    // ---- The pipe run -------------------------------------------------------------------

    /**
     * A deck with a pipe run on it: a cap, four pipes and a cap, holding 500 mol of carbon dioxide. Its
     * caps close both ends, so no gas leaves it. Sliced, it runs north to south across a deck 21
     * wide, and the move carries one position a tick, so its pipes go 21 ticks apart and every
     * node's atmos step, once in 20 ticks, falls while its neighbour on one side is gone or not yet
     * there. Sliced, it loses most of its gas: see {@link #a_sliced_pipe_run_vents_its_gas_without_the_handover}.
     */
    private static final class PipeRun implements Ship {
        private final boolean sliced;

        PipeRun(boolean sliced) {
            this.sliced = sliced;
        }

        @Override
        public Vec3i size() {
            return sliced ? new Vec3i(21, 2, 8) : new Vec3i(8, 2, 3);
        }

        @Override
        public int workPerTick() {
            return sliced ? 1 : Ship.super.workPerTick();
        }

        /** The run's nodes, cap to cap. */
        private List<Vec3i> nodes() {
            return sliced
                    ? List.of(
                            new Vec3i(3, 1, 1),
                            new Vec3i(3, 1, 2),
                            new Vec3i(3, 1, 3),
                            new Vec3i(3, 1, 4),
                            new Vec3i(3, 1, 5),
                            new Vec3i(3, 1, 6))
                    : List.of(
                            new Vec3i(1, 1, 1),
                            new Vec3i(2, 1, 1),
                            new Vec3i(3, 1, 1),
                            new Vec3i(4, 1, 1),
                            new Vec3i(5, 1, 1),
                            new Vec3i(6, 1, 1));
        }

        @Override
        public void build(ServerLevel level, BlockPos corner) {
            deck(level, corner, size());
            List<Vec3i> nodes = nodes();
            Direction along = sliced ? Direction.SOUTH : Direction.EAST;
            place(level, corner.offset(nodes.getFirst()), cap(along));
            for (Vec3i p : nodes.subList(1, nodes.size() - 1)) {
                place(level, corner.offset(p), AtmosBlocks.PIPE.get().defaultBlockState());
            }
            place(level, corner.offset(nodes.getLast()), cap(along.getOpposite()));
            node(level, corner, 1).network().gas().add(new GasParcel(Map.of(CO2, MOLES), StandardAir.TEMPERATURE));
        }

        private static BlockState cap(Direction facing) {
            return AtmosBlocks.PIPE_CAP.get().defaultBlockState().setValue(PipeCapBlock.FACING, facing);
        }

        private PipeNodeEntity node(ServerLevel level, BlockPos corner, int i) {
            return entity(level, corner.offset(nodes().get(i)), PipeNodeEntity.class);
        }

        @Override
        public void ready(GameTestHelper h, ServerLevel level, BlockPos corner) {
            assertOneNetwork(h, level, corner);
        }

        /** Right after the move, the nodes' saved shares, taken as they left, still add up to all of it. */
        @Override
        public void refill(ServerLevel level, BlockPos corner) {
            PipeNetwork network = node(level, corner, 1).network();
            double lost = MOLES - network.gas().moles(CO2);
            if (lost > 0) {
                network.gas().add(new GasParcel(Map.of(CO2, lost), StandardAir.TEMPERATURE));
            }
        }

        @Override
        public void kept(GameTestHelper h, ServerLevel level, BlockPos corner) {
            double moles = 0;
            for (int i = 0; i < nodes().size(); i++) {
                moles += node(level, corner, i).share().moles().getOrDefault(CO2, 0.0);
            }
            if (sliced) {
                assertVented(h, moles);
            } else {
                assertMoles(h, moles, "the moved nodes' shares of carbon dioxide");
            }
        }

        @Override
        public void works(GameTestHelper h, ServerLevel level, BlockPos corner) {
            if (!sliced) {
                assertOneNetwork(h, level, corner);
                return;
            }
            // Its pipes are all on the box's top face, so the move's settling fitted their arms
            // again: it is one closed run once more, holding what it didn't vent.
            PipeNetwork network = node(level, corner, 1).network();
            h.assertTrue(
                    network.members().size() == nodes().size(),
                    "the sliced run's network has " + network.members().size() + " nodes");
            assertVented(h, network.gas().moles(CO2));
        }

        /** Records the sliced move's loss: under half the gas arrived. */
        private static void assertVented(GameTestHelper h, double moles) {
            h.assertTrue(
                    moles < MOLES / 2,
                    "a sliced move kept " + moles + " mol of " + MOLES + ": if west3436/zas-cubed#524 is fixed,"
                            + " assert that it keeps it all");
        }

        /** Every node is in one network again, and it holds all the gas. */
        private void assertOneNetwork(GameTestHelper h, ServerLevel level, BlockPos corner) {
            PipeNetwork network = node(level, corner, 1).network();
            for (int i = 0; i < nodes().size(); i++) {
                PipeNetwork own = node(level, corner, i).network();
                h.assertTrue(
                        own == network,
                        "node " + i + " of the run is in a network of "
                                + own.members().size() + " holding "
                                + own.gas().moles(CO2) + " mol, apart from the first pipe's "
                                + network.members().size() + " holding "
                                + network.gas().moles(CO2) + " mol");
            }
            h.assertTrue(
                    network.members().size() == nodes().size(),
                    "the run's network has " + network.members().size() + " nodes");
            assertMoles(h, network.gas().moles(CO2), "carbon dioxide in the run");
        }
    }

    // ---- The canister -------------------------------------------------------------------

    /** A deck with a closed canister on it holding 500 mol of carbon dioxide, released at 321 kPa. */
    private static final class Canister implements Ship {
        private static final Vec3i AT = new Vec3i(1, 1, 1);
        private static final double RELEASE = 321;

        @Override
        public Vec3i size() {
            return new Vec3i(3, 2, 3);
        }

        @Override
        public void build(ServerLevel level, BlockPos corner) {
            deck(level, corner, size());
            place(
                    level,
                    corner.offset(AT),
                    AtmosBlocks.CANISTER
                            .get()
                            .defaultBlockState()
                            .setValue(CanisterBlock.FACING, Direction.SOUTH)
                            .setValue(CanisterBlock.OPEN, false));
            CanisterBlockEntity canister = canister(level, corner);
            canister.setReleasePressure(RELEASE);
            canister.network().gas().add(new GasParcel(Map.of(CO2, MOLES), StandardAir.TEMPERATURE));
        }

        private static CanisterBlockEntity canister(ServerLevel level, BlockPos corner) {
            return entity(level, corner.offset(AT), CanisterBlockEntity.class);
        }

        @Override
        public void ready(GameTestHelper h, ServerLevel level, BlockPos corner) {
            works(h, level, corner);
        }

        @Override
        public void kept(GameTestHelper h, ServerLevel level, BlockPos corner) {
            CanisterBlockEntity canister = canister(level, corner);
            assertMoles(h, canister.share().moles().getOrDefault(CO2, 0.0), "carbon dioxide in the canister");
            h.assertTrue(
                    canister.releasePressure() == RELEASE,
                    "the canister's release pressure is " + canister.releasePressure());
            h.assertFalse(canister.valveOpen(), "the canister's valve opened");
        }

        @Override
        public void works(GameTestHelper h, ServerLevel level, BlockPos corner) {
            CanisterBlockEntity canister = canister(level, corner);
            assertMoles(h, canister.network().gas().moles(CO2), "carbon dioxide in the canister");
            h.assertTrue(
                    canister.releasePressure() == RELEASE,
                    "the canister's release pressure is " + canister.releasePressure());
        }
    }

    // ---- The area air controller --------------------------------------------------------

    /**
     * A deck with an area air controller on a wall block, and a vent and a scrubber linked to it with
     * the multitool. The deck is in the open air, so the controller has no zone, and reaches them
     * through its links alone. Its buffer is kept full, so it powers and orders them.
     */
    private static final class AreaAir implements Ship {
        private static final Vec3i WALL = new Vec3i(0, 1, 2);
        private static final Vec3i CONTROLLER = new Vec3i(1, 1, 2);
        private static final Vec3i VENT = new Vec3i(3, 1, 1);
        private static final Vec3i SCRUBBER = new Vec3i(4, 1, 2);

        @Override
        public Vec3i size() {
            return new Vec3i(6, 2, 4);
        }

        @Override
        public void build(ServerLevel level, BlockPos corner) {
            deck(level, corner, size());
            level.setBlock(corner.offset(WALL), Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_CLIENTS);
            place(
                    level,
                    corner.offset(CONTROLLER),
                    AtmosBlocks.AREA_AIR_CONTROLLER
                            .get()
                            .defaultBlockState()
                            .setValue(HorizontalDirectionalBlock.FACING, Direction.EAST));
            place(
                    level,
                    corner.offset(VENT),
                    AtmosBlocks.VENT.get().defaultBlockState().setValue(VentBlock.FACING, Direction.UP));
            place(
                    level,
                    corner.offset(SCRUBBER),
                    AtmosBlocks.SCRUBBER.get().defaultBlockState().setValue(ScrubberBlock.FACING, Direction.UP));
            label(level, corner.offset(CONTROLLER), "Bridge air");
            label(level, corner.offset(VENT), "Bridge vent");
            label(level, corner.offset(SCRUBBER), "Bridge scrubber");
        }

        private static AreaAirControllerBlockEntity controller(ServerLevel level, BlockPos corner) {
            AreaAirControllerBlockEntity c =
                    entity(level, corner.offset(CONTROLLER), AreaAirControllerBlockEntity.class);
            c.buffer().set(c.buffer().getMaxEnergyStored());
            return c;
        }

        @Override
        public void link(ServerLevel level, BlockPos corner) {
            AreaAirControllerBlockEntity c = controller(level, corner);
            for (Vec3i p : List.of(VENT, SCRUBBER)) {
                if (!c.links().contains(corner.offset(p))) {
                    c.toggleLink(corner.offset(p));
                }
            }
        }

        @Override
        public Set<BlockPos> links(BlockPos corner) {
            return Set.of(corner.offset(VENT), corner.offset(SCRUBBER));
        }

        @Override
        public Set<BlockPos> links(ServerLevel level, BlockPos corner) {
            return controller(level, corner).links();
        }

        @Override
        public void ready(GameTestHelper h, ServerLevel level, BlockPos corner) {
            works(h, level, corner);
        }

        @Override
        public void kept(GameTestHelper h, ServerLevel level, BlockPos corner) {
            assertLabel(h, level, corner.offset(CONTROLLER), "Bridge air");
            assertLabel(h, level, corner.offset(VENT), "Bridge vent");
            assertLabel(h, level, corner.offset(SCRUBBER), "Bridge scrubber");
        }

        /** The controller lists the vent and scrubber as linked parts, and powers and orders them. */
        @Override
        public void works(GameTestHelper h, ServerLevel level, BlockPos corner) {
            AreaAirControllerBlockEntity c = controller(level, corner);
            Optional<BlockPos> self = Optional.of(c.getBlockPos());
            VentBlockEntity vent = entity(level, corner.offset(VENT), VentBlockEntity.class);
            ScrubberBlockEntity scrubber = entity(level, corner.offset(SCRUBBER), ScrubberBlockEntity.class);
            h.assertTrue(vent.controller().equals(self), "the vent's controller is " + vent.controller());
            h.assertTrue(vent.power().isPowered(), "the vent has no power");
            h.assertTrue(scrubber.controller().equals(self), "the scrubber's controller is " + scrubber.controller());
            h.assertTrue(scrubber.power().isPowered(), "the scrubber has no power");
            h.assertTrue(c.links().equals(links(corner)), "the controller links " + c.links());
            Set<BlockPos> listed = new LinkedHashSet<>();
            for (AreaAirControllerBlockEntity.Part part : c.parts()) {
                if (part.linked()) {
                    listed.add(part.pos());
                }
            }
            h.assertTrue(listed.equals(links(corner)), "the controller lists " + c.parts());
        }

        /**
         * The controller has dropped both links, and the vent and the scrubber are controlled only if
         * they share its room.
         */
        @Override
        public void unlinked(GameTestHelper h, ServerLevel level, BlockPos corner) {
            AreaAirControllerBlockEntity c = controller(level, corner);
            h.assertTrue(c.links().isEmpty(), "the controller still links " + c.links());
            Set<BlockPos> inZone = new LinkedHashSet<>();
            for (AreaAirControllerBlockEntity.Part part : c.parts()) {
                h.assertFalse(part.linked(), "the controller lists a linked part: " + part);
                if (part.inZone()) {
                    inZone.add(part.pos());
                }
            }
            // A destination walled in by the Nether's rock can close a room around the deck, and a
            // part in the controller's own room is covered through it, link or none.
            VentBlockEntity vent = entity(level, corner.offset(VENT), VentBlockEntity.class);
            ScrubberBlockEntity scrubber = entity(level, corner.offset(SCRUBBER), ScrubberBlockEntity.class);
            h.assertTrue(
                    !vent.controlled() || inZone.contains(vent.getBlockPos()),
                    "something controls the moved vent: " + vent.controller());
            h.assertTrue(
                    !scrubber.controlled() || inZone.contains(scrubber.getBlockPos()),
                    "something controls the moved scrubber: " + scrubber.controller());
        }
    }

    // ---- The airlock --------------------------------------------------------------------

    /**
     * A deck with an airlock, and an airlock controller and an air sensor on a wall, the airlock, the
     * sensor and a vent linked to the controller with the multitool.
     */
    private static final class Airlock implements Ship {
        private static final Vec3i DOOR = new Vec3i(2, 1, 1);
        private static final Vec3i CONTROLLER = new Vec3i(4, 1, 2);
        private static final Vec3i SENSOR = new Vec3i(4, 2, 2);
        private static final Vec3i WALL = new Vec3i(5, 1, 2);
        private static final Vec3i VENT = new Vec3i(1, 1, 2);

        /** The slots the door and the sensor went to when they were last linked. */
        private AirlockControllerBlockEntity.Slot doorSlot;

        private AirlockControllerBlockEntity.Slot sensorSlot;

        @Override
        public Vec3i size() {
            return new Vec3i(6, 3, 4);
        }

        @Override
        public void build(ServerLevel level, BlockPos corner) {
            deck(level, corner, size());
            level.setBlock(corner.offset(WALL), Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.setBlock(corner.offset(WALL).above(), Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_CLIENTS);
            BlockState door = AtmosBlocks.AIRLOCK
                    .get()
                    .defaultBlockState()
                    .setValue(AirlockBlock.FACING, Direction.SOUTH)
                    .setValue(AirlockBlock.HALF, DoubleBlockHalf.LOWER);
            level.setBlock(corner.offset(DOOR), door, Block.UPDATE_CLIENTS);
            level.setBlock(
                    corner.offset(DOOR).above(),
                    door.setValue(AirlockBlock.HALF, DoubleBlockHalf.UPPER),
                    Block.UPDATE_CLIENTS);
            loaded(level, corner.offset(DOOR));
            place(
                    level,
                    corner.offset(CONTROLLER),
                    AtmosBlocks.AIRLOCK_CONTROLLER
                            .get()
                            .defaultBlockState()
                            .setValue(HorizontalDirectionalBlock.FACING, Direction.WEST));
            place(
                    level,
                    corner.offset(SENSOR),
                    AtmosBlocks.AIR_SENSOR
                            .get()
                            .defaultBlockState()
                            .setValue(HorizontalDirectionalBlock.FACING, Direction.WEST));
            place(
                    level,
                    corner.offset(VENT),
                    AtmosBlocks.VENT.get().defaultBlockState().setValue(VentBlock.FACING, Direction.UP));
            label(level, corner.offset(DOOR), "Cargo door");
            label(level, corner.offset(CONTROLLER), "Cargo cycler");
            label(level, corner.offset(SENSOR), "Cargo sensor");
            label(level, corner.offset(VENT), "Cargo vent");
        }

        private static AirlockControllerBlockEntity controller(ServerLevel level, BlockPos corner) {
            return entity(level, corner.offset(CONTROLLER), AirlockControllerBlockEntity.class);
        }

        @Override
        public void link(ServerLevel level, BlockPos corner) {
            AirlockControllerBlockEntity c = controller(level, corner);
            for (Vec3i p : List.of(DOOR, SENSOR, VENT)) {
                AirlockControllerBlockEntity.LinkResult linked = c.toggleLink(level, corner.offset(p));
                if (linked != AirlockControllerBlockEntity.LinkResult.LINKED) {
                    throw new GameTestAssertException("linking " + p + " to the airlock controller: " + linked);
                }
            }
            AirlockControllerBlockEntity.Slot door = c.slotOf(corner.offset(DOOR));
            AirlockControllerBlockEntity.Slot sensor = c.slotOf(corner.offset(SENSOR));
            doorSlot = door;
            sensorSlot = sensor;
            if (door == null || !door.door() || sensor == null || sensor.door()) {
                throw new GameTestAssertException("the airlock is in slot " + door + ", the sensor in " + sensor);
            }
        }

        @Override
        public Set<BlockPos> links(BlockPos corner) {
            return Set.of(corner.offset(DOOR), corner.offset(SENSOR), corner.offset(VENT));
        }

        @Override
        public Set<BlockPos> links(ServerLevel level, BlockPos corner) {
            AirlockControllerBlockEntity c = controller(level, corner);
            Set<BlockPos> links = new LinkedHashSet<>();
            for (AirlockControllerBlockEntity.Slot s : AirlockControllerBlockEntity.Slot.values()) {
                BlockPos p = c.slot(s);
                if (p != null) {
                    links.add(p);
                }
            }
            links.addAll(c.linkedVents());
            return links;
        }

        @Override
        public void ready(GameTestHelper h, ServerLevel level, BlockPos corner) {
            works(h, level, corner);
        }

        @Override
        public void kept(GameTestHelper h, ServerLevel level, BlockPos corner) {
            entity(level, corner.offset(DOOR), AirlockBlockEntity.class);
            entity(level, corner.offset(SENSOR), AirSensorBlockEntity.class);
            assertLabel(h, level, corner.offset(DOOR), "Cargo door");
            assertLabel(h, level, corner.offset(CONTROLLER), "Cargo cycler");
            assertLabel(h, level, corner.offset(SENSOR), "Cargo sensor");
            assertLabel(h, level, corner.offset(VENT), "Cargo vent");
        }

        /**
         * The controller has the airlock and the sensor in the slots they had, the airlock and the
         * sensor find it, and it orders the vent, as it does every step whether powered or not.
         */
        @Override
        public void works(GameTestHelper h, ServerLevel level, BlockPos corner) {
            AirlockControllerBlockEntity c = controller(level, corner);
            VentBlockEntity vent = entity(level, corner.offset(VENT), VentBlockEntity.class);
            h.assertTrue(vent.airlockControlled(), "the airlock controller doesn't order the vent");
            h.assertTrue(
                    vent.controller().equals(Optional.of(c.getBlockPos())),
                    "the vent's controller is " + vent.controller());
            h.assertTrue(
                    c.slot(doorSlot) != null && c.slot(doorSlot).equals(corner.offset(DOOR)),
                    "the controller's " + doorSlot + " is " + c.slot(doorSlot));
            h.assertTrue(
                    c.slot(sensorSlot) != null && c.slot(sensorSlot).equals(corner.offset(SENSOR)),
                    "the controller's " + sensorSlot + " is " + c.slot(sensorSlot));
            h.assertTrue(
                    c.linkedVents().equals(Set.of(corner.offset(VENT))), "the controller's vents: " + c.linkedVents());
            h.assertTrue(
                    AirlockControllerBlockEntity.linking(level, corner.offset(DOOR))
                            .equals(Optional.of(c)),
                    "the airlock finds no controller");
            h.assertTrue(
                    AirlockControllerBlockEntity.linkingSensor(level, corner.offset(SENSOR))
                            .equals(Optional.of(c)),
                    "the sensor finds no controller");
        }

        /** The controller has emptied its slots and dropped the vent: the moved airlock has no controller. */
        @Override
        public void unlinked(GameTestHelper h, ServerLevel level, BlockPos corner) {
            AirlockControllerBlockEntity c = controller(level, corner);
            Set<BlockPos> links = links(level, corner);
            h.assertTrue(links.isEmpty(), "the airlock controller still links " + links);
            h.assertTrue(
                    AirlockControllerBlockEntity.linking(level, corner.offset(DOOR))
                            .isEmpty(),
                    "the moved airlock has a controller");
            VentBlockEntity vent = entity(level, corner.offset(VENT), VentBlockEntity.class);
            h.assertFalse(vent.airlockControlled(), "an airlock controller orders the moved vent");
            h.assertTrue(c.cycling() < 0, "the controller is cycling");
        }
    }

    // ---- Building -----------------------------------------------------------------------

    /** A test's area: chunk-aligned, far from the GameTest grid, from {@link MoverTests}' and from each other. */
    private static BlockPos area(int index) {
        return new BlockPos(-20_480 + 512 * index, 100, 20_480);
    }

    private static BoundingBox box(BlockPos corner, Vec3i size) {
        return BoundingBox.fromCorners(corner, corner.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1));
    }

    private static ServerLevel level(GameTestHelper h, ResourceKey<Level> dimension) {
        ServerLevel level = h.getLevel().getServer().getLevel(dimension);
        if (level == null) {
            throw new GameTestAssertException("no " + dimension.location());
        }
        return level;
    }

    /** Lets go of the chunks {@link #hold} forced. */
    private static void release(ServerLevel level, BoundingBox box) {
        for (int x = (box.minX() - 1) >> 4; x <= (box.maxX() + 1) >> 4; x++) {
            for (int z = (box.minZ() - 1) >> 4; z <= (box.maxZ() + 1) >> 4; z++) {
                level.setChunkForced(x, z, false);
            }
        }
    }

    /** Loads the chunks around a box and keeps them loaded and ticking; throws until they are. */
    private static void hold(GameTestHelper h, ServerLevel level, BoundingBox box) {
        for (int x = (box.minX() - 1) >> 4; x <= (box.maxX() + 1) >> 4; x++) {
            for (int z = (box.minZ() - 1) >> 4; z <= (box.maxZ() + 1) >> 4; z++) {
                ChunkPos c = new ChunkPos(x, z);
                level.getChunkSource().addRegionTicket(HELD, c, 2, c);
                // A level with no player and no forced chunk stops ticking its block entities after
                // 300 ticks, and then no device moved there would load or step. A ship moved with a
                // player aboard keeps its level ticking; here a forced chunk does.
                level.setChunkForced(x, z, true);
                level.getChunk(x, z);
                h.assertTrue(
                        level.areEntitiesLoaded(c.toLong()),
                        "entities aren't loaded yet in " + c + " of "
                                + level.dimension().location());
            }
        }
    }

    /** A floor of stone bricks under the whole box. */
    private static void deck(ServerLevel level, BlockPos corner, Vec3i size) {
        for (int x = 0; x < size.getX(); x++) {
            for (int z = 0; z < size.getZ(); z++) {
                level.setBlock(corner.offset(x, 0, z), Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    /**
     * Places a block as a player would, fitted to its neighbours and they to it, and loads its device
     * now rather than at the start of the next tick, as ZAS³'s own GameTests do.
     */
    private static void place(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, Block.updateFromNeighbourShapes(state, level, pos), Block.UPDATE_ALL);
        loaded(level, pos);
    }

    private static void loaded(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof AtmosDevice device && !DeviceTicker.isScheduled(device)) {
            device.onLoad();
        }
    }

    private static void label(ServerLevel level, BlockPos pos, String label) {
        entity(level, pos, Labelled.class).setLabel(label);
    }

    /** Takes every block and entity out of a box, quietly, so nothing drops. */
    private static void wipe(ServerLevel level, BoundingBox box) {
        for (Entity e : level.getEntities((Entity) null, AABB.of(box).inflate(2), e -> !(e instanceof Player))) {
            e.discard();
        }
        for (BlockPos p :
                BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            LevelChunk chunk = level.getChunkAt(p);
            QuietBlocks.takeBlockEntity(level, chunk, p);
            QuietBlocks.set(level, chunk, p, Blocks.AIR.defaultBlockState());
        }
    }

    // ---- Checking -----------------------------------------------------------------------

    /** The block entity at a position, of a class; throws if there is none of that class. */
    private static <T> T entity(ServerLevel level, BlockPos pos, Class<T> type) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!type.isInstance(be)) {
            throw new GameTestAssertException("expected a " + type.getSimpleName() + " at " + pos.toShortString()
                    + " in " + level.dimension().location() + ", found " + be);
        }
        return type.cast(be);
    }

    private static Map<Vec3i, BlockState> blocks(ServerLevel level, BlockPos corner, Vec3i size) {
        Map<Vec3i, BlockState> blocks = new HashMap<>();
        BoundingBox box = box(corner, size);
        for (BlockPos p :
                BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            blocks.put(p.subtract(corner), level.getBlockState(p));
        }
        return blocks;
    }

    /** The ship's blocks are at {@code corner} as they were, but for their {@link #LIVE} readouts. */
    private static void assertBlocks(
            GameTestHelper h, Map<Vec3i, BlockState> before, ServerLevel level, BlockPos corner) {
        before.forEach((rel, state) -> {
            BlockPos p = corner.offset(rel);
            BlockState now = level.getBlockState(p);
            if (!steady(now).equals(steady(state))) {
                throw new GameTestAssertException("expected " + state + " at " + p.toShortString() + " in "
                        + level.dimension().location() + ", found " + now);
            }
        });
    }

    /** A state with its live readouts set to their first values. */
    private static BlockState steady(BlockState state) {
        for (Property<?> p : state.getProperties()) {
            if (LIVE.contains(p.getName())) {
                state = first(state, p);
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState first(BlockState state, Property<T> p) {
        return state.setValue(p, p.getPossibleValues().iterator().next());
    }

    private static void assertClear(GameTestHelper h, ServerLevel level, BoundingBox box) {
        for (BlockPos p :
                BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            h.assertTrue(
                    level.getBlockState(p).isAir(),
                    "expected air at " + p.toShortString() + ", found " + level.getBlockState(p));
        }
    }

    private static void assertNothingDropped(GameTestHelper h, ServerLevel level, BoundingBox box) {
        List<ItemEntity> dropped =
                level.getEntitiesOfClass(ItemEntity.class, AABB.of(box).inflate(2));
        h.assertTrue(dropped.isEmpty(), "the move dropped " + dropped);
    }

    private static void assertMoles(GameTestHelper h, double moles, String what) {
        h.assertTrue(Math.abs(moles - MOLES) < EPSILON * MOLES, what + ": " + moles + " mol, not " + MOLES);
    }

    private static void assertLabel(GameTestHelper h, ServerLevel level, BlockPos pos, String label) {
        String now = entity(level, pos, Labelled.class).label();
        h.assertTrue(
                now.equals(label), "the label at " + pos.toShortString() + " is '" + now + "', not '" + label + "'");
    }
}
