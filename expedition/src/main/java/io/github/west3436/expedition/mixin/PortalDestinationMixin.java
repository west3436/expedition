package io.github.west3436.expedition.mixin;

import io.github.west3436.expedition.portal.PortalRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Nether and End portals turned off in the server settings (#57): a portal that {@link PortalRules}
 * refuses has no destination, so the entity stays where it is. It is asked first, before the portal
 * looks for or builds its exit, so a refused trip changes nothing at the other end.
 */
@Mixin({NetherPortalBlock.class, EndPortalBlock.class})
abstract class PortalDestinationMixin {
    @Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
    private void expedition$portalsOff(
            ServerLevel level, Entity entity, BlockPos pos, CallbackInfoReturnable<DimensionTransition> cir) {
        if (PortalRules.refuses((Block) (Object) this, level, entity)) {
            cir.setReturnValue(null);
        }
    }
}
