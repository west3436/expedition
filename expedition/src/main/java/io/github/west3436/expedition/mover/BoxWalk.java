package io.github.west3436.expedition.mover;

import java.util.NoSuchElementException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Walks the positions of a box one at a time, in a fixed order, so that a move can stop between any
 * two of them and carry on in the next tick. The walk is layers of y, rows of z within a layer, and
 * x within a row.
 *
 * <p>{@link #topDown} walks the layers from the top: the order for a move whose two boxes don't
 * overlap. The source keeps its lower layers, which hold up what stands on them, until the last
 * tick of the move, and a column's heightmap drops one block at a time.
 *
 * <p>{@link #ahead} walks each axis from the end the offset points to, so that every position's
 * destination is walked, and so emptied, before the position itself: the order for a move whose
 * two boxes overlap in one level, which would otherwise write over blocks it hasn't moved yet.
 *
 * <p>{@link #shell} walks only the positions on the box's faces: the ones with a neighbour outside
 * the box.
 */
final class BoxWalk {
    private final BoundingBox box;
    private final int stepX;
    private final int stepY;
    private final int stepZ;
    private final boolean shellOnly;
    private int x;
    private int y;
    private int z;
    private boolean done;

    private BoxWalk(BoundingBox box, int stepX, int stepY, int stepZ, boolean shellOnly) {
        this.box = box;
        this.stepX = stepX;
        this.stepY = stepY;
        this.stepZ = stepZ;
        this.shellOnly = shellOnly;
        this.x = stepX > 0 ? box.minX() : box.maxX();
        this.y = stepY > 0 ? box.minY() : box.maxY();
        this.z = stepZ > 0 ? box.minZ() : box.maxZ();
    }

    /**
     * Every position, top layer first.
     *
     * @param box the box
     * @return the walk
     */
    static BoxWalk topDown(BoundingBox box) {
        return new BoxWalk(box, 1, -1, 1, false);
    }

    /**
     * Every position, each position's destination before the position itself.
     *
     * @param box the box
     * @param offset where every position goes, relative to itself
     * @return the walk: top layer first unless the offset points down
     */
    static BoxWalk ahead(BoundingBox box, Vec3i offset) {
        return new BoxWalk(
                box, offset.getX() > 0 ? -1 : 1, offset.getY() < 0 ? 1 : -1, offset.getZ() > 0 ? -1 : 1, false);
    }

    /**
     * The positions on the box's faces, top layer first.
     *
     * @param box the box
     * @return the walk
     */
    static BoxWalk shell(BoundingBox box) {
        return new BoxWalk(box, 1, -1, 1, true);
    }

    /**
     * How many positions the whole walk has, walked or not.
     *
     * @param box the box
     * @param shellOnly whether only the faces count
     * @return the count
     */
    static long size(BoundingBox box, boolean shellOnly) {
        long all = (long) box.getXSpan() * box.getYSpan() * box.getZSpan();
        if (!shellOnly) {
            return all;
        }
        long inner = (long) Math.max(0, box.getXSpan() - 2)
                * Math.max(0, box.getYSpan() - 2)
                * Math.max(0, box.getZSpan() - 2);
        return all - inner;
    }

    /**
     * Whether a position is left.
     *
     * @return true until the walk has given every position
     */
    boolean hasNext() {
        return !done;
    }

    /**
     * The next position.
     *
     * @param out where to put it
     * @return {@code out}
     * @throws NoSuchElementException if the walk is over
     */
    BlockPos.MutableBlockPos next(BlockPos.MutableBlockPos out) {
        if (done) {
            throw new NoSuchElementException();
        }
        out.set(x, y, z);
        advance();
        return out;
    }

    private void advance() {
        if (shellOnly && !onOuterRow() && x == firstX() && box.getXSpan() > 1) {
            // Inside a layer, away from its edge rows, only the row's two ends are on a face.
            x = lastX();
            return;
        }
        if (x != lastX()) {
            x += stepX;
            return;
        }
        x = firstX();
        if (z != lastZ()) {
            z += stepZ;
            return;
        }
        z = firstZ();
        if (y != lastY()) {
            y += stepY;
            return;
        }
        done = true;
    }

    /** Whether the walk's current row has every position on a face: a top or bottom layer, or an edge row. */
    private boolean onOuterRow() {
        return y == box.minY() || y == box.maxY() || z == box.minZ() || z == box.maxZ();
    }

    private int firstX() {
        return stepX > 0 ? box.minX() : box.maxX();
    }

    private int lastX() {
        return stepX > 0 ? box.maxX() : box.minX();
    }

    private int firstZ() {
        return stepZ > 0 ? box.minZ() : box.maxZ();
    }

    private int lastZ() {
        return stepZ > 0 ? box.maxZ() : box.minZ();
    }

    private int lastY() {
        return stepY > 0 ? box.maxY() : box.minY();
    }
}
