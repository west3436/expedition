package io.github.west3436.expedition.mixin;

import io.github.west3436.expedition.api.planet.PlanetGravity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A planet's gravity on the entities that have no gravity attribute (#16): dropped items, falling
 * blocks, projectiles, minecarts, boats and the like fall with their usual gravity times the
 * level's {@link PlanetGravity}. Living entities are left alone: their gravity is the attribute,
 * which {@code PlanetGravityEvents} modifies. It runs on both sides, each with its own side's
 * planets, so a client's prediction of a falling item matches the server.
 */
@Mixin(Entity.class)
abstract class EntityGravityMixin {
    @Inject(method = "getGravity", at = @At("RETURN"), cancellable = true)
    private void expedition$planetGravity(CallbackInfoReturnable<Double> cir) {
        Entity self = (Entity) (Object) this;
        if (self instanceof LivingEntity) {
            return;
        }
        double gravity = PlanetGravity.of(self.level());
        if (gravity != PlanetGravity.NORMAL) {
            cir.setReturnValue(cir.getReturnValueD() * gravity);
        }
    }
}
