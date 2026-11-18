package io.github.west3436.expedition.drive;

import io.github.west3436.expedition.mover.MoveRefusal;

/**
 * The stable codes a refused {@link Plot} gives. They share the mover's namespace ({@link
 * MoveRefusal}): no code here is one of the mover's, programs compare these strings, and they never
 * change. Each has a message in the mod's {@code en_us.json}, under {@link
 * MoveRefusal#translationKey}, so {@link MoveRefusal#message} words them too.
 *
 * <p>{@link ThrustModel#plot} asks in this order, and the first that refuses names the plot's
 * refusal: {@link #NO_DRIVE}, {@link #THRUST_TOO_LOW}, {@link #THRUST_TOO_LOW_TO_LAND}, {@link
 * #OUT_OF_RANGE}, {@link #NO_FUEL}.
 */
public final class DriveRefusal {
    /** The ship has no drive. */
    public static final String NO_DRIVE = "no_drive";

    /** Thrust-to-weight where the ship stands isn't above 1, so it can't lift off. */
    public static final String THRUST_TOO_LOW = "thrust_too_low";

    /** Thrust-to-weight at the destination isn't above 1, so the ship couldn't land there. */
    public static final String THRUST_TOO_LOW_TO_LAND = "thrust_too_low_to_land";

    /** The trip would take longer than the longest trip allowed. */
    public static final String OUT_OF_RANGE = "out_of_range";

    /** The tanks hold less fuel than the trip burns. */
    public static final String NO_FUEL = "no_fuel";

    private DriveRefusal() {}
}
