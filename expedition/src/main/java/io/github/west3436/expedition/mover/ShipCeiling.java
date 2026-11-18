package io.github.west3436.expedition.mover;

import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The server's ceiling on a ship's box: a technical limit, not a game rule. A move of a box over it
 * is refused as {@link MoveRefusal#TOO_LARGE}, before the box is searched. The mover asks the one
 * {@link ShipMover#setCeiling set} on it; the server setting of #9 sets it. Until then it is {@link
 * #NONE}.
 */
@FunctionalInterface
public interface ShipCeiling {
    /** No ceiling: no box is too large. */
    ShipCeiling NONE = box -> false;

    /**
     * Whether a box is over the ceiling. It is asked on the server thread, before anything about the
     * box is read, so it must judge by the box alone.
     *
     * @param box the box a move would carry
     * @return true if the move is to be refused as {@link MoveRefusal#TOO_LARGE}
     */
    boolean exceeds(BoundingBox box);
}
