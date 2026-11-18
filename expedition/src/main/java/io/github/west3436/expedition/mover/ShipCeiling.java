package io.github.west3436.expedition.mover;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The server's ceiling on a ship's box: a technical limit, not a game rule. A move of a box over it
 * is refused as {@link MoveRefusal#TOO_LARGE}, before the box is searched. A short hop, whose
 * destination overlaps its own box and which the mover carries in one tick, may have a lower
 * ceiling of its own, {@link #hopExceeds}, refused as {@link MoveRefusal#HOP_TOO_LARGE}.
 *
 * <p>The mover asks the one {@link ShipMover#setCeiling set} on it. The mod sets the one {@link
 * #of} makes from its server settings (see {@code ExpeditionConfig}), which reads them at every
 * move, so a reloaded config takes effect at the next move. Until then it is {@link #NONE}.
 */
@FunctionalInterface
public interface ShipCeiling {
    /** No ceiling: no box is too large, for a move or a short hop. */
    ShipCeiling NONE = box -> false;

    /**
     * Whether a box is over the ceiling. It is asked on the server thread, before anything about the
     * box is read, so it must judge by the box alone.
     *
     * @param box the box a move would carry
     * @return true if the move is to be refused as {@link MoveRefusal#TOO_LARGE}
     */
    boolean exceeds(BoundingBox box);

    /**
     * Whether a box within the ceiling is too large for a short hop: a move in the same level whose
     * destination overlaps the box ({@link MoveRequest#overlaps()}), which the mover carries whole in
     * one tick, whatever the tick's budget. Asked after {@link #exceeds}, and only for a short hop.
     * By default, no box is.
     *
     * @param box the box the hop would carry
     * @return true if the hop is to be refused as {@link MoveRefusal#HOP_TOO_LARGE}
     */
    default boolean hopExceeds(BoundingBox box) {
        return false;
    }

    /**
     * The limits a box is over, each with the box's measure and the limit, so a refusal can say by
     * how much. Asked only for a box that {@link #exceeds} the ceiling. By default none can be said,
     * and the move is refused with its code alone ({@link Obstacle.OfMove}).
     *
     * @param box the box a move would carry
     * @return the limits it is over, each as {@link MoveRefusal#TOO_LARGE}; may be empty
     */
    default List<Obstacle.OverLimit> breaches(BoundingBox box) {
        return List.of();
    }

    /**
     * The short-hop limits a box is over, as {@link #breaches} gives the others. Asked only for a box
     * that {@link #hopExceeds} the ceiling for a hop.
     *
     * @param box the box the hop would carry
     * @return the limits it is over, each as {@link MoveRefusal#HOP_TOO_LARGE}; may be empty
     */
    default List<Obstacle.OverLimit> hopBreaches(BoundingBox box) {
        return List.of();
    }

    /**
     * A ceiling on a box's volume and on its longest side, and on the volume of a short hop. Each
     * limit is read every time the ceiling is asked, so one that changes, such as a server setting
     * that is reloaded, applies from the next move. A box at a limit is within it.
     *
     * @param maxVolume the most blocks a box may hold
     * @param maxLongestSide the most blocks a box may be along any axis
     * @param maxHopVolume the most blocks a short hop's box may hold
     * @return the ceiling
     * @throws NullPointerException if a limit is null
     */
    static ShipCeiling of(IntSupplier maxVolume, IntSupplier maxLongestSide, IntSupplier maxHopVolume) {
        Objects.requireNonNull(maxVolume, "maxVolume");
        Objects.requireNonNull(maxLongestSide, "maxLongestSide");
        Objects.requireNonNull(maxHopVolume, "maxHopVolume");
        return new ShipCeiling() {
            @Override
            public boolean exceeds(BoundingBox box) {
                return volume(box) > maxVolume.getAsInt() || longestSide(box) > maxLongestSide.getAsInt();
            }

            @Override
            public boolean hopExceeds(BoundingBox box) {
                return volume(box) > maxHopVolume.getAsInt();
            }

            @Override
            public List<Obstacle.OverLimit> breaches(BoundingBox box) {
                List<Obstacle.OverLimit> over = new ArrayList<>(2);
                long volume = volume(box);
                int maxV = maxVolume.getAsInt();
                if (volume > maxV) {
                    over.add(new Obstacle.OverLimit(MoveRefusal.TOO_LARGE, Obstacle.OverLimit.VOLUME, volume, maxV));
                }
                int side = longestSide(box);
                int maxSide = maxLongestSide.getAsInt();
                if (side > maxSide) {
                    over.add(new Obstacle.OverLimit(
                            MoveRefusal.TOO_LARGE, Obstacle.OverLimit.LONGEST_SIDE, side, maxSide));
                }
                return over;
            }

            @Override
            public List<Obstacle.OverLimit> hopBreaches(BoundingBox box) {
                long volume = volume(box);
                int max = maxHopVolume.getAsInt();
                return volume > max
                        ? List.of(new Obstacle.OverLimit(
                                MoveRefusal.HOP_TOO_LARGE, Obstacle.OverLimit.HOP_VOLUME, volume, max))
                        : List.of();
            }
        };
    }

    /**
     * How many blocks a box holds. It may be more than an {@code int} can hold.
     *
     * @param box the box
     * @return its volume in blocks
     */
    static long volume(BoundingBox box) {
        return (long) box.getXSpan() * box.getYSpan() * box.getZSpan();
    }

    /**
     * How many blocks a box is along its longest axis.
     *
     * @param box the box
     * @return its longest side in blocks
     */
    static int longestSide(BoundingBox box) {
        return Math.max(box.getXSpan(), Math.max(box.getYSpan(), box.getZSpan()));
    }
}
