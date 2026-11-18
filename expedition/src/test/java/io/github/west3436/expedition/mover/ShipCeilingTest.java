package io.github.west3436.expedition.mover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

/** The ceiling on a ship's box, without the game. */
class ShipCeilingTest {
    private static BoundingBox box(int x, int y, int z) {
        return new BoundingBox(-5, 60, 7, -5 + x - 1, 60 + y - 1, 7 + z - 1);
    }

    @Test
    void measuresVolumeAndLongestSide() {
        assertEquals(64L * 64 * 32, ShipCeiling.volume(box(64, 32, 64)));
        assertEquals(64, ShipCeiling.longestSide(box(64, 32, 16)));
        assertEquals(64, ShipCeiling.longestSide(box(1, 1, 64)));
        // More than an int holds.
        assertEquals(3_000_000L * 3_000_000L, ShipCeiling.volume(box(3_000_000, 1, 3_000_000)));
    }

    @Test
    void capsBothVolumeAndLongestSide() {
        ShipCeiling ceiling = ShipCeiling.of(() -> 131_072, () -> 64, () -> 4_096);
        assertFalse(ceiling.exceeds(box(64, 32, 64)), "a box at both limits");
        assertTrue(ceiling.exceeds(box(64, 33, 64)), "over the volume");
        assertTrue(ceiling.exceeds(box(65, 1, 1)), "over the longest side");
        assertTrue(ceiling.exceeds(box(3_000_000, 1, 3_000_000)), "a volume past an int");
    }

    @Test
    void capsAShortHopByItsOwnVolume() {
        ShipCeiling ceiling = ShipCeiling.of(() -> 131_072, () -> 64, () -> 4_096);
        assertFalse(ceiling.hopExceeds(box(16, 16, 16)));
        assertTrue(ceiling.hopExceeds(box(16, 17, 16)));
        assertFalse(ShipCeiling.NONE.hopExceeds(box(64, 64, 64)));
        assertFalse(ShipCeiling.NONE.exceeds(box(1_000, 1_000, 1_000)));
    }

    @Test
    void readsItsLimitsEveryTime() {
        AtomicInteger volume = new AtomicInteger(27);
        ShipCeiling ceiling = ShipCeiling.of(volume::get, () -> 64, () -> 4_096);
        assertFalse(ceiling.exceeds(box(3, 3, 3)));
        volume.set(26);
        assertTrue(ceiling.exceeds(box(3, 3, 3)));
    }
}
