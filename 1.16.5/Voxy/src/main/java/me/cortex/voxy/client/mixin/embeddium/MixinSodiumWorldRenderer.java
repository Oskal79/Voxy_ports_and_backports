package me.cortex.voxy.client.mixin.embeddium;

import com.mojang.blaze3d.matrix.MatrixStack;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.util.GlStateProbe;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.core.util.VoxyFogParameters;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.FloatBuffer;

/**
 * Drives voxy's LoD pass off Embeddium's terrain render.
 * <p>
 * The 1.21.1 build hooks Sodium's {@code DefaultChunkRenderer#render}, which takes the matrices and
 * camera transform as arguments. Embeddium 0.3.18 has no such entry point -- terrain goes through
 * {@code SodiumWorldRenderer#drawChunkLayer(RenderType, MatrixStack, double, double, double)}, and
 * 1.16.5 is still fixed-function, so the projection has to be read back from GL rather than taken
 * from a parameter.
 * <p>
 * LoD is drawn once, after the cutout layer, matching where the 1.21.1 build injects
 * (DefaultTerrainRenderPasses.CUTOUT).
 * <p>
 * On 1.21.1 this all runs inside Sodium's begin(renderPass)/end(renderPass) bracket, which restores
 * GL state around it. Embeddium 0.3.18 has no equivalent, so the state has to be snapshotted and put
 * back by hand -- see {@link GlStateProbe} for why restoring through GlStateManager alone silently
 * does nothing.
 */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public class MixinSodiumWorldRenderer {
    @org.spongepowered.asm.mixin.Unique private static int voxy$frames = 0;
    @org.spongepowered.asm.mixin.Unique private static int voxy$noRenderer = 0;
    @org.spongepowered.asm.mixin.Unique private static int voxy$leaksLogged = 0;

    @Inject(method = "drawChunkLayer", at = @At("TAIL"), remap = false)
    private void voxy$renderLod(RenderType layer, MatrixStack stack,
                                double camX, double camY, double camZ, CallbackInfo ci) {
        if (layer != RenderType.cutout()) {
            return;
        }
        if (IrisUtil.irisShadowActive()) {
            return;
        }
        boolean shadersEnabled = IrisUtil.irisShaderPackEnabled();
        if (shadersEnabled && !IrisUtil.isShaderPackVoxyCompatible()) {
            if (voxy$frames % 300 == 0) {
                me.cortex.voxy.common.Logger.info("[voxy-diag] shaders active but shaderpack not compatible with Voxy");
            }
            return;
        }
        if (me.cortex.voxy.client.config.VoxyConfig.CONFIG.getDebugRenderStage() == 3) {
            return;   // bisect control: behaves as if the LoD pass did not exist
        }
        var renderer = IVoxyRenderSystemHolder.getNullable();
        if (renderer == null) {
            if (voxy$noRenderer++ % 300 == 0) {
                me.cortex.voxy.common.Logger.info("[voxy-diag] cutout pass reached, but no render system (x"
                        + voxy$noRenderer + ")");
            }
            return;
        }
        if (voxy$frames == 0) {
            try {
                me.cortex.voxy.client.core.model.ModelFactory.logColourDiagnostics();
            } catch (Throwable t) {
                me.cortex.voxy.common.Logger.info("[voxy-diag] colour check failed: " + t);
            }
        }
        if (voxy$frames % 300 == 0) {
            me.cortex.voxy.common.Logger.info("[voxy-diag] traversal"
                    + " counts=" + java.util.Arrays.toString(me.cortex.voxy.client.RenderStatistics.hierarchicalTraversalCounts)
                    + " renderSections=" + java.util.Arrays.toString(me.cortex.voxy.client.RenderStatistics.hierarchicalRenderSections)
                    + " lodDistance=" + me.cortex.voxy.client.config.VoxyConfig.CONFIG.sectionRenderDistance
                    + " (queue cap " + me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser.MAX_QUEUE_SIZE + ")");
        }
        if (voxy$frames++ % 300 == 0) {
            me.cortex.voxy.common.Logger.info("[voxy-diag] LoD pass running, frame " + voxy$frames
                    + ", shaders=" + shadersEnabled
                    + ", debugStage=" + me.cortex.voxy.client.config.VoxyConfig.CONFIG.getDebugRenderStage()
                    + ", stopAfter=" + me.cortex.voxy.client.config.VoxyConfig.CONFIG.getDebugStopAfter());
        }

        var target = Minecraft.getInstance().getMainRenderTarget();
        int depthTexId = target.depthBufferId;
        int colorTexId = target.colorTextureId;
        int targetWidth = target.width;
        int targetHeight = target.height;

        if (shadersEnabled) {
            net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline dpipe = me.cortex.voxy.client.core.util.OculusPipelineBridge.getDeferredPipeline();
            net.coderbot.iris.rendertarget.RenderTargets rt = me.cortex.voxy.client.core.util.OculusPipelineBridge.getRenderTargets(dpipe);
            if (rt != null) {
                depthTexId = rt.getDepthTexture();
                colorTexId = me.cortex.voxy.client.core.util.OculusPipelineBridge.getTextureId(rt, 0);
                targetWidth = rt.getCurrentWidth();
                targetHeight = rt.getCurrentHeight();
            }
        }

        FloatBuffer buf = BufferUtils.createFloatBuffer(16);
        org.lwjgl.opengl.GL11.glGetFloatv(org.lwjgl.opengl.GL11.GL_PROJECTION_MATRIX, buf);
        Matrix4f projection = new Matrix4f().set(buf);

        FloatBuffer mv = BufferUtils.createFloatBuffer(16);
        stack.last().pose().store(mv);
        ((java.nio.Buffer) mv).rewind();
        Matrix4f modelView = new Matrix4f().set(mv);

        me.cortex.voxy.client.DeferredRelightIngest.pump();

        int[] before = GlStateProbe.capture();
        int[] beforeExt = GlStateProbe.captureExtended();
        GlStateProbe.drainErrors();
        try {
            var viewport = renderer.setupViewport(projection, modelView, VoxyFogParameters.capture(),
                    targetWidth, targetHeight, camX, camY, camZ);
            renderer.renderOpaque(viewport, depthTexId, colorTexId);
        } finally {
            if (!shadersEnabled) {
                GlStateProbe.restore(before);
            }
            // Report what the pass actually leaked, for the first few frames only. This is the
            // measurement that says which state is wrong rather than guessing at it.
            // Sample the leak on frames where voxy ACTUALLY drew terrain. The first frames of a
            // session have no sections yet, so the section renderer returns before binding
            // anything -- diffs taken there describe an idle pass, not the one that breaks the world.
            boolean drew = me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer.DREW_TERRAIN_THIS_FRAME;
            me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer.DREW_TERRAIN_THIS_FRAME = false;
            if (drew && voxy$leaksLogged < 3) {
                voxy$leaksLogged++;
                String errors = GlStateProbe.drainErrors();
                String leaked = GlStateProbe.diff(before, GlStateProbe.capture());
                String leakedExt = GlStateProbe.diffExtended(beforeExt, GlStateProbe.captureExtended());
                me.cortex.voxy.common.Logger.info("[voxy-diag] (terrain-drawing frame) GL state leaked by LoD pass: "
                        + (leaked == null ? "<none>" : leaked));
                me.cortex.voxy.common.Logger.info("[voxy-diag] extended state leaked (not restored): "
                        + (leakedExt == null ? "<none>" : leakedExt));
                me.cortex.voxy.common.Logger.info("[voxy-diag] GL errors during pass: "
                        + (errors == null ? "<none>" : errors));
            }
            GlStateProbe.restore(before);
        }
    }
}
