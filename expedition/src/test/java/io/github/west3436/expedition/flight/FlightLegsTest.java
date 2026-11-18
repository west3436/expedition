package io.github.west3436.expedition.flight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.west3436.expedition.api.planet.SpaceSquare;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A trip's legs and the ground under a landing, without the game. */
class FlightLegsTest {
    private static final double EPSILON = 1e-9;

    /** The Overworld's square and the Moon's, as the shipped planet files place them. */
    private static final SpaceSquare OVERWORLD = SpaceSquare.of(0, 0, 8192);

    private static final SpaceSquare MOON = SpaceSquare.of(0, -7120, 2048);

    @Test
    void aTripBetweenTwoGroundsHasThreeLegs() {
        FlightLegs legs = FlightLegs.of(FlightLegs.over(OVERWORLD, 0, 0, true), FlightLegs.over(MOON, 0, -7120, true));
        assertEquals(FlightLegs.ORBIT_ALTITUDE, legs.liftOff(), EPSILON);
        assertEquals(7120, legs.crossing(), EPSILON);
        assertEquals(FlightLegs.ORBIT_ALTITUDE, legs.landing(), EPSILON);
        assertEquals(7120 + 2 * FlightLegs.ORBIT_ALTITUDE, legs.distance(), EPSILON);
    }

    @Test
    void aHopOnOnePlanetHasTheSameLegsOnlyShorter() {
        FlightLegs legs =
                FlightLegs.of(FlightLegs.over(OVERWORLD, 100, 0, true), FlightLegs.over(OVERWORLD, 400, 0, true));
        assertEquals(300, legs.crossing(), EPSILON);
        assertEquals(300 + 2 * FlightLegs.ORBIT_ALTITUDE, legs.distance(), EPSILON);
    }

    @Test
    void aTripFromSpaceIntoOrbitHasOnlyTheCrossing() {
        FlightLegs legs = FlightLegs.of(FlightLegs.inSpace(0, -5000), FlightLegs.over(MOON, 0, -7120, false));
        assertEquals(0, legs.liftOff(), EPSILON);
        assertEquals(0, legs.landing(), EPSILON);
        assertEquals(2120, legs.distance(), EPSILON);
    }

    @Test
    void groundBeyondTheSquareAddsTheStretchAtEitherEnd() {
        // The Overworld's square runs to x 4095; a ship at 5000 reaches Space at the edge.
        FlightLegs.End beyond = FlightLegs.over(OVERWORLD, 5000, 0, true);
        assertEquals(4095, beyond.x(), EPSILON);
        assertEquals(905, beyond.stretch(), EPSILON);
        FlightLegs out = FlightLegs.of(beyond, FlightLegs.over(OVERWORLD, 4095, 0, true));
        FlightLegs in = FlightLegs.of(FlightLegs.over(OVERWORLD, 4095, 0, true), beyond);
        assertEquals(905, out.liftOff() - FlightLegs.ORBIT_ALTITUDE, EPSILON);
        assertEquals(905, in.landing() - FlightLegs.ORBIT_ALTITUDE, EPSILON);
        assertEquals(out.distance(), in.distance(), EPSILON);
    }

    @Test
    void anOrbitBeyondTheSquareIsAtItsEdgeWithNoStretch() {
        FlightLegs.End orbit = FlightLegs.over(MOON, 3000, -7120, false);
        assertEquals(1023, orbit.x(), EPSILON);
        assertEquals(0, orbit.stretch(), EPSILON);
        assertEquals(0, orbit.leg(), EPSILON);
    }

    @Test
    void flatGroundIsLandedOnOneAboveItsTop() {
        LandingSite site = LandingSite.judge(new int[] {63, 63, 63, 63}, 10, 20, -64, 320, 6, 4);
        assertTrue(site.ok());
        assertEquals(64, site.origin().getY());
        assertEquals(10, site.origin().getX());
        assertEquals(20, site.origin().getZ());
    }

    @Test
    void groundVaryingByMoreThanTheLimitIsTooUneven() {
        assertTrue(LandingSite.judge(new int[] {60, 64}, 0, 0, -64, 320, 6, 4).ok());
        LandingSite rough = LandingSite.judge(new int[] {60, 65}, 0, 0, -64, 320, 6, 4);
        assertFalse(rough.ok());
        assertEquals(Optional.of(FlightRefusal.TOO_UNEVEN), rough.refusal());
    }

    @Test
    void noGroundOrNoRoomIsRefused() {
        assertEquals(
                Optional.of(FlightRefusal.NO_GROUND),
                LandingSite.judge(new int[] {-65, -65}, 0, 0, -64, 320, 6, 4).refusal());
        assertEquals(
                Optional.of(FlightRefusal.NO_GROUND),
                LandingSite.judge(new int[] {315, 315}, 0, 0, -64, 320, 6, 4).refusal());
        assertTrue(LandingSite.judge(new int[] {313, 313}, 0, 0, -64, 320, 6, 4).ok());
    }
}
