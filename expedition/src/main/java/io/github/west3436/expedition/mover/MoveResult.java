package io.github.west3436.expedition.mover;

import java.util.Objects;
import java.util.Optional;

/**
 * How a move ended, with what it carried and what it cost.
 *
 * @param refusal empty if the box moved; otherwise the stable code of the check or hand-over that
 *     refused it, in which case nothing changed and every count is 0
 * @param mass the ship's mass: the solid blocks moved, as {@link ShipSurvey#isSolid} counts them
 * @param blocks the blocks moved that aren't air, solid or not
 * @param blockEntities the block entities moved, with their saved state
 * @param entities the entities moved, each with its passengers counted once as one
 * @param entitiesLeft entities in the box that didn't go, because something refused their teleport
 * @param ticks how many server ticks the move did work in, from its start to its last neighbour
 *     update
 * @param nanos the wall time the move spent working, summed over those ticks
 */
public record MoveResult(
        Optional<String> refusal,
        int mass,
        int blocks,
        int blockEntities,
        int entities,
        int entitiesLeft,
        int ticks,
        long nanos) {
    /**
     * Checks the refusal.
     *
     * @throws NullPointerException if it is null
     */
    public MoveResult {
        Objects.requireNonNull(refusal, "refusal");
    }

    /**
     * A refused move: nothing changed.
     *
     * @param code why
     * @param ticks the ticks it took to refuse
     * @param nanos the time it took to refuse
     * @return the result
     */
    static MoveResult refused(String code, int ticks, long nanos) {
        return new MoveResult(Optional.of(code), 0, 0, 0, 0, 0, ticks, nanos);
    }

    /**
     * Whether the box moved.
     *
     * @return true unless the move was refused
     */
    public boolean moved() {
        return refusal.isEmpty();
    }
}
