package me.cortex.voxy.client.mixin.iris;

import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.core.util.VoxyFogParameters;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.opengl.GL11C.glViewport;

/**
 * Captures the viewport Iris is about to render with, so Voxy can match it.
 * <p>
 * 1.21.1's {@code renderLevel} hands the camera and both matrices in directly:
 * {@code (DeltaTracker, boolean, Camera, GameRenderer, LightTexture, Matrix4f frustum, Matrix4f projection)}.
 * The 26.x version instead took a bundle of render-state objects ({@code CameraRenderState},
 * {@code ChunkSectionsToRender}, {@code GpuBufferSlice}, {@code GraphicsResourceAllocator}), none of
 * which exist here -- so this ends up simpler. Fog likewise comes from {@link VoxyFogParameters}
 * rather than Sodium's {@code GameRendererStorage}, which the 0.8.x line does not have.
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {

    @Inject(method = "renderLevel", at = @At("HEAD"), order = 100)
    private void voxy$injectIrisCompat(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
                                       GameRenderer gameRenderer, LightTexture lightTexture,
                                       Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
        if (!IrisUtil.irisShaderPackEnabled()) {
            return;
        }
        if (IVoxyRenderSystemHolder.getNullableHolder() == null) {
            return;
        }

        var target = Minecraft.getInstance().getMainRenderTarget();
        //Fix the viewport dims, fuck iris
        glViewport(0, 0, target.width, target.height);

        var pos = camera.getPosition();
        IrisUtil.CAPTURED_VIEWPORT_PARAMETERS = new IrisUtil.CapturedViewportParameters(
                new ChunkRenderMatrices(projectionMatrix, frustumMatrix),
                VoxyFogParameters.capture(),
                target.width, target.height,
                pos.x, pos.y, pos.z);
    }
}
