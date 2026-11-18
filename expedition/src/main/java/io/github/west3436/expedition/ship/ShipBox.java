package io.github.west3436.expedition.ship;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * A ship's box, as six distances from its Shuttle Computer, relative to the way the computer faces.
 * Everything inside it moves with the ship. The computer is always inside: all six at 0 is a box of
 * the computer alone.
 *
 * <p>The directions are a pilot's, standing at the computer's screen and looking at it: {@code
 * front} is toward the pilot, the way the screen faces; {@code back} is behind the computer; {@code
 * left} and {@code right} are the pilot's left and right; {@code up} and {@code down} are up and
 * down. Because the distances are relative, the box turns with the computer, and moves with it.
 *
 * @param front blocks toward the way the screen faces, at least 0
 * @param back blocks away from it, at least 0
 * @param left blocks to the left of a pilot facing the screen, at least 0
 * @param right blocks to the right, at least 0
 * @param up blocks up, at least 0
 * @param down blocks down, at least 0
 */
public record ShipBox(int front, int back, int left, int right, int up, int down) {
    /** The computer alone. */
    public static final ShipBox NONE = new ShipBox(0, 0, 0, 0, 0, 0);

    /** The furthest any side may reach: past it a box is far over any ceiling. */
    public static final int MAX_DISTANCE = 1_024;

    /** How the box goes over the network. */
    public static final StreamCodec<ByteBuf, ShipBox> STREAM_CODEC = StreamCodec.of(
            (buf, box) -> {
                for (int d : box.distances()) {
                    ByteBufCodecs.VAR_INT.encode(buf, d);
                }
            },
            buf -> new ShipBox(
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf)));

    /** The NBT keys, in the order of {@link #distances()}. */
    private static final String[] KEYS = {"front", "back", "left", "right", "up", "down"};

    /**
     * Checks the distances.
     *
     * @throws IllegalArgumentException if one is negative or over {@link #MAX_DISTANCE}
     */
    public ShipBox {
        for (int d : new int[] {front, back, left, right, up, down}) {
            if (d < 0 || d > MAX_DISTANCE) {
                throw new IllegalArgumentException("a box's distance must be 0 to " + MAX_DISTANCE + ", not " + d);
            }
        }
    }

    /**
     * A box from six distances, each clamped to 0 to {@link #MAX_DISTANCE}.
     *
     * @param distances front, back, left, right, up, down
     * @return the box
     * @throws IllegalArgumentException if there aren't six
     */
    public static ShipBox clamped(int... distances) {
        if (distances.length != 6) {
            throw new IllegalArgumentException("a box has six distances, not " + distances.length);
        }
        int[] d = new int[6];
        for (int i = 0; i < 6; i++) {
            d[i] = Math.clamp(distances[i], 0, MAX_DISTANCE);
        }
        return new ShipBox(d[0], d[1], d[2], d[3], d[4], d[5]);
    }

    /**
     * The six distances.
     *
     * @return front, back, left, right, up, down
     */
    public int[] distances() {
        return new int[] {front, back, left, right, up, down};
    }

    /**
     * The direction a pilot facing the screen calls left.
     *
     * @param facing the way the computer's screen faces
     * @return the left of a pilot looking at it
     */
    public static Direction left(Direction facing) {
        return facing.getClockWise();
    }

    /**
     * The box in the world.
     *
     * @param computer where the computer is
     * @param facing the way its screen faces, horizontal
     * @return the box, with the computer inside
     * @throws IllegalArgumentException if the facing isn't horizontal
     */
    public BoundingBox toWorld(BlockPos computer, Direction facing) {
        requireHorizontal(facing);
        BlockPos a =
                computer.relative(facing, front).relative(left(facing), left).above(up);
        BlockPos b =
                computer.relative(facing, -back).relative(left(facing), -right).below(down);
        return BoundingBox.fromCorners(a, b);
    }

    /**
     * The distances that make a box in the world, as far as it reaches past the computer on each
     * side. A box that doesn't hold the computer is grown to hold it.
     *
     * @param box the box in the world
     * @param computer where the computer is
     * @param facing the way its screen faces, horizontal
     * @return the distances, each clamped to {@link #MAX_DISTANCE}
     * @throws IllegalArgumentException if the facing isn't horizontal
     */
    public static ShipBox fromWorld(BoundingBox box, BlockPos computer, Direction facing) {
        requireHorizontal(facing);
        return clamped(
                reach(box, computer, facing),
                reach(box, computer, facing.getOpposite()),
                reach(box, computer, left(facing)),
                reach(box, computer, left(facing).getOpposite()),
                box.maxY() - computer.getY(),
                computer.getY() - box.minY());
    }

    /** How far a box reaches past a position in one horizontal direction, at least 0. */
    private static int reach(BoundingBox box, BlockPos pos, Direction direction) {
        int reach = switch (direction) {
            case EAST -> box.maxX() - pos.getX();
            case WEST -> pos.getX() - box.minX();
            case SOUTH -> box.maxZ() - pos.getZ();
            case NORTH -> pos.getZ() - box.minZ();
            default -> throw new IllegalArgumentException("not horizontal: " + direction);
        };
        return Math.max(0, reach);
    }

    private static void requireHorizontal(Direction facing) {
        if (facing.getAxis().isVertical()) {
            throw new IllegalArgumentException("a computer faces a horizontal direction, not " + facing);
        }
    }

    /**
     * Saves the box.
     *
     * @return the tag
     */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        int[] d = distances();
        for (int i = 0; i < 6; i++) {
            tag.putInt(KEYS[i], d[i]);
        }
        return tag;
    }

    /**
     * Loads a box, clamping each distance; a missing one is 0.
     *
     * @param tag the tag {@link #save} wrote
     * @return the box
     */
    public static ShipBox load(CompoundTag tag) {
        int[] d = new int[6];
        for (int i = 0; i < 6; i++) {
            d[i] = tag.getInt(KEYS[i]);
        }
        return clamped(d);
    }
}
