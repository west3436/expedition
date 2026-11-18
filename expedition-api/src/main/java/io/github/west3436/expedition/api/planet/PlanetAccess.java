package io.github.west3436.expedition.api.planet;

/**
 * Who may find and reach a planet.
 *
 * @param hiddenUntilScanned whether the planet stays off a player's map of Space until they have
 *     scanned it; off unless the file sets it
 */
public record PlanetAccess(boolean hiddenUntilScanned) {
    /** What a planet file gets when it leaves {@code access} out: nothing hidden. */
    public static final PlanetAccess DEFAULT = new PlanetAccess(false);
}
