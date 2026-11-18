package io.github.west3436.expedition.transit;

import java.util.Collection;
import net.minecraft.core.BlockPos;

/**
 * Where the transit dimension's slots are: one ship each, far from any other.
 *
 * <p><b>Spacing.</b> Slots sit on a grid whose pitch is the server's box ceiling (its longest side)
 * plus {@link #MARGIN}, rounded up to a whole region file ({@link #ALIGN}). A player sees at most 32
 * chunks, 512 blocks, so the margin keeps every other ship several view distances away, and each
 * slot keeps to region files of its own. With the default ceiling of 64 the pitch is 2,560 blocks.
 *
 * <p><b>Placement.</b> Slot {@code i} is column {@code i % columns}, row {@code i / columns} of the
 * grid, from the dimension's origin towards positive X and Z, with as many columns as fit in {@link
 * #REACH}. A ship's box has its lowest corner at the slot's origin, at {@link #FLOOR_Y}, or lower if
 * it is too tall to fit above it.
 *
 * <p><b>Allocation.</b> A new trip takes the lowest slot that is at least a pitch away, along X or
 * Z, from every slot in use, each measured by the larger of the two slots' pitches. So slots are
 * reused as soon as they are freed, and a server whose ceiling changed while ships were in transit
 * still keeps them apart.
 */
public final class TransitSlots {
    /** Blocks added to the box ceiling between slots: four times the farthest a player can see. */
    public static final int MARGIN = 2048;

    /** Slots start on whole region files. */
    public static final int ALIGN = 512;

    /** How far along X and Z the grid reaches, inside the game's largest world border. */
    public static final int REACH = 29_000_000;

    /** The height of a box's lowest layer in its slot, when it fits. */
    public static final int FLOOR_Y = 0;

    private TransitSlots() {}

    /**
     * The pitch of the grid for a box ceiling.
     *
     * @param longestSide the most blocks a box may be along any axis, at least 1
     * @return the blocks from one slot's origin to the next
     * @throws IllegalArgumentException if {@code longestSide} is below 1
     */
    public static int pitch(int longestSide) {
        if (longestSide < 1) {
            throw new IllegalArgumentException("longestSide is " + longestSide);
        }
        long raw = (long) longestSide + MARGIN;
        long aligned = (raw + ALIGN - 1) / ALIGN * ALIGN;
        return (int) Math.min(aligned, REACH);
    }

    /**
     * How many slots fit in a row at a pitch.
     *
     * @param pitch the pitch
     * @return the columns, at least 1
     */
    public static int columns(int pitch) {
        return Math.max(1, REACH / pitch);
    }

    /**
     * Where a slot's origin is.
     *
     * @param index the slot, from 0
     * @param pitch the pitch
     * @return the origin's X and Z, at {@link #FLOOR_Y}
     */
    public static BlockPos origin(int index, int pitch) {
        int columns = columns(pitch);
        return new BlockPos((index % columns) * pitch, FLOOR_Y, (index / columns) * pitch);
    }

    /**
     * Where a box's lowest corner goes in a slot: at the slot's origin, at {@link #FLOOR_Y} unless
     * the box is too tall to fit above it in the dimension's height.
     *
     * @param origin the slot's origin
     * @param height how many blocks tall the box is
     * @param minY the dimension's lowest Y
     * @param maxY the dimension's highest Y, inclusive
     * @return the corner, or null if the box can't fit at all
     */
    public static BlockPos corner(BlockPos origin, int height, int minY, int maxY) {
        if (height > maxY - minY + 1) {
            return null;
        }
        int y = Math.max(minY, Math.min(FLOOR_Y, maxY + 1 - height));
        return new BlockPos(origin.getX(), y, origin.getZ());
    }

    /**
     * A slot in use, as allocation sees it.
     *
     * @param origin the slot's origin
     * @param pitch the pitch it was allocated at
     */
    public record Taken(BlockPos origin, int pitch) {}

    /**
     * The lowest slot at a pitch that is clear of every slot in use.
     *
     * @param taken the slots in use
     * @param pitch the pitch for the new slot
     * @return the slot's index
     */
    public static int allocate(Collection<Taken> taken, int pitch) {
        for (int index = 0; ; index++) {
            BlockPos candidate = origin(index, pitch);
            boolean clear = true;
            for (Taken t : taken) {
                int apart = Math.max(pitch, t.pitch());
                if (Math.abs(candidate.getX() - t.origin().getX()) < apart
                        && Math.abs(candidate.getZ() - t.origin().getZ()) < apart) {
                    clear = false;
                    break;
                }
            }
            if (clear) {
                return index;
            }
        }
    }
}
