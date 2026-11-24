package io.github.west3436.expedition.api.planet;

import io.github.west3436.expedition.api.space.Space;
import io.github.west3436.expedition.api.space.Transit;
import java.util.Objects;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.fml.LogicalSide;

/**
 * A level's gravity: its planet's {@link Planet#gravity() gravity}, a multiple of the Overworld's,
 * {@link Space#GRAVITY} in {@linkplain Space Space} and the {@linkplain Transit transit dimension},
 * or 1 anywhere else. It works without any other mod.
 *
 * <p>This is the gravity of the whole level, as planets define it. Expedition gives it to SS13
 * Framework as the gravity of each dimension, and the framework applies it to entities, composed
 * with any field over part of a level, such as deck gravity or a gravity generator. The gravity at
 * one position, fields included, is the framework's {@code Gravity.at(level, pos)}. As the
 * framework applies it:
 *
 * <ul>
 *   <li>A living entity carries transient modifiers on the vanilla gravity attribute, its safe
 *       fall distance and its fall damage multiplier, so a fall hurts as much as one of {@code
 *       gravity ×} its height on the Overworld, and landing from a jump never hurts.
 *   <li>Other entities, which have no gravity attribute (dropped items, falling blocks,
 *       projectiles, minecarts, boats), fall with their usual gravity times the gravity where they
 *       are.
 * </ul>
 *
 * <p>Thread: any. Like {@link Planets}, each answer comes from a snapshot of the loaded planets.
 */
public final class PlanetGravity {
    /** The gravity of a level that isn't a planet: the Overworld's. */
    public static final double NORMAL = 1.0;

    private PlanetGravity() {}

    /**
     * The gravity in a level.
     *
     * @param level a server or client level
     * @return its planet's gravity, from the level's own side; {@link Space#GRAVITY} in Space and
     *     in transit; or {@link #NORMAL} anywhere else
     */
    public static double of(Level level) {
        if (Space.is(Objects.requireNonNull(level, "level")) || Transit.is(level)) {
            return Space.GRAVITY;
        }
        return Planets.forLevel(level).map(Planet::gravity).orElse(NORMAL);
    }

    /**
     * The gravity in a dimension.
     *
     * @param dimension the dimension
     * @param side the side whose planets to look in
     * @return its planet's gravity; {@link Space#GRAVITY} in Space and in transit; or {@link
     *     #NORMAL} anywhere else
     */
    public static double of(ResourceKey<Level> dimension, LogicalSide side) {
        if (Space.is(dimension) || Transit.is(dimension)) {
            return Space.GRAVITY;
        }
        return Planets.forDimension(dimension, side).map(Planet::gravity).orElse(NORMAL);
    }
}
