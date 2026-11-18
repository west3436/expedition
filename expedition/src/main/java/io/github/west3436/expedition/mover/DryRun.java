package io.github.west3436.expedition.mover;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What {@link ShipMover#dryRun} found: every obstacle that would refuse the move, not only the first.
 * Nothing was changed to find them.
 *
 * <p>The obstacles come in the order the checks are asked (see {@link MoveRefusal}), so the first
 * one's code is the refusal {@link ShipMover#start} would give now. Blocks and entities are listed
 * one by one, with where they are; a chunk that isn't loaded is listed once, and what is in it isn't
 * looked at. A box over the server's ceiling isn't searched block by block.
 *
 * @param obstacles everything that would refuse the move; empty if it would go ahead
 */
public record DryRun(List<Obstacle> obstacles) {
    /**
     * Copies the obstacles.
     *
     * @throws NullPointerException if the list or an obstacle is null
     */
    public DryRun {
        obstacles = List.copyOf(obstacles);
    }

    /**
     * Whether the move would go ahead.
     *
     * @return true if nothing is in its way
     */
    public boolean wouldMove() {
        return obstacles.isEmpty();
    }

    /**
     * The refusal {@link ShipMover#start} would give now.
     *
     * @return the first obstacle's code, or empty if the move would go ahead
     */
    public Optional<String> refusal() {
        return obstacles.isEmpty()
                ? Optional.empty()
                : Optional.of(obstacles.get(0).code());
    }

    /**
     * Every reason the move would be refused.
     *
     * @return the distinct codes, in the order they were found
     */
    public Set<String> codes() {
        Set<String> codes = new LinkedHashSet<>();
        for (Obstacle obstacle : obstacles) {
            codes.add(obstacle.code());
        }
        return codes;
    }

    /**
     * The obstacles for one reason.
     *
     * @param code a refusal code
     * @return those with that code, in order
     */
    public List<Obstacle> obstacles(String code) {
        return obstacles.stream().filter(o -> o.code().equals(code)).toList();
    }
}
