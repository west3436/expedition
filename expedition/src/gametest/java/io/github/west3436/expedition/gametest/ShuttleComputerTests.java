package io.github.west3436.expedition.gametest;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.drive.DriveRefusal;
import io.github.west3436.expedition.drive.Plot;
import io.github.west3436.expedition.drive.Trip;
import io.github.west3436.expedition.registry.ExpeditionBlocks;
import io.github.west3436.expedition.ship.ShipBox;
import io.github.west3436.expedition.ship.ShipComputers;
import io.github.west3436.expedition.ship.ShipMass;
import io.github.west3436.expedition.ship.ShipStatus;
import io.github.west3436.expedition.ship.ShuttleComputerBlockEntity;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The Shuttle Computer and its ship's box (#27): placing it, setting and reading the box, fitting it
 * to the ship, and the mass and thrust the window shows.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ShuttleComputerTests {
    private static final double EPSILON = 1e-9;

    private ShuttleComputerTests() {}

    /** A placed computer knows its owner, and its box and mass follow the distances set on it. */
    @GameTest(template = "empty")
    public static void a_placed_computer_shows_its_box_and_mass(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Player player = h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        BlockPos rel = new BlockPos(2, 2, 2);
        ShuttleComputerBlockEntity computer = place(h, rel, Direction.NORTH);
        BlockPos pos = computer.getBlockPos();
        BlockState state = level.getBlockState(pos);
        state.getBlock()
                .setPlacedBy(level, pos, state, player, new ItemStack(ExpeditionBlocks.SHUTTLE_COMPUTER_ITEM.get()));
        check(player.getUUID().equals(computer.owner().orElse(null)), "the placer isn't the owner");

        // The computer alone: its own weight, 1, and no thrust.
        ShipStatus alone = ShipComputers.status(level, computer);
        check(alone.world().equals(BoundingBox.fromCorners(pos, pos)), "the box isn't the computer: " + alone.world());
        near(1, alone.mass(), "the computer alone");
        near(0, alone.thrust(), "thrust with no drives");
        check(alone.drives() == 0, "drives with none");

        // Two iron blocks ahead of the screen (north), one plank to the pilot's left (east).
        h.setBlock(rel.north(), Blocks.IRON_BLOCK);
        h.setBlock(rel.north(2), Blocks.IRON_BLOCK);
        h.setBlock(rel.east(), Blocks.OAK_PLANKS);
        ShipComputers.setBox(computer, new ShipBox(2, 0, 1, 0, 0, 0));
        ShipStatus status = ShipComputers.status(level, computer);
        BoundingBox expected = BoundingBox.fromCorners(pos.north(2), pos.east());
        check(status.world().equals(expected), "the box is " + status.world() + ", not " + expected);
        near(1 + 4 + 4 + 0.5, status.mass(), "the ship");
        near(ShipMass.of(level, expected), status.mass(), "the ship against ShipMass");
        check(status.terrain() == 0, "terrain where there is none");
        check(status.overlap().isEmpty(), "an overlap with no other ship");
        check(!status.tooLarge(), "a small box over the ceiling");
        h.succeed();
    }

    /** Fitting the box follows the blocks joined to the computer, and not the ground under them. */
    @GameTest(template = "empty")
    public static void fitting_follows_the_ship_and_not_the_ground(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // A floor of stone, natural ground, with a ship of planks on it.
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                h.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        BlockPos rel = new BlockPos(3, 3, 3);
        ShuttleComputerBlockEntity computer = place(h, rel, Direction.SOUTH);
        h.setBlock(rel.below(), Blocks.OAK_PLANKS);
        h.setBlock(rel.below().west(), Blocks.OAK_PLANKS);
        h.setBlock(rel.below().west(2), Blocks.OAK_PLANKS);
        h.setBlock(rel.west(2).above(), Blocks.GLASS);
        h.setBlock(rel.west(2), Blocks.OAK_PLANKS);

        Optional<Integer> fitted = ShipComputers.fit(level, computer);
        check(fitted.equals(Optional.of(6)), "fitted to " + fitted + " blocks, not 6");
        BlockPos pos = computer.getBlockPos();
        BoundingBox expected = BoundingBox.fromCorners(pos.below().west(2), pos.above());
        check(computer.worldBox().equals(expected), "fitted to " + computer.worldBox() + ", not " + expected);
        ShipStatus status = ShipComputers.status(level, computer);
        check(status.terrain() == 0, "the fitted box holds ground");

        // Grown down by one to take in the floor: allowed, warned about, and counted.
        ShipBox box = computer.box();
        ShipComputers.setBox(computer, new ShipBox(box.front(), box.back(), box.left(), box.right(), box.up(), 2));
        ShipStatus grounded = ShipComputers.status(level, computer);
        check(grounded.terrain() == 3, "the box holds " + grounded.terrain() + " ground blocks, not 3");
        near(status.mass() + 3, grounded.mass(), "the ship with ground");
        h.succeed();
    }

    /** Two ships whose boxes overlap can't move: the plot is refused. */
    @GameTest(template = "empty")
    public static void overlapping_ships_are_refused(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ShuttleComputerBlockEntity a = place(h, new BlockPos(1, 2, 1), Direction.NORTH);
        ShuttleComputerBlockEntity b = place(h, new BlockPos(5, 2, 1), Direction.NORTH);
        check(ShipComputers.overlapping(a).isEmpty(), "apart, they overlap");
        Plot apart = ShipComputers.plot(level, a, Trip.of(10, 0, 0));
        check(
                apart.refusal().equals(Optional.of(DriveRefusal.NO_DRIVE)),
                "apart, a ship with no drive is refused for " + apart.refusal());

        // A's box reaches to its left (east) over B.
        ShipComputers.rename(b, "Beta");
        ShipComputers.setBox(a, new ShipBox(0, 0, 4, 0, 0, 0));
        check(ShipComputers.overlapping(a).orElse(null) == b, "A's box doesn't overlap B");
        check(ShipComputers.overlapping(b).orElse(null) == a, "B's box doesn't overlap A");
        check(
                ShipComputers.status(level, a).overlap().equals(Optional.of("Beta")),
                "the window doesn't name the other ship");
        Plot plot = ShipComputers.plot(level, a, Trip.of(10, 0, 0));
        check(plot.refusal().equals(Optional.of(ShipComputers.OVERLAP)), "the plot is refused for " + plot.refusal());

        // Gone, it no longer counts.
        h.setBlock(new BlockPos(5, 2, 1), Blocks.AIR);
        check(ShipComputers.overlapping(a).isEmpty(), "a removed computer still overlaps");
        h.succeed();
    }

    /** The box, name and owner survive a save and load. */
    @GameTest(template = "empty")
    public static void the_computer_keeps_its_ship(GameTestHelper h) {
        ShuttleComputerBlockEntity computer = place(h, new BlockPos(1, 2, 1), Direction.WEST);
        computer.setOwner(java.util.UUID.randomUUID(), "Pilot");
        ShipComputers.rename(computer, "  Kestrel  ");
        ShipComputers.setBox(computer, new ShipBox(1, 2, 3, 4, 5, 6));
        var saved = computer.saveWithoutMetadata(h.getLevel().registryAccess());
        ShuttleComputerBlockEntity copy =
                new ShuttleComputerBlockEntity(computer.getBlockPos(), computer.getBlockState());
        copy.loadWithComponents(saved, h.getLevel().registryAccess());
        check(copy.name().equals("Kestrel"), "the name is '" + copy.name() + "'");
        check(copy.ownerName().equals("Pilot"), "the owner is " + copy.ownerName());
        check(copy.owner().equals(computer.owner()), "the owner's id changed");
        check(copy.box().equals(new ShipBox(1, 2, 3, 4, 5, 6)), "the box is " + copy.box());
        h.succeed();
    }

    /** Places a computer facing a way, at a position relative to the test. */
    private static ShuttleComputerBlockEntity place(GameTestHelper h, BlockPos rel, Direction facing) {
        h.setBlock(
                rel,
                ExpeditionBlocks.SHUTTLE_COMPUTER
                        .get()
                        .defaultBlockState()
                        .setValue(HorizontalDirectionalBlock.FACING, facing));
        if (!(h.getBlockEntity(rel) instanceof ShuttleComputerBlockEntity computer)) {
            throw new GameTestAssertException("no computer's block entity at " + rel);
        }
        return computer;
    }

    private static void near(double expected, double actual, String what) {
        check(Math.abs(expected - actual) < EPSILON, what + " weighs " + actual + ", not " + expected);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }
}
