package io.github.west3436.expedition.ship;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

/** A ship's box as six distances from its computer, without the game. */
class ShipBoxTest {
    private static final BlockPos COMPUTER = new BlockPos(10, 64, -20);

    @Test
    void theComputerAloneIsOneBlock() {
        BoundingBox box = ShipBox.NONE.toWorld(COMPUTER, Direction.NORTH);
        assertEquals(BoundingBox.fromCorners(COMPUTER, COMPUTER), box);
    }

    @Test
    void sidesFollowTheWayTheScreenFaces() {
        ShipBox box = new ShipBox(1, 2, 3, 4, 5, 6);
        // Facing north, a pilot at the screen looks south: front is north (-z), left is east (+x).
        assertEquals(
                new BoundingBox(10 - 4, 64 - 6, -20 - 1, 10 + 3, 64 + 5, -20 + 2),
                box.toWorld(COMPUTER, Direction.NORTH));
        // Facing east, the pilot looks west: front is +x, left is south (+z).
        assertEquals(
                new BoundingBox(10 - 2, 64 - 6, -20 - 4, 10 + 1, 64 + 5, -20 + 3),
                box.toWorld(COMPUTER, Direction.EAST));
        assertEquals(Direction.EAST, ShipBox.left(Direction.NORTH));
        assertEquals(Direction.WEST, ShipBox.left(Direction.SOUTH));
    }

    @Test
    void turnsBackIntoTheSameDistances() {
        ShipBox box = new ShipBox(1, 2, 3, 4, 5, 6);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BoundingBox world = box.toWorld(COMPUTER, facing);
            assertTrue(world.isInside(COMPUTER), "the computer is inside facing " + facing);
            assertEquals(box, ShipBox.fromWorld(world, COMPUTER, facing), "facing " + facing);
        }
    }

    @Test
    void growsABoxThatMissesTheComputer() {
        BoundingBox elsewhere = new BoundingBox(20, 70, -10, 22, 72, -8);
        ShipBox box = ShipBox.fromWorld(elsewhere, COMPUTER, Direction.SOUTH);
        BoundingBox world = box.toWorld(COMPUTER, Direction.SOUTH);
        assertTrue(world.isInside(COMPUTER));
        assertTrue(world.isInside(new BlockPos(22, 72, -8)));
        assertEquals(0, box.down());
    }

    @Test
    void savesAndClamps() {
        ShipBox box = new ShipBox(1, 2, 3, 4, 5, 6);
        assertEquals(box, ShipBox.load(box.save()));
        CompoundTag tag = box.save();
        tag.putInt("up", -3);
        tag.putInt("down", 1_000_000);
        assertEquals(new ShipBox(1, 2, 3, 4, 0, ShipBox.MAX_DISTANCE), ShipBox.load(tag));
        assertEquals(ShipBox.NONE, ShipBox.load(new CompoundTag()));
        assertThrows(IllegalArgumentException.class, () -> new ShipBox(-1, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ShipBox.NONE.toWorld(COMPUTER, Direction.UP));
    }
}
