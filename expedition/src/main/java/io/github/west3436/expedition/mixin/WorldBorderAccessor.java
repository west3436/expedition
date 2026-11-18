package io.github.west3436.expedition.mixin;

import java.util.List;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A border's listeners, so a planet's border can be detached from the Overworld's by removing the
 * listener that copies the Overworld's changes onto it ({@link
 * io.github.west3436.expedition.planet.PlanetBorders}).
 */
@Mixin(WorldBorder.class)
public interface WorldBorderAccessor {
    /** {@return the border's listeners, as a mutable list} */
    @Accessor("listeners")
    List<BorderChangeListener> expedition$listeners();
}
