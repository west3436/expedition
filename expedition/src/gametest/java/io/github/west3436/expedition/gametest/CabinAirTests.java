package io.github.west3436.expedition.gametest;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.mover.MoveCheck;
import io.github.west3436.expedition.mover.MoveJob;
import io.github.west3436.expedition.mover.MoveRefusal;
import io.github.west3436.expedition.mover.MoveRequest;
import io.github.west3436.expedition.mover.MoveResult;
import io.github.west3436.expedition.mover.MoveSettings;
import io.github.west3436.expedition.mover.QuietBlocks;
import io.github.west3436.expedition.mover.RelocationHandover;
import io.github.west3436.expedition.mover.ShipMover;
import io.github.west3436.expedition.mover.ZasRelocationHandover;
import io.github.west3436.zas.api.atmosphere.Atmospheres;
import io.github.west3436.zas.api.atmosphere.ZoneId;
import io.github.west3436.zas.api.atmosphere.ZoneInfo;
import io.github.west3436.zas.api.event.ZoneBreachedEvent;
import io.github.west3436.zas.api.event.ZoneRelocatedEvent;
import io.github.west3436.zas.api.gas.GasParcel;
import io.github.west3436.zas.api.gas.StandardAir;
import io.github.west3436.zas.api.mutation.GasMutations;
import io.github.west3436.zas.api.mutation.MutationOutcome;
import io.github.west3436.zas.api.mutation.MutationResult;
import io.github.west3436.zas.api.mutation.SourceId;
import io.github.west3436.zas.api.relocation.RelocationOutcome;
import io.github.west3436.zas.api.relocation.RelocationResult;
import io.github.west3436.zas.api.relocation.RelocationTicket;
import io.github.west3436.zas.api.relocation.Relocations;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Cabin air through a move (#11): a sealed room at 150 kPa, holding a mix that isn't standard air
 * at a temperature that isn't the ambient's, is moved by the mover with its default hand-over, ZAS³'s
 * relocation. It arrives with the same moles of each gas and the same temperature, and no breach
 * event fires. A move refused after the hand-over began, and one ZAS³ refuses, leave the air as it
 * was.
 *
 * <p>Only ZAS³'s simulation is used, through its API, never its content, so the tests pass with
 * {@code -Pcontent=off}. The room's walls are white concrete, which this source set's data gives a
 * heat transmittance of 0 (ZAS³'s HEAT-019): no heat crosses them, so the room's temperature is
 * its own in the Overworld and in the warmer Nether alike, and any change would be the move's.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CabinAirTests {
    private static final String BATCH = "cabin_air";

    /** As the mover's tests: a far chunk's entities can take a long time to load on a busy machine. */
    private static final int TIMEOUT = 20_000;

    private static final TicketType<ChunkPos> HELD =
            TicketType.create("expedition_gametest_cabin", Comparator.comparingLong(ChunkPos::toLong), TIMEOUT);

    private static final SourceId SOURCE = SourceId.of(ExpeditionMod.MOD_ID, "gametest/cabin_air");
    private static final ResourceLocation CO2 = ResourceLocation.fromNamespaceAndPath("zas", "carbon_dioxide");

    /** The room: 5 blocks a side, walls 1 thick, so 27 cells of air inside. */
    private static final int SIDE = 5;

    private static final int CELLS = 27;
    private static final BlockState WALL = Blocks.WHITE_CONCRETE.defaultBlockState();
    private static final double KPA = 150;

    /** The gas added: colder than the Overworld's 293.15 K and the Nether's 313.15 K. */
    private static final double FILL_TEMPERATURE = 278.15;

    private record Seen(ResourceKey<Level> level, ZoneId zone) {}

    private static final List<Seen> BREACHED = new CopyOnWriteArrayList<>();
    private static final List<ZoneRelocatedEvent> RELOCATED = new CopyOnWriteArrayList<>();

    static {
        NeoForge.EVENT_BUS.addListener(
                (ZoneBreachedEvent e) -> BREACHED.add(new Seen(e.level().dimension(), e.zone())));
        NeoForge.EVENT_BUS.addListener((ZoneRelocatedEvent e) -> RELOCATED.add(e));
    }

    private CabinAirTests() {}

    // ---- The tests ----------------------------------------------------------------------

    /**
     * The acceptance test: the room goes from the Overworld into the Nether, and arrives with the
     * same moles of each gas, the same temperature and its 27 cells, sealed; no breach event fires,
     * and one relocation event names where it came from and who moved it.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void cabin_air_goes_into_the_nether(GameTestHelper h) {
        ServerLevel overworld = h.getLevel();
        ServerLevel nether = level(h, Level.NETHER);
        BlockPos a = area(0);
        BlockPos b = new BlockPos(a.getX(), 64, a.getZ());
        carries(h, new MoveRequest(overworld, box(a), nether, b));
    }

    /** The same room moved 100 blocks east within the Overworld keeps its air and its zone's id. */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void cabin_air_goes_100_blocks_within_the_overworld(GameTestHelper h) {
        ServerLevel overworld = h.getLevel();
        BlockPos a = area(1);
        carries(h, MoveRequest.by(overworld, box(a), overworld, new Vec3i(100, 0, 0)));
    }

    /**
     * A move refused after ZAS³'s hand-over began cancels it: the room's zone was suspended and
     * carries on where it is, with its gas and temperature, unbreached.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void a_cancelled_handover_leaves_the_air(GameTestHelper h) {
        ServerLevel overworld = h.getLevel();
        BlockPos a = area(2);
        BlockPos b = a.offset(100, 0, 0);
        MoveRequest request = MoveRequest.by(overworld, box(a), overworld, new Vec3i(100, 0, 0));
        Run run = new Run();
        int[] calls = {0};
        prepare(h, run, overworld, a, overworld, b)
                .thenExecute(() -> {
                    run.mark = BREACHED.size();
                    // Asked once at the start and again once the hand-over has begun, when it refuses.
                    MoveCheck late = r -> ++calls[0] == 2 ? Optional.of("test_late_refusal") : Optional.empty();
                    RelocationHandover recording =
                            (from, box) -> run.ticket = ZasRelocationHandover.INSTANCE.begin(from, box);
                    run.job = ShipMover.start(
                            request,
                            MoveSettings.DEFAULT.withHandover(recording).withChecks(List.of(late)));
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(run.job.isDone(), "the refused move is still going");
                    h.assertTrue(run.ticket.ended().isDone(), "ZAS³ hasn't answered the cancel yet");
                })
                .thenExecute(() -> {
                    MoveResult result = run.job.result().join();
                    h.assertTrue(
                            result.refusal().equals(Optional.of("test_late_refusal")),
                            "the move's refusal was " + result.refusal());
                    h.assertTrue(
                            run.ticket.begun().join().isEmpty(),
                            "ZAS³ refused the begin: " + run.ticket.begun().join());
                    RelocationResult cancelled = run.ticket.ended().join();
                    h.assertTrue(
                            cancelled.outcome() == RelocationOutcome.CANCELLED,
                            "ZAS³ answered the cancel with " + cancelled.outcome());
                    h.assertTrue(
                            cancelled.zones().equals(Map.of(run.before.id(), run.before.id())),
                            "the cancel resumed " + cancelled.zones() + ", not the room's zone " + run.before.id());
                })
                .thenWaitUntil(() -> stayed(h, run, overworld, a, b))
                .thenExecute(() -> {
                    wipe(overworld, box(a));
                    wipe(overworld, box(b));
                })
                .thenSucceed();
    }

    /**
     * A room another mover is already moving: ZAS³ refuses the begin as {@code BUSY}, the move is
     * refused whole as {@code atmos_busy}, and once the other ticket is cancelled the air is as it
     * was.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void a_room_already_moving_refuses_as_atmos_busy(GameTestHelper h) {
        ServerLevel overworld = h.getLevel();
        BlockPos a = area(3);
        BlockPos b = a.offset(100, 0, 0);
        Run run = new Run();
        RelocationTicket[] other = new RelocationTicket[1];
        CompletableFuture<?>[] released = new CompletableFuture<?>[1];
        prepare(h, run, overworld, a, overworld, b)
                .thenExecute(() -> {
                    run.mark = BREACHED.size();
                    other[0] = Relocations.begin(
                            overworld, box(a), SourceId.of(ExpeditionMod.MOD_ID, "gametest/another_mover"));
                })
                .thenWaitUntil(() -> h.assertTrue(other[0].begun().isDone(), "the other mover hasn't begun"))
                .thenExecute(() -> {
                    RelocationOutcome began = other[0].begun().join().outcome();
                    h.assertTrue(began == RelocationOutcome.BEGUN, "the other mover's begin was " + began);
                    run.job = ShipMover.start(MoveRequest.by(overworld, box(a), overworld, new Vec3i(100, 0, 0)));
                })
                .thenWaitUntil(() -> h.assertTrue(run.job.isDone(), "the busy move is still going"))
                .thenExecute(() -> {
                    Optional<String> refusal = run.job.result().join().refusal();
                    h.assertTrue(
                            refusal.equals(Optional.of(MoveRefusal.ATMOS_BUSY)), "the move's refusal was " + refusal);
                    released[0] = Relocations.cancel(other[0]);
                })
                .thenWaitUntil(() -> h.assertTrue(released[0].isDone(), "the other mover's cancel is pending"))
                .thenWaitUntil(() -> stayed(h, run, overworld, a, b))
                .thenExecute(() -> {
                    wipe(overworld, box(a));
                    wipe(overworld, box(b));
                })
                .thenSucceed();
    }

    /**
     * A room docked to the ship, whose last wall is the ship's own, is opened to the air when the
     * ship leaves: ZAS³ sees the blocks the mover takes away, though no block's behaviour runs.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void a_room_docked_to_the_ship_opens_when_it_leaves(GameTestHelper h) {
        ServerLevel overworld = h.getLevel();
        BlockPos a = area(4);
        BlockPos b = a.offset(100, 0, 0);
        // The dock: a room west of the ship, sharing the ship's west wall.
        BlockPos dock = a.offset(-(SIDE - 1), 0, 0);
        ZoneId[] docked = new ZoneId[1];
        Run run = new Run();
        h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, overworld, box(dock));
                    hold(h, overworld, box(a));
                    hold(h, overworld, box(b));
                })
                .thenExecute(() -> {
                    wipe(overworld, box(b));
                    build(overworld, a);
                    BoundingBox box = box(dock);
                    for (BlockPos p : BlockPos.betweenClosed(
                            box.minX(), box.minY(), box.minZ(), box.maxX() - 1, box.maxY(), box.maxZ())) {
                        int x = p.getX() - dock.getX();
                        int y = p.getY() - dock.getY();
                        int z = p.getZ() - dock.getZ();
                        boolean wall = x == 0 || y == 0 || z == 0 || y == SIDE - 1 || z == SIDE - 1;
                        overworld.setBlock(p, wall ? WALL : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                    run.fill = seed(overworld, dock);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(run.fill.isDone(), "the gas hasn't landed in the dock");
                    if (run.fill.join().outcome() != MutationOutcome.ZONE) {
                        run.fill = seed(overworld, dock);
                        throw new GameTestAssertException("the dock isn't a zone yet");
                    }
                    ZoneInfo zone = Atmospheres.zoneAt(overworld, centre(dock)).orElse(null);
                    h.assertTrue(zone != null && zone.enclosed(), "the dock isn't sealed: " + zone);
                    docked[0] = zone.id();
                })
                .thenExecute(() -> {
                    run.mark = BREACHED.size();
                    run.job = ShipMover.start(MoveRequest.by(overworld, box(a), overworld, new Vec3i(100, 0, 0)));
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(run.job.isDone(), "the move is still going");
                    // The dock's zone opens to the air: it is breached, or gone into the open air.
                    ZoneInfo zone = Atmospheres.zone(overworld, docked[0]).orElse(null);
                    h.assertTrue(zone == null || zone.breached(), "the dock is still sealed: " + zone);
                    ZoneInfo there = Atmospheres.zoneAt(overworld, centre(dock)).orElse(null);
                    h.assertTrue(there == null || there.breached(), "the dock is sealed: " + there);
                })
                .thenExecute(() -> {
                    h.assertTrue(run.job.result().join().moved(), "the ship didn't move");
                    wipe(overworld, box(dock));
                    wipe(overworld, box(b));
                })
                .thenSucceed();
    }

    // ---- The moves ----------------------------------------------------------------------

    /** Builds and fills the room, moves it with the default settings, and checks its air arrived. */
    private static void carries(GameTestHelper h, MoveRequest request) {
        ServerLevel from = request.from();
        ServerLevel to = request.to();
        BlockPos a = new BlockPos(
                request.box().minX(), request.box().minY(), request.box().minZ());
        BlockPos b = request.origin();
        Run run = new Run();
        prepare(h, run, from, a, to, b)
                .thenExecute(() -> {
                    h.assertTrue(
                            MoveSettings.DEFAULT.handover() == ZasRelocationHandover.INSTANCE,
                            "the default hand-over is " + MoveSettings.DEFAULT.handover());
                    run.mark = BREACHED.size();
                    run.relocatedMark = RELOCATED.size();
                    wipe(to, box(b));
                    run.job = ShipMover.start(request);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(run.job.isDone(), "the move is still going");
                    h.assertFalse(Relocations.isMoving(to, middle(b)), "the room's air hasn't arrived");
                    run.arrived = arrival(run);
                    h.assertTrue(run.arrived != null, "no relocation event for zone " + run.before.id());
                    ZoneInfo there = Atmospheres.zoneAt(to, centre(b)).orElse(null);
                    h.assertTrue(
                            there != null && there.id().equals(run.arrived.zone()),
                            "the zone at the destination is " + there + ", not " + run.arrived.zone());
                })
                .thenExecute(() -> {
                    MoveResult result = run.job.result().join();
                    h.assertTrue(result.moved(), "the move was refused: " + result.refusal());
                    ZoneInfo after = Atmospheres.zoneAt(to, centre(b)).orElseThrow();
                    same(h, run.before, after, "at the destination");
                    if (request.sameLevel()) {
                        h.assertTrue(after.id().equals(run.before.id()), "the zone's id changed within a level");
                    }
                    h.assertTrue(run.arrived.level() == to, "the relocation event fired in " + run.arrived.level());
                    h.assertTrue(
                            run.arrived.fromDimension() == from.dimension(),
                            "the relocation event says it came from " + run.arrived.fromDimension());
                    h.assertTrue(
                            run.arrived.mover().equals(ZasRelocationHandover.MOVER),
                            "the relocation event names the mover " + run.arrived.mover());
                    h.assertTrue(
                            Atmospheres.zoneAt(from, centre(a)).isEmpty(),
                            "a zone is left where the room was: "
                                    + Atmospheres.zoneAt(from, centre(a)).orElse(null));
                    noBreach(h, run, new Seen(from.dimension(), run.before.id()), new Seen(to.dimension(), after.id()));
                    wipe(from, box(a));
                    wipe(to, box(b));
                })
                .thenSucceed();
    }

    /**
     * Loads both ends, builds the room at {@code a}, and fills it to 150 kPa with a mix that isn't
     * standard air; {@code run.before} is then the room's zone, as the sequence it returns goes on.
     */
    private static GameTestSequence prepare(
            GameTestHelper h, Run run, ServerLevel from, BlockPos a, ServerLevel to, BlockPos b) {
        return h.startSequence()
                .thenWaitUntil(() -> {
                    hold(h, from, box(a));
                    hold(h, to, box(b));
                })
                .thenExecute(() -> {
                    // A clear destination, so the mover's own check for blocks in the way passes.
                    wipe(to, box(b));
                    build(from, a);
                    run.fill = seed(from, a);
                })
                .thenWaitUntil(() -> {
                    // The first gas may land before the room is a zone, and go to the open air; then
                    // the room is asked again.
                    h.assertTrue(run.fill.isDone(), "the first gas hasn't landed");
                    if (run.fill.join().outcome() != MutationOutcome.ZONE) {
                        run.fill = seed(from, a);
                        throw new GameTestAssertException("the room isn't a zone yet");
                    }
                    ZoneInfo zone = Atmospheres.zoneAt(from, centre(a)).orElse(null);
                    h.assertTrue(zone != null && zone.enclosed() && !zone.paused(), "the room isn't sealed: " + zone);
                })
                .thenExecute(() -> {
                    ZoneInfo zone = Atmospheres.zoneAt(from, centre(a)).orElseThrow();
                    h.assertTrue(zone.cellCount() == CELLS, "the room has " + zone.cellCount() + " cells");
                    run.fill = GasMutations.add(from, centre(a), mix(zone, FILL_TEMPERATURE), SOURCE);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(run.fill.isDone(), "the gas hasn't landed");
                    MutationOutcome went = run.fill.join().outcome();
                    if (went != MutationOutcome.ZONE) {
                        throw new GameTestAssertException("the gas went to " + went);
                    }
                    ZoneInfo zone = Atmospheres.zoneAt(from, centre(a)).orElseThrow();
                    if (Math.abs(zone.mixture().pressure() - KPA) >= 0.01) {
                        // The cold gas cooled the room, so it fell short: the rest goes in at the
                        // room's own temperature, which lands on 150 kPa.
                        run.fill = GasMutations.add(
                                from, centre(a), mix(zone, zone.mixture().temperature()), SOURCE);
                        throw new GameTestAssertException(
                                "the room is at " + zone.mixture().pressure() + " kPa");
                    }
                    run.before = zone;
                })
                .thenExecute(() -> {
                    double t = run.before.mixture().temperature();
                    h.assertTrue(
                            t < StandardAir.TEMPERATURE - 1, "the room is at " + t + " K, the ambient's temperature");
                    h.assertTrue(
                            run.before.mixture().moles(CO2)
                                    > 0.05 * run.before.mixture().totalMoles(),
                            "the room's mix is " + run.before.mixture().moles());
                });
    }

    /**
     * The gas that takes a zone to 150 kPa if it goes in at the zone's temperature: a quarter of it
     * carbon dioxide, far above air's trace, and the rest more oxygen than nitrogen.
     */
    private static GasParcel mix(ZoneInfo zone, double temperature) {
        double now = zone.mixture().totalMoles();
        double more = now * KPA / zone.mixture().pressure() - now;
        double co2 = more * 0.25;
        return new GasParcel(
                Map.of(CO2, co2, StandardAir.OXYGEN, (more - co2) * 0.6, StandardAir.NITROGEN, (more - co2) * 0.4),
                temperature);
    }

    /** The room is where it was, as it was, and nothing arrived at {@code b}. */
    private static void stayed(GameTestHelper h, Run run, ServerLevel level, BlockPos a, BlockPos b) {
        h.assertFalse(Relocations.isMoving(level, middle(a)), "the room is still held for a move");
        ZoneInfo now = Atmospheres.zoneAt(level, centre(a)).orElse(null);
        h.assertTrue(now != null, "the room has no zone");
        h.assertTrue(now.id().equals(run.before.id()), "the room's zone is " + now.id() + ", not " + run.before.id());
        same(h, run.before, now, "after the refusal");
        h.assertTrue(level.getBlockState(a).equals(WALL), "the room's corner is " + level.getBlockState(a));
        h.assertTrue(level.getBlockState(b).isAir(), "something arrived: " + level.getBlockState(b));
        h.assertTrue(Atmospheres.zoneAt(level, centre(b)).isEmpty(), "a zone is at the destination");
        noBreach(h, run, new Seen(level.dimension(), run.before.id()));
    }

    // ---- The room -----------------------------------------------------------------------

    /** One test's room as it goes. */
    private static final class Run {
        CompletableFuture<MutationResult> fill;
        ZoneInfo before;
        MoveJob job;
        ZasRelocationHandover.ZasTicket ticket;
        ZoneRelocatedEvent arrived;
        int mark;
        int relocatedMark;
    }

    /** A test's area: chunk-aligned, apart from the mover's tests' and from each other's. */
    private static BlockPos area(int index) {
        return new BlockPos(-20_480 + 512 * index, 100, -12_288);
    }

    private static BoundingBox box(BlockPos corner) {
        return BoundingBox.fromCorners(corner, corner.offset(SIDE - 1, SIDE - 1, SIDE - 1));
    }

    /** The middle of the room's air. */
    private static BlockPos middle(BlockPos corner) {
        return corner.offset(SIDE / 2, SIDE / 2, SIDE / 2);
    }

    private static Vec3 centre(BlockPos corner) {
        return Vec3.atCenterOf(middle(corner));
    }

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

    /** A hollow cube of white concrete with its lowest corner at {@code corner}, air inside. */
    private static void build(ServerLevel level, BlockPos corner) {
        BoundingBox box = box(corner);
        for (BlockPos p :
                BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            int x = p.getX() - corner.getX();
            int y = p.getY() - corner.getY();
            int z = p.getZ() - corner.getZ();
            boolean wall = x == 0 || y == 0 || z == 0 || x == SIDE - 1 || y == SIDE - 1 || z == SIDE - 1;
            level.setBlock(p, wall ? WALL : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** A little carbon dioxide, which makes the room a zone if it isn't one yet. */
    private static CompletableFuture<MutationResult> seed(ServerLevel level, BlockPos corner) {
        return GasMutations.add(level, centre(corner), new GasParcel(Map.of(CO2, 0.01), FILL_TEMPERATURE), SOURCE);
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

    // ---- What the air must be -----------------------------------------------------------

    /** The relocation event for the room's zone since the move began, or null. */
    private static ZoneRelocatedEvent arrival(Run run) {
        for (ZoneRelocatedEvent e : since(RELOCATED, run.relocatedMark)) {
            if (e.from().equals(run.before.id())) {
                return e;
            }
        }
        return null;
    }

    /** A zone holds what it held before: its cells, each gas's moles and its temperature, sealed. */
    private static void same(GameTestHelper h, ZoneInfo before, ZoneInfo after, String where) {
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
        h.assertTrue(after.enclosed(), where + ": the room is breached");
        h.assertFalse(after.paused(), where + ": the room is paused");
    }

    private static void noBreach(GameTestHelper h, Run run, Seen... zones) {
        List<Seen> breached = since(BREACHED, run.mark);
        for (Seen zone : zones) {
            h.assertFalse(breached.contains(zone), "a breach event fired for zone " + zone);
        }
    }

    /** What a list of events has gained since it was {@code mark} long. */
    private static <T> List<T> since(List<T> events, int mark) {
        List<T> now = List.copyOf(events);
        return now.subList(mark, now.size());
    }
}
