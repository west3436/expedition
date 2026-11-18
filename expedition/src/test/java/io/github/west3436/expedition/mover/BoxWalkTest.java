package io.github.west3436.expedition.mover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The orders the mover walks a box in, without the game. */
class BoxWalkTest {
    private static final BoundingBox BOX = new BoundingBox(-3, 60, 5, 2, 64, 9);

    private static List<BlockPos> walk(BoxWalk walk) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        while (walk.hasNext()) {
            out.add(walk.next(at).immutable());
        }
        return out;
    }

    private static Set<BlockPos> every(BoundingBox box) {
        Set<BlockPos> all = new HashSet<>();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    all.add(new BlockPos(x, y, z));
                }
            }
        }
        return all;
    }

    private static boolean onFace(BoundingBox box, BlockPos p) {
        return p.getX() == box.minX()
                || p.getX() == box.maxX()
                || p.getY() == box.minY()
                || p.getY() == box.maxY()
                || p.getZ() == box.minZ()
                || p.getZ() == box.maxZ();
    }

    static List<BoundingBox> boxes() {
        return List.of(
                BOX,
                new BoundingBox(0, 0, 0, 0, 0, 0),
                new BoundingBox(0, 0, 0, 1, 1, 1),
                new BoundingBox(0, 0, 0, 2, 2, 2),
                new BoundingBox(0, 0, 0, 0, 5, 3),
                new BoundingBox(4, -2, 4, 9, -2, 4),
                new BoundingBox(-17, 30, 15, 16, 33, 17));
    }

    @ParameterizedTest
    @MethodSource("boxes")
    void topDownGivesEveryPositionOnce(BoundingBox box) {
        List<BlockPos> walked = walk(BoxWalk.topDown(box));
        assertEquals(every(box), new HashSet<>(walked));
        assertEquals(walked.size(), new HashSet<>(walked).size());
        assertEquals(BoxWalk.size(box, false), walked.size());
    }

    @ParameterizedTest
    @MethodSource("boxes")
    void shellGivesEveryPositionOnAFaceOnce(BoundingBox box) {
        List<BlockPos> walked = walk(BoxWalk.shell(box));
        Set<BlockPos> faces = new HashSet<>();
        for (BlockPos p : every(box)) {
            if (onFace(box, p)) {
                faces.add(p);
            }
        }
        assertEquals(faces, new HashSet<>(walked));
        assertEquals(walked.size(), new HashSet<>(walked).size());
        assertEquals(BoxWalk.size(box, true), walked.size());
    }

    @Test
    void shellOfALargeBoxIsItsSurfaceOnly() {
        BoundingBox box = new BoundingBox(0, 0, 0, 63, 63, 63);
        assertEquals(64L * 64 * 64 - 62L * 62 * 62, BoxWalk.size(box, true));
        assertEquals(BoxWalk.size(box, true), walk(BoxWalk.shell(box)).size());
    }

    @Test
    void topDownEndsWithTheBottomLayer() {
        List<BlockPos> walked = walk(BoxWalk.topDown(BOX));
        int layer = BOX.getXSpan() * BOX.getZSpan();
        for (int i = 0; i < walked.size(); i++) {
            assertEquals(BOX.maxY() - i / layer, walked.get(i).getY(), "layer of step " + i);
        }
    }

    static List<Vec3i> overlappingOffsets() {
        List<Vec3i> offsets = new ArrayList<>();
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    if (x != 0 || y != 0 || z != 0) {
                        offsets.add(new Vec3i(x, y, z));
                    }
                }
            }
        }
        return offsets;
    }

    @ParameterizedTest
    @MethodSource("overlappingOffsets")
    void aheadEmptiesEachDestinationBeforeWritingIt(Vec3i offset) {
        List<BlockPos> walked = walk(BoxWalk.ahead(BOX, offset));
        assertEquals(every(BOX), new HashSet<>(walked));
        Map<BlockPos, Integer> step = new HashMap<>();
        for (int i = 0; i < walked.size(); i++) {
            step.put(walked.get(i), i);
        }
        for (BlockPos p : walked) {
            Integer destination = step.get(p.offset(offset));
            if (destination != null) {
                assertTrue(
                        destination < step.get(p),
                        p + " is walked before its destination " + p.offset(offset) + " by " + offset);
            }
        }
    }

    @Test
    void aheadIsTopDownUnlessTheOffsetPointsDown() {
        assertEquals(
                BOX.maxY(),
                walk(BoxWalk.ahead(BOX, new Vec3i(1, 0, 0))).getFirst().getY());
        assertEquals(
                BOX.maxY(),
                walk(BoxWalk.ahead(BOX, new Vec3i(0, 2, 0))).getFirst().getY());
        assertEquals(
                BOX.minY(),
                walk(BoxWalk.ahead(BOX, new Vec3i(0, -1, 0))).getFirst().getY());
    }

    @Test
    void aWalkCanStopAnywhereAndCarryOn() {
        BoxWalk walk = BoxWalk.topDown(BOX);
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        List<BlockPos> sliced = new ArrayList<>();
        // Slices of three, as a tick's budget might cut them.
        while (walk.hasNext()) {
            for (int i = 0; i < 3 && walk.hasNext(); i++) {
                sliced.add(walk.next(at).immutable());
            }
        }
        assertEquals(walk(BoxWalk.topDown(BOX)), sliced);
        assertFalse(walk.hasNext());
        assertThrows(NoSuchElementException.class, () -> walk.next(at));
    }
}
