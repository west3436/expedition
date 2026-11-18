package io.github.west3436.expedition.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.west3436.expedition.ExpeditionMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Draws the outline of a ship's box in the world while its Shuttle Computer's window is open, so the
 * pilot sees exactly what will move.
 */
@EventBusSubscriber(modid = ExpeditionMod.MOD_ID, value = Dist.CLIENT)
public final class ShipOutline {
    private ShipOutline() {}

    /**
     * Draws the outline after the translucent blocks.
     *
     * @param event the event
     */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
                || !(Minecraft.getInstance().screen instanceof ShuttleComputerScreen screen)) {
            return;
        }
        BoundingBox box = screen.outline();
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        MultiBufferSource.BufferSource buffers =
                Minecraft.getInstance().renderBuffers().bufferSource();
        AABB aabb = new AABB(box.minX(), box.minY(), box.minZ(), box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1)
                .inflate(0.002);
        LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()), aabb, 0.3F, 0.85F, 1.0F, 1.0F);
        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }
}
