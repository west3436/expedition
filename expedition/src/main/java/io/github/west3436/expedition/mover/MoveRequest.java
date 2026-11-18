package io.github.west3436.expedition.mover;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * What to move: everything inside {@code box} in {@code from}, to {@code to}, with the box's lowest
 * corner arriving at {@code origin}. Ships don't turn: every block keeps its facing, and every
 * position moves by the same {@link #offset()}.
 *
 * @param from the level the box is in
 * @param box the box, both corners inside
 * @param to the level it goes to: the same one, or another
 * @param origin where the box's lowest corner ({@code minX, minY, minZ}) goes
 */
public record MoveRequest(ServerLevel from, BoundingBox box, ServerLevel to, BlockPos origin) {
    /**
     * Checks the arguments and makes the origin immutable.
     *
     * @throws NullPointerException if an argument is null
     */
    public MoveRequest {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(box, "box");
        Objects.requireNonNull(to, "to");
        origin = origin.immutable();
    }

    /**
     * A move by an offset.
     *
     * @param from the level the box is in
     * @param box the box
     * @param to the level it goes to
     * @param offset how far every position goes
     * @return the request
     */
    public static MoveRequest by(ServerLevel from, BoundingBox box, ServerLevel to, Vec3i offset) {
        return new MoveRequest(from, box, to, new BlockPos(box.minX(), box.minY(), box.minZ()).offset(offset));
    }

    /**
     * How far every position goes.
     *
     * @return the offset from a position in the box to where it arrives
     */
    public Vec3i offset() {
        return new Vec3i(origin.getX() - box.minX(), origin.getY() - box.minY(), origin.getZ() - box.minZ());
    }

    /**
     * Where the box arrives.
     *
     * @return the destination box
     */
    public BoundingBox destination() {
        return box.moved(origin.getX() - box.minX(), origin.getY() - box.minY(), origin.getZ() - box.minZ());
    }

    /**
     * Where a position arrives.
     *
     * @param pos a position in the box
     * @return the same position in the destination box
     */
    public BlockPos map(BlockPos pos) {
        return pos.offset(offset());
    }

    /**
     * Whether the box stays in its level.
     *
     * @return true if {@code from} and {@code to} are the same level
     */
    public boolean sameLevel() {
        return from == to;
    }

    /**
     * Whether the destination box overlaps the source box in the same level: a short hop, which the
     * mover does in one tick.
     *
     * @return true if some position is in both boxes
     */
    public boolean overlaps() {
        return sameLevel() && box.intersects(destination());
    }
}
