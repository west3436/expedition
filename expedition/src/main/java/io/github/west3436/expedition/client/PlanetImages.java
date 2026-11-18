package io.github.west3436.expedition.client;

import io.github.west3436.expedition.api.planet.Planet;
import io.github.west3436.expedition.api.planet.SpaceSquare;
import java.util.Collection;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Which planet's image a camera in Space sees below it, and where (#24). A planet shows in Space as
 * its image, a flat square lying under its square of Space, seen only while the camera is over that
 * square: over no planet's square, nothing is drawn below.
 *
 * <p>The images lie {@value #DEPTH} blocks below Space's lowest block, where the void begins, so
 * everything a ship can reach is above them. This class only decides; {@link PlanetImageRenderer}
 * draws. It touches no rendering, so it runs anywhere.
 */
public final class PlanetImages {
    /** How far below Space's lowest block the images lie. */
    public static final int DEPTH = 64;

    /**
     * A planet's image as it lies in Space: covering its square exactly, at one height.
     *
     * @param texture the image's texture
     * @param minX the square's lowest X
     * @param minZ the square's lowest Z
     * @param maxX the first X past the square
     * @param maxZ the first Z past the square
     * @param y the height it lies at
     */
    public record Image(ResourceLocation texture, int minX, int minZ, int maxX, int maxZ, double y) {}

    private PlanetImages() {}

    /**
     * The height the images lie at in a level.
     *
     * @param minBuildHeight the level's lowest block's Y
     * @return the height
     */
    public static double planeY(int minBuildHeight) {
        return minBuildHeight - DEPTH;
    }

    /**
     * The image a camera sees below it: that of the planet whose square holds the camera's column,
     * if the planet names an image and the camera is above the images.
     *
     * @param planets the planets
     * @param x the camera's X
     * @param y the camera's Y
     * @param z the camera's Z
     * @param planeY the height the images lie at
     * @return the image, or empty
     */
    public static Optional<Image> below(Collection<Planet> planets, double x, double y, double z, double planeY) {
        if (!(y > planeY)) {
            return Optional.empty();
        }
        int column = Mth.floor(x);
        int row = Mth.floor(z);
        for (Planet planet : planets) {
            SpaceSquare square = planet.space();
            if (square.contains(column, row)) {
                return square.image()
                        .map(texture ->
                                new Image(texture, square.minX(), square.minZ(), square.maxX(), square.maxZ(), planeY));
            }
        }
        return Optional.empty();
    }

    /**
     * How much to shrink an image toward the camera so its farthest corner is within reach of the
     * view. Shrinking about the eye changes nothing the eye sees, and keeps a square thousands of
     * blocks wide from being cut off by the far edge of the view.
     *
     * @param image the image
     * @param x the camera's X
     * @param y the camera's Y
     * @param z the camera's Z
     * @param reach the farthest a corner may be from the camera
     * @return the factor, at most 1
     */
    public static double shrink(Image image, double x, double y, double z, double reach) {
        double dx = Math.max(Math.abs(image.minX() - x), Math.abs(image.maxX() - x));
        double dz = Math.max(Math.abs(image.minZ() - z), Math.abs(image.maxZ() - z));
        double dy = y - image.y();
        double far = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return far <= reach ? 1 : reach / far;
    }
}
