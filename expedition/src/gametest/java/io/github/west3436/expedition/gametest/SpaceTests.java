package io.github.west3436.expedition.gametest;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.api.planet.PlanetGravity;
import io.github.west3436.expedition.api.planet.Planets;
import io.github.west3436.expedition.api.space.Space;
import io.github.west3436.ss13.api.gravity.Gravity;
import io.github.west3436.zas.api.atmosphere.AtmosphereSample;
import io.github.west3436.zas.api.atmosphere.AtmosphereState;
import io.github.west3436.zas.api.atmosphere.Atmospheres;
import io.github.west3436.zas.api.atmosphere.ZoneInfo;
import io.github.west3436.zas.api.gas.GasParcel;
import io.github.west3436.zas.api.gas.StandardAir;
import io.github.west3436.zas.api.mutation.GasMutations;
import io.github.west3436.zas.api.mutation.MutationOutcome;
import io.github.west3436.zas.api.mutation.MutationResult;
import io.github.west3436.zas.api.mutation.SourceId;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The Space dimension (#22): it is a level on the server with no terrain and no gravity, ZAS³ reads
 * its open air as a vacuum at 2.7 K, and a sealed room built in it holds its air.
 *
 * <p>Only ZAS³'s simulation is used, through its API, never its content, so the tests pass with
 * {@code -Pcontent=off}. The room's walls are white concrete, which this source set's data gives a
 * heat transmittance of 0, so the room keeps its temperature against the cold of Space and any loss
 * of pressure would be a leak.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SpaceTests {
    private static final String BATCH = "space";

    /** Long enough for chunks and a zone to come up on a busy GameTest server. */
    private static final int TIMEOUT = 2400;

    /** How long the filled room is watched for a leak. */
    private static final int HOLD_TICKS = 200;

    private static final SourceId SOURCE = SourceId.of(ExpeditionMod.MOD_ID, "gametest/space");

    /** The room: 5 blocks a side, walls 1 thick, so 27 cells of air inside. */
    private static final int SIDE = 5;

    private static final int CELLS = 27;
    private static final BlockState WALL = Blocks.WHITE_CONCRETE.defaultBlockState();

    /** The pressure the room is filled to: standard air's. */
    private static final double KPA = 101.325;

    private SpaceTests() {}

    /** Space is a level, no planet, with nothing in it: no ground anywhere in a column. */
    @GameTest(template = "empty", batch = BATCH)
    public static void space_is_an_empty_level(GameTestHelper h) {
        ServerLevel space = space(h);
        h.assertTrue(Space.is(space), "Space.is says the level isn't Space");
        h.assertFalse(Space.is(h.getLevel()), "Space.is says the Overworld is Space");
        h.assertTrue(Planets.forLevel(space).isEmpty(), "Space is a planet");
        int x = 4_096 + 8;
        int z = -4_096 + 8;
        space.getChunk(new BlockPos(x, 0, z));
        for (int y = space.getMinBuildHeight(); y < space.getMaxBuildHeight(); y++) {
            BlockPos p = new BlockPos(x, y, z);
            h.assertTrue(space.getBlockState(p).isAir(), "Space has " + space.getBlockState(p) + " at " + p);
        }
        h.succeed();
    }

    /** ZAS³ reads Space's open air as its ambient file gives it: a vacuum at 2.7 K. */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void zas_reads_a_vacuum_in_space(GameTestHelper h) {
        ServerLevel space = space(h);
        BlockPos at = new BlockPos(8, 100, 8);
        space.setChunkForced(0, 0, true);
        h.startSequence()
                .thenWaitUntil(() -> {
                    AtmosphereSample air = Atmospheres.at(space, at);
                    h.assertValueEqual(air.state(), AtmosphereState.AMBIENT, "the state of Space's open air");
                    h.assertTrue(air.pressure() == 0, "Space's open air is at " + air.pressure() + " kPa");
                    h.assertTrue(air.partialPressures().isEmpty(), "Space's open air holds " + air.partialPressures());
                    h.assertTrue(
                            Math.abs(air.temperature() - Space.TEMPERATURE) < 1e-6,
                            "Space's open air is at " + air.temperature() + " K");
                })
                .thenExecute(() -> space.setChunkForced(0, 0, false))
                .thenSucceed();
    }

    /**
     * Space has no gravity: a living thing there carries the planet gravity modifier for a gravity
     * of 0, and so does what has no attribute, such as a dropped item.
     */
    @GameTest(template = "empty", batch = BATCH)
    public static void space_has_no_gravity(GameTestHelper h) {
        ServerLevel space = space(h);
        h.assertValueEqual(PlanetGravity.of(space), Space.GRAVITY, "Space's gravity");
        BlockPos at = new BlockPos(16 * 3 + 8, 100, 8);
        space.getChunk(at);
        Pig pig = EntityType.PIG.create(space);
        if (pig == null) {
            throw new GameTestAssertException("no pig");
        }
        pig.moveTo(Vec3.atBottomCenterOf(at));
        pig.setNoAi(true);
        h.assertTrue(space.addFreshEntity(pig), "the pig wasn't added to Space");
        try {
            h.assertTrue(
                    pig.getAttribute(Attributes.GRAVITY).getModifier(Gravity.MODIFIER_ID) != null,
                    "the pig in Space has no gravity modifier");
            h.assertTrue(pig.getGravity() == 0, "the pig's gravity in Space is " + pig.getGravity());
        } finally {
            pig.discard();
        }
        ItemEntity item = new ItemEntity(space, at.getX(), at.getY(), at.getZ(), new ItemStack(Items.STONE));
        h.assertTrue(item.getGravity() == 0, "an item's gravity in Space is " + item.getGravity());
        h.succeed();
    }

    /**
     * A sealed room built in Space and filled with standard air holds it: after {@value
     * #HOLD_TICKS} ticks it is still one sealed zone of 27 cells at the same pressure, with the
     * vacuum outside its walls.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void a_sealed_room_in_space_holds_air(GameTestHelper h) {
        ServerLevel space = space(h);
        BlockPos corner = new BlockPos(-16 * 4 + 4, 100, 4);
        BlockPos outside = corner.offset(-2, SIDE / 2, SIDE / 2);
        Room room = new Room();
        int chunkX = corner.getX() >> 4;
        int chunkZ = corner.getZ() >> 4;
        space.setChunkForced(chunkX, chunkZ, true);
        h.startSequence()
                .thenExecute(() -> {
                    build(space, corner);
                    room.fill = add(space, corner, 0.01);
                })
                .thenWaitUntil(() -> {
                    // The first gas may land before the room is a zone, and go to the open air; then
                    // the room is asked again.
                    h.assertTrue(room.fill.isDone(), "the first gas hasn't landed");
                    if (room.fill.join().outcome() != MutationOutcome.ZONE) {
                        room.fill = add(space, corner, 0.01);
                        throw new GameTestAssertException("the room isn't a zone yet");
                    }
                    ZoneInfo zone = Atmospheres.zoneAt(space, centre(corner)).orElse(null);
                    h.assertTrue(zone != null && zone.enclosed() && !zone.paused(), "the room isn't sealed: " + zone);
                    h.assertTrue(zone.cellCount() == CELLS, "the room has " + zone.cellCount() + " cells");
                    double now = zone.mixture().totalMoles();
                    room.fill = add(space, corner, now * KPA / zone.mixture().pressure() - now);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(room.fill.isDone(), "the air hasn't landed");
                    h.assertValueEqual(room.fill.join().outcome(), MutationOutcome.ZONE, "where the air went");
                    ZoneInfo zone = Atmospheres.zoneAt(space, centre(corner)).orElseThrow();
                    h.assertTrue(
                            Math.abs(zone.mixture().pressure() - KPA) < 1,
                            "the room is at " + zone.mixture().pressure() + " kPa");
                    room.before = zone;
                    room.at = h.getTick();
                })
                .thenWaitUntil(() -> h.assertTrue(
                        h.getTick() >= room.at + HOLD_TICKS, "the room has been full for " + (h.getTick() - room.at)))
                .thenExecute(() -> {
                    ZoneInfo after = Atmospheres.zoneAt(space, centre(corner)).orElse(null);
                    h.assertTrue(after != null, "the room has no zone");
                    h.assertTrue(after.id().equals(room.before.id()), "the room's zone changed");
                    h.assertTrue(after.enclosed() && !after.paused(), "the room is breached or paused: " + after);
                    h.assertTrue(after.cellCount() == CELLS, "the room has " + after.cellCount() + " cells");
                    double was = room.before.mixture().totalMoles();
                    double is = after.mixture().totalMoles();
                    h.assertTrue(Math.abs(is - was) <= 1e-6 * was, "the room held " + was + " mol, holds " + is);
                    h.assertTrue(
                            Math.abs(after.mixture().pressure()
                                            - room.before.mixture().pressure())
                                    < 0.01,
                            "the room's pressure went from "
                                    + room.before.mixture().pressure() + " to "
                                    + after.mixture().pressure() + " kPa");
                    AtmosphereSample out = Atmospheres.at(space, outside);
                    h.assertValueEqual(out.state(), AtmosphereState.AMBIENT, "the state outside the room");
                    h.assertTrue(out.pressure() == 0, "outside the room is at " + out.pressure() + " kPa");
                    wipe(space, corner);
                    space.setChunkForced(chunkX, chunkZ, false);
                })
                .thenSucceed();
    }

    // ---- Helpers ------------------------------------------------------------------------

    private static final class Room {
        CompletableFuture<MutationResult> fill;
        ZoneInfo before;
        long at;
    }

    private static ServerLevel space(GameTestHelper h) {
        ServerLevel space = h.getLevel().getServer().getLevel(Space.DIMENSION);
        if (space == null) {
            throw new GameTestAssertException("Space isn't a level on the server");
        }
        return space;
    }

    /** A hollow cube of white concrete with its lowest corner at {@code corner}, air inside. */
    private static void build(ServerLevel level, BlockPos corner) {
        for (BlockPos p : BlockPos.betweenClosed(corner, corner.offset(SIDE - 1, SIDE - 1, SIDE - 1))) {
            int x = p.getX() - corner.getX();
            int y = p.getY() - corner.getY();
            int z = p.getZ() - corner.getZ();
            boolean wall = x == 0 || y == 0 || z == 0 || x == SIDE - 1 || y == SIDE - 1 || z == SIDE - 1;
            level.setBlock(p, wall ? WALL : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    private static void wipe(ServerLevel level, BlockPos corner) {
        for (BlockPos p : BlockPos.betweenClosed(corner, corner.offset(SIDE - 1, SIDE - 1, SIDE - 1))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    private static Vec3 centre(BlockPos corner) {
        return Vec3.atCenterOf(corner.offset(SIDE / 2, SIDE / 2, SIDE / 2));
    }

    /** Standard air's mix, this many moles of it, at room temperature, into the room. */
    private static CompletableFuture<MutationResult> add(ServerLevel level, BlockPos corner, double moles) {
        return GasMutations.add(
                level,
                centre(corner),
                new GasParcel(
                        Map.of(StandardAir.OXYGEN, moles * 0.21, StandardAir.NITROGEN, moles * 0.79),
                        StandardAir.TEMPERATURE),
                SOURCE);
    }
}
