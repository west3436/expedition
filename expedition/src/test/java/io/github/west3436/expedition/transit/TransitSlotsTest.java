package io.github.west3436.expedition.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** The transit slots' spacing, placement and allocation, and a route's progress. */
class TransitSlotsTest {
    @Test
    void thePitchIsTheCeilingAndAMarginInWholeRegions() {
        assertEquals(2560, TransitSlots.pitch(64));
        assertEquals(2560, TransitSlots.pitch(1));
        assertEquals(2560, TransitSlots.pitch(512));
        assertEquals(3072, TransitSlots.pitch(513));
        assertEquals(0, TransitSlots.pitch(64) % 512);
        // Far enough that no player on one ship can see another: 32 chunks is the most anyone sees.
        assertTrue(TransitSlots.pitch(64) - 64 > 2 * 32 * 16);
        assertThrows(IllegalArgumentException.class, () -> TransitSlots.pitch(0));
    }

    @Test
    void slotsFillRowsFromTheOrigin() {
        int pitch = TransitSlots.pitch(64);
        int columns = TransitSlots.columns(pitch);
        assertEquals(29_000_000 / 2560, columns);
        assertEquals(new BlockPos(0, 0, 0), TransitSlots.origin(0, pitch));
        assertEquals(new BlockPos(2560, 0, 0), TransitSlots.origin(1, pitch));
        assertEquals(new BlockPos(0, 0, 2560), TransitSlots.origin(columns, pitch));
        assertEquals(new BlockPos(2560 * 3, 0, 2560 * 2), TransitSlots.origin(columns * 2 + 3, pitch));
    }

    @Test
    void aBoxSitsOnTheFloorUnlessItIsTooTall() {
        BlockPos origin = new BlockPos(2560, 0, 0);
        assertEquals(new BlockPos(2560, 0, 0), TransitSlots.corner(origin, 64, -64, 319));
        assertEquals(new BlockPos(2560, 0, 0), TransitSlots.corner(origin, 320, -64, 319));
        assertEquals(new BlockPos(2560, -10, 0), TransitSlots.corner(origin, 330, -64, 319));
        assertEquals(new BlockPos(2560, -64, 0), TransitSlots.corner(origin, 384, -64, 319));
        assertNull(TransitSlots.corner(origin, 385, -64, 319));
    }

    @Test
    void allocationTakesTheLowestFreeSlotAndReusesFreedOnes() {
        int pitch = TransitSlots.pitch(64);
        List<TransitSlots.Taken> taken = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int slot = TransitSlots.allocate(taken, pitch);
            assertEquals(i, slot);
            taken.add(new TransitSlots.Taken(TransitSlots.origin(slot, pitch), pitch));
        }
        taken.remove(1);
        assertEquals(1, TransitSlots.allocate(taken, pitch), "the freed slot");
    }

    @Test
    void slotsTakenAtAWiderPitchStayApart() {
        // A ship that left when the ceiling was larger holds slot 0 at a pitch of 5120: a new slot at
        // 2560 must stay 5120 from it, so slot 1 (2560) is skipped and slot 2 (5120) is next.
        int wide = TransitSlots.pitch(3000);
        int pitch = TransitSlots.pitch(64);
        List<TransitSlots.Taken> taken = List.of(new TransitSlots.Taken(BlockPos.ZERO, wide));
        int slot = TransitSlots.allocate(taken, pitch);
        BlockPos origin = TransitSlots.origin(slot, pitch);
        assertTrue(Math.abs(origin.getX()) >= wide || Math.abs(origin.getZ()) >= wide, "slot " + slot);
        assertEquals(2, slot);
    }

    @Test
    void aRouteIsFlownAtAnEvenPace() {
        TransitRoute route = new TransitRoute(0, 0, 3000, -4000, 100, 1100);
        assertEquals(5000, route.length());
        assertEquals(0, route.x(50));
        assertEquals(0, route.progress(100));
        assertEquals(1500, route.x(600));
        assertEquals(-2000, route.z(600));
        assertEquals(3000, route.x(5000));
        assertEquals(1, new TransitRoute(1, 2, 3, 4, 10, 10).progress(10));
        assertThrows(IllegalArgumentException.class, () -> new TransitRoute(0, 0, 1, 1, 10, 9));
        assertThrows(IllegalArgumentException.class, () -> new TransitRoute(Double.NaN, 0, 1, 1, 0, 9));
    }
}
