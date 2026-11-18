package io.github.west3436.expedition.drive;

/**
 * Where a ship means to go, as the thrust model sees it.
 *
 * @param distance how far, in blocks, at least 0
 * @param fromGravity the gravity where the ship stands, a multiple of the Overworld's: 0 in space,
 *     where nothing needs lifting
 * @param toGravity the gravity where it lands: 0 if it stays in space
 * @param cruiseSpeed the fastest the pilot wants to go, in blocks per second, above 0: positive
 *     infinity for as fast as the drives allow. A lower speed takes longer and burns less fuel
 * @param maxTime the longest trip allowed, in seconds, above 0: positive infinity for no limit
 */
public record Trip(double distance, double fromGravity, double toGravity, double cruiseSpeed, double maxTime) {
    /**
     * Checks the numbers.
     *
     * @throws IllegalArgumentException if the distance or a gravity is negative or not finite, or the
     *     cruise speed or longest time isn't above 0
     */
    public Trip {
        finiteAtLeastZero("distance", distance);
        finiteAtLeastZero("fromGravity", fromGravity);
        finiteAtLeastZero("toGravity", toGravity);
        if (!(cruiseSpeed > 0)) {
            throw new IllegalArgumentException("cruiseSpeed must be above 0, not " + cruiseSpeed);
        }
        if (!(maxTime > 0)) {
            throw new IllegalArgumentException("maxTime must be above 0, not " + maxTime);
        }
    }

    /**
     * A trip as fast as the drives allow, with no limit on its time.
     *
     * @param distance how far, in blocks
     * @param fromGravity the gravity where the ship stands, 0 in space
     * @param toGravity the gravity where it lands, 0 in space
     * @return the trip
     */
    public static Trip of(double distance, double fromGravity, double toGravity) {
        return new Trip(distance, fromGravity, toGravity, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    /**
     * This trip with the pilot's cruise speed.
     *
     * @param cruiseSpeed the fastest to go, in blocks per second
     * @return the trip
     */
    public Trip atSpeed(double cruiseSpeed) {
        return new Trip(distance, fromGravity, toGravity, cruiseSpeed, maxTime);
    }

    /**
     * This trip with a limit on its time.
     *
     * @param maxTime the longest trip allowed, in seconds
     * @return the trip
     */
    public Trip within(double maxTime) {
        return new Trip(distance, fromGravity, toGravity, cruiseSpeed, maxTime);
    }

    private static void finiteAtLeastZero(String name, double value) {
        if (!(value >= 0) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be finite and at least 0, not " + value);
        }
    }
}
