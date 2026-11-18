package io.github.west3436.expedition.mixin;

import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The border a delegate listener copies the Overworld's changes onto, so the one for a planet's
 * level can be found and removed ({@link io.github.west3436.expedition.planet.PlanetBorders}).
 */
@Mixin(BorderChangeListener.DelegateBorderChangeListener.class)
public interface DelegateBorderChangeListenerAccessor {
    /** {@return the border the listener copies changes onto} */
    @Accessor("worldBorder")
    WorldBorder expedition$worldBorder();
}
