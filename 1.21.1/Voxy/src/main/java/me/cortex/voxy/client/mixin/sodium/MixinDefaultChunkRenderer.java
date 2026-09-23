package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.core.util.VoxyFogParameters;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.device.RenderDevice;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives Voxy's LoD pass off Sodium's terrain render.
 * <p>
 * Sodium 0.8.13's signature is much narrower than the 0.9.x one this was written against:
 * {@code render(ChunkRenderMatrices, CommandList, ChunkRenderListIterable, TerrainRenderPass,
 * CameraTransform, boolean)} -- no {@code FogParameters}, {@code GpuSampler}, {@code GpuBufferSlice}
 * or {@code GlTexelBuffer}, and {@code begin}/{@code end} take only the render pass. 1.21.1 also has
 * no per-pass GPU targets, so the framebuffer comes from Minecraft's main render target and the fog
 * state is snapshotted from {@code RenderSystem} (see {@link VoxyFogParameters}).
 */
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class MixinDefaultChunkRenderer extends ShaderChunkRenderer {
    public MixinDefaultChunkRenderer(RenderDevice device, ChunkVertexType vertexType) {
        super(device, vertexType);
    }

    @Inject(method = "render", at = @At(value = "HEAD"), cancellable = true)
    private void voxy$cancelThingie(ChunkRenderMatrices matrices, CommandList commandList,
                                    ChunkRenderListIterable renderLists, TerrainRenderPass renderPass,
                                    CameraTransform camera, boolean indexedRenderingEnabled, CallbackInfo ci) {
        if (VoxyClient.disableSodiumChunkRender()) {
            super.begin(renderPass);
            this.doRender(matrices, renderPass, camera);
            super.end(renderPass);
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V",
            shift = At.Shift.BEFORE))
    private void voxy$injectRender(ChunkRenderMatrices matrices, CommandList commandList,
                                   ChunkRenderListIterable renderLists, TerrainRenderPass renderPass,
                                   CameraTransform camera, boolean indexedRenderingEnabled, CallbackInfo ci) {
        this.doRender(matrices, renderPass, camera);
    }

    @Unique
    private void doRender(ChunkRenderMatrices matrices, TerrainRenderPass renderPass, CameraTransform camera) {
        if (renderPass != DefaultTerrainRenderPasses.CUTOUT) {
            return;
        }
        if (IrisUtil.irisShadowActive()) {
            return;
        }
        var renderer = IVoxyRenderSystemHolder.getNullable();
        if (renderer == null) {
            return;
        }

        var target = Minecraft.getInstance().getMainRenderTarget();
        Viewport<?> viewport = null;
        if (IrisUtil.irisShaderPackEnabled()) {
            viewport = renderer.getViewport();
            if (viewport == null || viewport.width <= 0 || viewport.height <= 0) {
                if (IrisUtil.CAPTURED_VIEWPORT_PARAMETERS != null) {
                    viewport = IrisUtil.CAPTURED_VIEWPORT_PARAMETERS.apply(renderer);
                } else {
                    viewport = renderer.setupViewport(matrices.projection(), matrices.modelView(),
                            VoxyFogParameters.capture(), target.width, target.height, camera.x, camera.y, camera.z);
                }
            }
        } else {
            viewport = renderer.setupViewport(matrices.projection(), matrices.modelView(),
                    VoxyFogParameters.capture(), target.width, target.height, camera.x, camera.y, camera.z);
        }
        if (viewport != null && viewport.width > 0 && viewport.height > 0) {
            renderer.renderOpaque(viewport, target.getDepthTextureId(), target.getColorTextureId());
        }
    }
}
