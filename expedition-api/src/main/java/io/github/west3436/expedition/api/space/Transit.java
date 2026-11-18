package io.github.west3436.expedition.api.space;

import io.github.west3436.expedition.api.ExpeditionApi;
import io.github.west3436.expedition.api.planet.PlanetGravity;
import java.util.Objects;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * The transit dimension, {@code expedition:transit}: where a ship is for the length of a trip. Each
 * ship in transit has a slot of its own, far from any other, so no ship ever sees another.
 *
 * <ul>
 *   <li>It holds ships and nothing else: no terrain, no natural spawning, no portals, and nothing
 *       can be built outside a ship's box.
 *   <li>Its open air is a vacuum at {@link Space#TEMPERATURE} K, from its ZAS³ ambient file {@code
 *       data/expedition/zas/ambient/transit.json}, so a breach mid-trip is as deadly as one in
 *       Space. Like Space it has no gravity ({@link PlanetGravity} gives {@link Space#GRAVITY}).
 *   <li>Stepping outside a ship's box drops a player into Space at the point on the route the ship
 *       has reached.
 * </ul>
 *
 * <p>The transit dimension is not a planet, and no planet file may name it.
 *
 * <p>Thread: any.
 */
public final class Transit {
    /** {@code expedition:transit}: the transit dimension. */
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(
            Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(ExpeditionApi.MOD_ID, "transit"));

    private Transit() {}

    /**
     * Whether a level is the transit dimension.
     *
     * @param level a server or client level
     * @return true if it is
     */
    public static boolean is(Level level) {
        return is(Objects.requireNonNull(level, "level").dimension());
    }

    /**
     * Whether a dimension is the transit dimension.
     *
     * @param dimension the dimension
     * @return true if it is {@link #DIMENSION}
     */
    public static boolean is(ResourceKey<Level> dimension) {
        return DIMENSION.equals(Objects.requireNonNull(dimension, "dimension"));
    }
}
