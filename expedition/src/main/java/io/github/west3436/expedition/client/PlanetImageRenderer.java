package io.github.west3436.expedition.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.api.planet.Planets;
import io.github.west3436.expedition.api.space.Space;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.LogicalSide;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Draws the image of the planet a player in Space is over, flat below them (#24), as {@link
 * PlanetImages} places it.
 *
 * <p>It is drawn with the sky, before the world, and writes no depth, so the ship and everything
 * else in Space draws over it. Like the sky, it is fullbright and takes no fog. The image is shrunk
 * toward the camera ({@link PlanetImages#shrink}) so a square thousands of blocks wide stays inside
 * the view however far its corners are.
 */
@EventBusSubscriber(modid = ExpeditionMod.MOD_ID, value = Dist.CLIENT)
public final class PlanetImageRenderer {
    /** How much of the view's depth the image may take, so its far corners are never cut. */
    private static final float REACH = 0.5F;

    private PlanetImageRenderer() {}

    /**
     * Draws the image after the sky.
     *
     * @param event the event
     */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || !Space.is(level)) {
            return;
        }
        Vec3 eye = event.getCamera().getPosition();
        Optional<PlanetImages.Image> found = PlanetImages.below(
                Planets.all(LogicalSide.CLIENT), eye.x, eye.y, eye.z, PlanetImages.planeY(level.getMinBuildHeight()));
        if (found.isEmpty()) {
            return;
        }
        PlanetImages.Image image = found.get();
        double k = PlanetImages.shrink(image, eye.x, eye.y, eye.z, minecraft.gameRenderer.getDepthFar() * REACH);
        float y = (float) ((image.y() - eye.y) * k);
        float x0 = (float) ((image.minX() - eye.x) * k);
        float x1 = (float) ((image.maxX() - eye.x) * k);
        float z0 = (float) ((image.minZ() - eye.z) * k);
        float z1 = (float) ((image.maxZ() - eye.z) * k);

        // The sky's stage has no camera rotation applied yet: the sky applies it itself, and so does this.
        PoseStack pose = new PoseStack();
        pose.mulPose(event.getModelViewMatrix());
        Matrix4f matrix = pose.last().pose();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, image.texture());
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        // North (-Z) at the image's top, east (+X) at its right, as on a map.
        BufferBuilder buffer =
                Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buffer.addVertex(matrix, x0, y, z0).setUv(0.0F, 0.0F);
        buffer.addVertex(matrix, x0, y, z1).setUv(0.0F, 1.0F);
        buffer.addVertex(matrix, x1, y, z1).setUv(1.0F, 1.0F);
        buffer.addVertex(matrix, x1, y, z0).setUv(1.0F, 0.0F);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
    }
}
