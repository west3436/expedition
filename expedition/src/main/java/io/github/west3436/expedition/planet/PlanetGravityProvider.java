package io.github.west3436.expedition.planet;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.api.planet.PlanetGravity;
import io.github.west3436.ss13.api.gravity.Gravity;
import io.github.west3436.ss13.api.gravity.GravityEffect;
import net.minecraft.resources.ResourceLocation;

/**
 * A planet's gravity (#16), given to SS13 Framework's {@link Gravity} as the gravity of its whole
 * dimension: {@link PlanetGravity#of} sets it, so Space and transit set 0, and every other level is
 * left alone. The framework applies it to entities: modifiers on living entities' gravity, safe
 * fall distance and fall damage, and the gravity of items, projectiles and the like. A field
 * registered after it, such as deck gravity or a gravity generator, can set or scale it again.
 */
public final class PlanetGravityProvider {
    /** {@code expedition:planets}: the provider's id. */
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(ExpeditionMod.MOD_ID, "planets");

    private PlanetGravityProvider() {}

    /** Registers the provider, once, as the mod is constructed. */
    public static void register() {
        Gravity.register(ID, Gravity.DIMENSION, (level, pos) -> {
            double gravity = PlanetGravity.of(level);
            return gravity == PlanetGravity.NORMAL ? GravityEffect.NONE : GravityEffect.set(gravity);
        });
    }
}
