package io.github.west3436.expedition.api.planet;

import io.github.west3436.expedition.api.ExpeditionApi;
import java.util.Objects;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.fml.LogicalSide;

/**
 * A level's gravity: its planet's {@link Planet#gravity() gravity}, a multiple of the Overworld's,
 * or 1 where the level isn't a planet. It works without any other mod, and a mod with its own
 * gravity, such as a gravity generator, can build on it.
 *
 * <p>How Expedition applies it:
 *
 * <ul>
 *   <li>A living entity in a planet's dimension carries a transient modifier on the vanilla {@code
 *       minecraft:generic.gravity} attribute: id {@link #MODIFIER_ID}, operation {@code
 *       add_multiplied_total}, amount {@code gravity - 1}. It is added as the entity joins the
 *       level (spawned, loaded or arriving from another dimension), removed as it leaves, and
 *       updated for every entity on {@code /reload}. Other modifiers of the same operation compose
 *       with it by multiplying, so a mod scaling gravity further adds a modifier of its own rather
 *       than replacing this one.
 *   <li>Falls scale with it: the entity's safe fall distance is divided by the gravity and its
 *       fall damage multiplied by it, so a fall hurts as much as one of {@code gravity ×} its
 *       height on the Overworld, and landing from a jump never hurts.
 *   <li>Other entities, which have no gravity attribute (dropped items, falling blocks,
 *       projectiles, minecarts, boats), fall with their usual gravity times the level's.
 * </ul>
 *
 * <p>Thread: any. Like {@link Planets}, each answer comes from a snapshot of the loaded planets.
 */
public final class PlanetGravity {
    /** {@code expedition:planet_gravity}: the id of the planet's modifier on the gravity attribute. */
    public static final ResourceLocation MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath(ExpeditionApi.MOD_ID, "planet_gravity");

    /** The gravity of a level that isn't a planet: the Overworld's. */
    public static final double NORMAL = 1.0;

    private PlanetGravity() {}

    /**
     * The gravity in a level.
     *
     * @param level a server or client level
     * @return its planet's gravity, from the level's own side, or {@link #NORMAL} if it isn't a
     *     planet
     */
    public static double of(Level level) {
        return Planets.forLevel(Objects.requireNonNull(level, "level"))
                .map(Planet::gravity)
                .orElse(NORMAL);
    }

    /**
     * The gravity in a dimension.
     *
     * @param dimension the dimension
     * @param side the side whose planets to look in
     * @return its planet's gravity, or {@link #NORMAL} if it isn't a planet
     */
    public static double of(ResourceKey<Level> dimension, LogicalSide side) {
        return Planets.forDimension(dimension, side).map(Planet::gravity).orElse(NORMAL);
    }
}
