package io.github.west3436.expedition.flight;

import io.github.west3436.expedition.api.planet.SpaceSquare;
import io.github.west3436.expedition.api.space.Space;

/**
 * A trip's three legs (#29): lift-off from the ground into Space, the crossing through Space, and
 * landing on the ground. A trip that starts or ends in Space has no leg at that end, and a hop
 * between two places on one planet has all three, only shorter.
 *
 * <p>A leg from or to the ground is {@link #ORBIT_ALTITUDE} blocks, the climb to Space, plus the
 * stretch between where the ship stands, or lands, and the nearest edge of the planet's square in
 * Space, if it is beyond the square: a ship beyond the square reaches Space at the nearest edge. A
 * ship in orbit is over its planet's square, so an orbit beyond the square is at its edge, with no
 * stretch to fly.
 *
 * @param liftOff the lift-off leg, in blocks: 0 from Space
 * @param crossing the crossing, in blocks, on Space's plane
 * @param landing the landing leg, in blocks: 0 into Space
 * @param fromX where the crossing begins, in Space
 * @param fromZ where the crossing begins, in Space
 * @param toX where it ends, in Space: over the landing spot, or the orbit
 * @param toZ where it ends, in Space
 */
public record FlightLegs(
        double liftOff, double crossing, double landing, double fromX, double fromZ, double toX, double toZ) {
    /** The climb from a planet's ground to Space, and the descent back, in blocks. */
    public static final double ORBIT_ALTITUDE = 256;

    /**
     * One end of a trip, in Space.
     *
     * @param x the point in Space it leaves from or arrives at
     * @param z the point in Space
     * @param stretch how far beyond the planet's square the ground at this end is, in blocks
     * @param ground whether this end is on a planet's ground, rather than in Space
     */
    public record End(double x, double z, double stretch, boolean ground) {
        /**
         * The length of this end's leg.
         *
         * @return {@link #ORBIT_ALTITUDE} and the stretch on the ground; 0 in Space
         */
        public double leg() {
            return ground ? ORBIT_ALTITUDE + stretch : 0;
        }
    }

    /**
     * An end in Space itself, such as a station's: the point, with nothing to climb.
     *
     * @param x the point's X in Space
     * @param z the point's Z in Space
     * @return the end
     */
    public static End inSpace(double x, double z) {
        return new End(x, z, 0, false);
    }

    /**
     * An end over a planet: on its ground, or in its orbit. The point is where the ground's column
     * lies in Space ({@link Space#fromSurface}), which may be beyond the square; the end is at the
     * nearest point of the square.
     *
     * @param square the planet's square
     * @param x the column's X in Space
     * @param z the column's Z in Space
     * @param ground true on the ground, so the stretch beyond the square counts; false in orbit
     * @return the end
     */
    public static End over(SpaceSquare square, double x, double z, boolean ground) {
        double cx = Math.max(square.minX(), Math.min(square.maxX() - 1, x));
        double cz = Math.max(square.minZ(), Math.min(square.maxZ() - 1, z));
        return new End(cx, cz, ground ? Space.distance(x, z, cx, cz) : 0, ground);
    }

    /**
     * The legs between two ends.
     *
     * @param from where the trip starts
     * @param to where it ends
     * @return the legs
     */
    public static FlightLegs of(End from, End to) {
        return new FlightLegs(
                from.leg(),
                Space.distance(from.x(), from.z(), to.x(), to.z()),
                to.leg(),
                from.x(),
                from.z(),
                to.x(),
                to.z());
    }

    /**
     * The whole trip's length, as the thrust model flies it.
     *
     * @return the three legs together, in blocks
     */
    public double distance() {
        return liftOff + crossing + landing;
    }
}
