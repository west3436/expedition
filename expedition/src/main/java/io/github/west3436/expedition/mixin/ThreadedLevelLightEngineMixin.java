package io.github.west3436.expedition.mixin;

import io.github.west3436.expedition.mover.LightChecks;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Lets the mover hand a whole batch of light checks to the light engine as one task ({@link
 * LightChecks}): while a batch is being handed over, the task {@code checkBlock} queues is the batch.
 * Every other call is untouched.
 *
 * <p>Optional: a mod that replaces the light engine may leave nothing to inject into, and then the
 * mover sees its batch wasn't taken and checks each position on its own, as vanilla does.
 */
@Mixin(ThreadedLevelLightEngine.class)
abstract class ThreadedLevelLightEngineMixin {
    @ModifyArg(
            method = "checkBlock",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/level/ThreadedLevelLightEngine;addTask(IILnet/minecraft/server/level/ThreadedLevelLightEngine$TaskType;Ljava/lang/Runnable;)V"),
            index = 3,
            require = 0)
    private Runnable expedition$handOverBatch(Runnable task) {
        return LightChecks.handOver(task);
    }
}
