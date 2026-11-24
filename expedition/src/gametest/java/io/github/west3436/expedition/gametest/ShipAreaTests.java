package io.github.west3436.expedition.gametest;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.mover.MoveJob;
import io.github.west3436.expedition.mover.MoveRequest;
import io.github.west3436.expedition.mover.ShipMover;
import io.github.west3436.expedition.registry.ExpeditionBlocks;
import io.github.west3436.expedition.ship.ShipAreas;
import io.github.west3436.expedition.ship.ShipBox;
import io.github.west3436.expedition.ship.ShipComputers;
import io.github.west3436.expedition.ship.ShuttleComputerBlockEntity;
import io.github.west3436.ss13.api.area.Area;
import io.github.west3436.ss13.api.area.AreaClaim;
import io.github.west3436.ss13.api.area.AreaStore;
import io.github.west3436.ss13.api.area.Areas;
import io.github.west3436.ss13.api.area.AreasChangedEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A ship's box is an SS13 Framework area (ss13-framework#11): a {@link Areas#REGION} answered from
 * its Shuttle Computer, under the rooms drawn inside it, which follows the box when it is resized,
 * takes the ship's name, and keeps its id through a save and a move.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ShipAreaTests {
    /** Every area change heard since the first test began, from every test running beside these. */
    private static final List<AreasChangedEvent> HEARD = new CopyOnWriteArrayList<>();

    private static final AtomicBoolean LISTENING = new AtomicBoolean();

    private ShipAreaTests() {}

    /**
     * The box is the ship's area, named after the ship, and nothing outside it is; a room drawn
     * inside wins over it; resizing, renaming and removing the computer each say where the area
     * changed.
     */
    @GameTest(template = "empty")
    public static void a_ship_box_is_a_region_area(GameTestHelper h) {
        listen();
        ServerLevel level = h.getLevel();
        ShuttleComputerBlockEntity computer = place(h, new BlockPos(2, 2, 2));
        BlockPos pos = computer.getBlockPos();
        ShipComputers.setBox(computer, new ShipBox(1, 1, 1, 1, 1, 1));
        BlockPos inside = pos.north();
        ResourceLocation id = ShipAreas.areaId(computer.shipId());

        Optional<Area> area = Areas.at(level, inside);
        check(area.equals(Optional.of(new Area(id, ShipAreas.UNNAMED))), "the box's area is " + area);
        check(shipClaim(level, pos.north(3)).isEmpty(), "a block outside the box is in the ship");
        AreaClaim claim = shipClaim(level, inside).orElseThrow(() -> new GameTestAssertException("no claim"));
        check(claim.priority() == Areas.REGION, "the ship's priority is " + claim.priority());

        // Renamed: the same area by another name, and said so.
        int heard = HEARD.size();
        ShipComputers.rename(computer, "Heron");
        check(Areas.at(level, inside).equals(Optional.of(new Area(id, "Heron"))), "renamed, it is " + area);
        check(heardAt(heard, level, pos), "renaming the ship said nothing");

        // Resized: the area follows the box, and the blocks it now takes in are named.
        heard = HEARD.size();
        BlockPos further = pos.north(3);
        ShipComputers.setBox(computer, new ShipBox(3, 1, 1, 1, 1, 1));
        check(shipClaim(level, further).isPresent(), "the grown box's new block isn't in the ship");
        check(heardAt(heard, level, further), "growing the box said nothing about its new blocks");

        // A room drawn inside the ship wins there, and the ship is under it.
        AreaStore store = Areas.store(level);
        ResourceLocation bridge = ResourceLocation.fromNamespaceAndPath(
                ExpeditionMod.MOD_ID,
                "test/bridge_" + UUID.randomUUID().toString().substring(0, 8));
        store.define(bridge, "Bridge");
        try {
            store.assign(bridge, new BoundingBox(inside));
            List<AreaClaim> claims = Areas.claims(level, inside);
            check(claims.size() >= 2, "the bridge's block has claims " + claims);
            check(claims.get(0).area().id().equals(bridge), "the bridge isn't first: " + claims);
            check(shipClaim(level, inside).isPresent(), "the ship isn't under the bridge: " + claims);
        } finally {
            store.remove(bridge);
        }

        // Removed: no ship there any more, and said so.
        heard = HEARD.size();
        h.setBlock(new BlockPos(2, 2, 2), Blocks.AIR);
        check(shipClaim(level, inside).isEmpty(), "a removed computer's box is still a ship");
        check(heardAt(heard, level, inside), "removing the computer said nothing");
        h.succeed();
    }

    /**
     * The ship's area keeps its id through a save and load, and through a move, which carries the
     * computer: the area is where the ship arrived, and not where it left. A creative copy of the
     * computer doesn't take the id.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void the_ship_area_keeps_its_id_through_a_save_and_a_move(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ShuttleComputerBlockEntity computer = place(h, new BlockPos(1, 2, 1));
        BlockPos from = computer.getBlockPos();
        UUID shipId = computer.shipId();
        ResourceLocation id = ShipAreas.areaId(shipId);

        CompoundTag saved = computer.saveWithoutMetadata(level.registryAccess());
        ShuttleComputerBlockEntity copy = new ShuttleComputerBlockEntity(from, computer.getBlockState());
        copy.loadWithComponents(saved, level.registryAccess());
        check(copy.shipId().equals(shipId), "the ship's id changed in a save: " + copy.shipId());
        CompoundTag picked = computer.saveCustomOnly(level.registryAccess());
        computer.removeComponentsFromTag(picked);
        check(!picked.contains("ship_id"), "a picked copy keeps the ship's id: " + picked);

        BlockPos to = from.offset(3, 0, 0);
        MoveJob job = ShipMover.start(MoveRequest.by(level, new BoundingBox(from), level, new Vec3i(3, 0, 0)));
        h.succeedWhen(() -> {
            check(job.isDone(), "the move is still going");
            check(
                    job.result().join().moved(),
                    "the ship didn't move: " + job.result().join());
            Optional<Area> there = Areas.at(level, to);
            check(there.isPresent() && there.get().id().equals(id), "where the ship arrived, the area is " + there);
            check(shipClaim(level, from).isEmpty(), "where the ship left is still in a ship");
        });
    }

    private static void listen() {
        if (LISTENING.compareAndSet(false, true)) {
            NeoForge.EVENT_BUS.addListener(AreasChangedEvent.class, HEARD::add);
        }
    }

    /** Whether an area change heard since {@code since} covers a block. */
    private static boolean heardAt(int since, ServerLevel level, BlockPos pos) {
        return HEARD.subList(since, HEARD.size()).stream().anyMatch(e -> e.level() == level && e.affects(pos));
    }

    /** The ship provider's claim at a block, if it has one. */
    private static Optional<AreaClaim> shipClaim(ServerLevel level, BlockPos pos) {
        return Areas.claims(level, pos).stream()
                .filter(c -> c.provider().equals(ShipAreas.PROVIDER))
                .findFirst();
    }

    /** Places a computer facing north, at a position relative to the test. */
    private static ShuttleComputerBlockEntity place(GameTestHelper h, BlockPos rel) {
        h.setBlock(
                rel,
                ExpeditionBlocks.SHUTTLE_COMPUTER
                        .get()
                        .defaultBlockState()
                        .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
        if (!(h.getBlockEntity(rel) instanceof ShuttleComputerBlockEntity computer)) {
            throw new GameTestAssertException("no computer's block entity at " + rel);
        }
        return computer;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }
}
