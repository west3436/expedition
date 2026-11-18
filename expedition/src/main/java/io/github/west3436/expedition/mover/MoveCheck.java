package io.github.west3436.expedition.mover;

import java.util.Optional;

/**
 * A reason to refuse a move whole, before anything changes: the seam for #6's refusals (an occupied
 * destination, an unloaded chunk, an immovable block, a box over the server's ceiling) and its dry
 * run.
 *
 * <p>The mover asks every check twice: when the move starts, before the hand-over begins, and again
 * once the hand-over is ready, just before the first block changes, since the world may have changed
 * while it waited. A refusal the first time changes nothing; a refusal the second time cancels the
 * hand-over and changes nothing.
 */
@FunctionalInterface
public interface MoveCheck {
    /**
     * Checks a move.
     *
     * @param request the move
     * @return a stable code saying why the move can't go ahead, or empty if it may
     */
    Optional<String> check(MoveRequest request);
}
