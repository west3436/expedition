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
import io.github.west3436.ss13.api.label.Labelled;
import io.github.west3436.ss13.api.label.Labels;
import io.github.west3436.ss13.device.DeviceBlockEntity;
import io.github.west3436.ss13.device.DeviceData;
import io.github.west3436.ss13.device.DeviceTicker;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
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

    /**
     * A computer saved before it was a device on SS13 Framework's base (#110), with its name under
     * {@code name} and no format version, loads with its name, owner and box, as a chunk of an
     * existing world loads it, and is tracked and scheduled once it is in the level. It saves the
     * name as its label, in format 2.
     */
    @GameTest(template = "empty")
    public static void a_computer_saved_before_the_device_base_keeps_its_ship(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos rel = new BlockPos(1, 2, 1);
        ShuttleComputerBlockEntity placed = place(h, rel, Direction.EAST);
        BlockPos pos = placed.getBlockPos();
        UUID id = UUID.randomUUID();
        CompoundTag old = new CompoundTag();
        old.putString("id", "expedition:shuttle_computer");
        old.putInt("x", pos.getX());
        old.putInt("y", pos.getY());
        old.putInt("z", pos.getZ());
        old.putString("name", "Kestrel");
        old.putUUID("owner", id);
        old.putString("owner_name", "Pilot");
        old.put("box", new ShipBox(1, 2, 3, 4, 5, 6).save());

        BlockEntity loaded = BlockEntity.loadStatic(pos, level.getBlockState(pos), old, level.registryAccess());
        if (!(loaded instanceof ShuttleComputerBlockEntity computer)) {
            throw new GameTestAssertException("the old save loaded as " + loaded);
        }
        check(computer.name().equals("Kestrel"), "the name is '" + computer.name() + "'");
        check(computer.label().equals("Kestrel"), "the label is '" + computer.label() + "'");
        check(computer.owner().equals(Optional.of(id)), "the owner is " + computer.owner());
        check(computer.ownerName().equals("Pilot"), "the owner's name is " + computer.ownerName());
        check(computer.box().equals(new ShipBox(1, 2, 3, 4, 5, 6)), "the box is " + computer.box());

        CompoundTag saved = computer.saveWithoutMetadata(level.registryAccess());
        check(saved.getInt(DeviceData.VERSION_KEY) == 2, "saved in format " + saved.getInt(DeviceData.VERSION_KEY));
        check(saved.getString(DeviceBlockEntity.LABEL_KEY).equals("Kestrel"), "saved with " + saved);
        check(!saved.contains("name"), "the old name is still saved: " + saved);

        level.setBlockEntity(computer);
        h.succeedWhen(() -> {
            check(level.getBlockEntity(pos) == computer, "the loaded computer isn't in the level");
            check(ShipComputers.loaded(level).contains(computer), "the loaded computer isn't tracked");
            check(DeviceTicker.isScheduled(computer), "the loaded computer isn't scheduled");
            ShipStatus status = ShipComputers.status(level, computer);
            check(status.name().equals("Kestrel"), "the window names the ship '" + status.name() + "'");
            check(status.owner().equals("Pilot"), "the window's owner is " + status.owner());
            check(status.box().equals(new ShipBox(1, 2, 3, 4, 5, 6)), "the window's box is " + status.box());
        });
    }

    /**
     * The ship's name is the computer's label: a name tag's name, or the label a framework tool sets,
     * names the ship, and the label is cleaned as every label is.
     */
    @GameTest(template = "empty")
    public static void the_ship_name_is_the_computer_label(GameTestHelper h) {
        ShuttleComputerBlockEntity computer = place(h, new BlockPos(1, 2, 1), Direction.NORTH);
        Labelled labelled = Labels.at(h.getLevel(), computer.getBlockPos());
        check(labelled == computer, "the computer isn't the label's device: " + labelled);
        check(labelled.setLabel("Heron"), "the label didn't change");
        check(computer.name().equals("Heron"), "the name is '" + computer.name() + "'");
        ShipComputers.rename(computer, "  Grey \u00a7lGoose  ");
        check(computer.label().equals("Grey Goose"), "the label is '" + computer.label() + "'");
        ShipComputers.rename(computer, "");
        check(computer.name().isEmpty() && computer.label().isEmpty(), "a blank name left " + computer.name());
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
