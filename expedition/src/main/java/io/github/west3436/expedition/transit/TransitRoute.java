package io.github.west3436.expedition.transit;

import io.github.west3436.expedition.api.space.Space;

/**
 * A trip's route through Space: a straight line on Space's plane from where the ship left to where
 * it is going, flown at an even pace between two game times. It says where the ship is at any time,
 * which is where a player who steps outside the ship lands.
 *
 * @param fromX the X in Space the ship left from
 * @param fromZ the Z in Space the ship left from
 * @param toX the X in Space the ship is going to
 * @param toZ the Z in Space the ship is going to
 * @param departs the game time the trip begins
 * @param arrives the game time it ends, no earlier than {@code departs}
 */
public record TransitRoute(double fromX, double fromZ, double toX, double toZ, long departs, long arrives) {
    /**
     * Checks the route.
     *
     * @throws IllegalArgumentException if a coordinate isn't finite, or it arrives before it departs
     */
    public TransitRoute {
        for (double c : new double[] {fromX, fromZ, toX, toZ}) {
            if (!Double.isFinite(c)) {
                throw new IllegalArgumentException("a coordinate isn't finite: " + c);
            }
        }
        if (arrives < departs) {
            throw new IllegalArgumentException("arrives at " + arrives + ", before it departs at " + departs);
        }
    }

    /**
     * How far along the route the ship is at a time.
     *
     * @param gameTime the game time
     * @return 1 once it arrives, 0 before then if it hasn't departed, and in proportion between
     */
    public double progress(long gameTime) {
        if (gameTime >= arrives) {
            return 1;
        }
        if (gameTime <= departs) {
            return 0;
        }
        return (double) (gameTime - departs) / (arrives - departs);
    }

    /**
     * The X in Space the ship has reached at a time.
     *
     * @param gameTime the game time
     * @return the X
     */
    public double x(long gameTime) {
        return fromX + (toX - fromX) * progress(gameTime);
    }

    /**
     * The Z in Space the ship has reached at a time.
     *
     * @param gameTime the game time
     * @return the Z
     */
    public double z(long gameTime) {
        return fromZ + (toZ - fromZ) * progress(gameTime);
    }

    /**
     * How long the route is.
     *
     * @return the horizontal distance in blocks, as {@link Space#distance} measures it
     */
    public double length() {
        return Space.distance(fromX, fromZ, toX, toZ);
    }
}
