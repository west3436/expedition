package io.github.west3436.expedition.mover;

import java.util.List;
import java.util.Optional;

/**
 * A reason of the caller's own to refuse a move whole, before anything changes, kept in {@link
 * MoveSettings#checks()}. The mover's own refusals ({@link MoveRefusal}: an occupied destination, an
 * unloaded chunk, an immovable block, a box over the server's ceiling, an overlap with a move in
 * progress) are always asked first; these come after them.
 *
 * <p>The mover asks every check twice: when the move starts, before the hand-over begins, and again
 * once the hand-over is ready, just before the first block changes, since the world may have changed
 * while it waited. A refusal the first time changes nothing; a refusal the second time cancels the
 * hand-over and changes nothing. A {@link ShipMover#dryRun dry run} asks {@link #obstacles} instead.
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

    /**
     * Everything this check would refuse a move for, for a dry run. By default, the one refusal
     * {@link #check} gives, for the move as a whole; a check that can say where overrides it.
     *
     * @param request the move
     * @return the obstacles, empty if the move may go ahead
     */
    default List<Obstacle> obstacles(MoveRequest request) {
        return check(request)
                .<List<Obstacle>>map(code -> List.of(new Obstacle.OfMove(code)))
                .orElse(List.of());
    }
}
