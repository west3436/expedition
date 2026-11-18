package io.github.west3436.expedition.mover;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The seam for ZAS³'s relocation hand-over (west3436/zas-cubed#506, ZAS³ 1.2.0's {@code
 * Relocations}): something else that has to know a box of blocks is being moved, so it can carry
 * what it keeps about them along, as ZAS³ carries a cabin's air.
 *
 * <p>The mover calls it in this order, on the server thread:
 *
 * <ol>
 *   <li>{@link #begin} before the first block of the box changes;
 *   <li>then waits for {@link Ticket#begun()} before it moves anything;
 *   <li>{@link Ticket#complete} once every block is in place at the destination and the entities
 *       have arrived, in the same tick;
 *   <li>or {@link Ticket#cancel} instead, if the move is refused after it began, before any block
 *       has changed.
 * </ol>
 *
 * <p>Each ticket is completed or cancelled exactly once. {@link ZasRelocationHandover} is ZAS³'s,
 * the one {@link MoveSettings#DEFAULT} uses. {@link #NONE} is the no-op hand-over, for a move that
 * should leave the air to ordinary block changes, as a test may: its tickets begin at once and do
 * nothing.
 */
@FunctionalInterface
public interface RelocationHandover {
    /** The hand-over that does nothing, and lets every move go ahead at once. */
    RelocationHandover NONE = (from, box) -> Ticket.NONE;

    /**
     * Starts a hand-over: nothing in {@code box} has changed yet.
     *
     * @param from the level the blocks leave
     * @param box the blocks that will move, both corners inside
     * @return the ticket the mover completes or cancels
     */
    Ticket begin(ServerLevel from, BoundingBox box);

    /** One hand-over in progress. */
    interface Ticket {
        /** A ticket that has begun and does nothing when completed or cancelled. */
        Ticket NONE = new Ticket() {
            private final CompletableFuture<Optional<String>> begun =
                    CompletableFuture.completedFuture(Optional.empty());

            @Override
            public CompletableFuture<Optional<String>> begun() {
                return begun;
            }

            @Override
            public void complete(ServerLevel to, Vec3i offset) {}

            @Override
            public void cancel() {}
        };

        /**
         * Whether the other side is ready. The mover moves nothing until this completes: with no
         * value to go ahead, or with a refusal code to refuse the move whole, in which case it still
         * calls {@link #cancel}. ZAS³'s begin is applied by its next atmos tick, so this may take a
         * few game ticks.
         *
         * @return the future, the same one every time; it completes on the server thread
         */
        CompletableFuture<Optional<String>> begun();

        /**
         * Ends the hand-over: the blocks and entities are all at the destination.
         *
         * @param to the level they arrived in
         * @param offset how far every position went
         */
        void complete(ServerLevel to, Vec3i offset);

        /** Ends the hand-over without a move: nothing in the box changed. */
        void cancel();
    }
}
