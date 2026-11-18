package io.github.west3436.expedition.flight;

import io.github.west3436.expedition.mover.MoveRefusal;

/**
 * The stable codes a refused flight gives (#29), besides the mover's ({@link MoveRefusal}), the
 * thrust model's ({@link io.github.west3436.expedition.drive.DriveRefusal}), the Shuttle Computer's
 * ({@link io.github.west3436.expedition.ship.ShipComputers#OVERLAP}) and transit's ({@link
 * io.github.west3436.expedition.transit.Transits}). They share the mover's namespace: programs
 * compare these strings, they never change, and each has a message in the mod's {@code en_us.json},
 * under {@link MoveRefusal#translationKey}.
 */
public final class FlightRefusal {
    /** The player who asked isn't the computer's owner, nor an operator. */
    public static final String NOT_OWNER = "not_owner";

    /**
     * There is no such planet to fly to, or it is hidden; or the ship isn't on a planet nor in Space,
     * so it has no way into Space.
     */
    public static final String NO_PLANET = "no_planet";

    /**
     * The ground under the ship's footprint at the landing spot varies by more than the server
     * allows ({@code flights.maxUnevenness}).
     */
    public static final String TOO_UNEVEN = "too_uneven";

    /** There is no ground under the landing spot, or no room above it below the top of the world. */
    public static final String NO_GROUND = "no_ground";

    /** Another ship's arrival is due where this one would arrive: the site is reserved for it. */
    public static final String RESERVED = "landing_reserved";

    /** The Shuttle Computer was gone, or unloaded, when the countdown ended. */
    public static final String NO_COMPUTER = "no_computer";

    /** The ship is already counting down to launch. */
    public static final String LAUNCHING = "launching";

    private FlightRefusal() {}
}
