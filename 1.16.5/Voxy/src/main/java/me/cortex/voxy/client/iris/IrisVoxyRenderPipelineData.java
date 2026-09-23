package me.cortex.voxy.client.iris;

import me.cortex.voxy.client.core.IrisVoxyRenderPipeline;
import me.cortex.voxy.client.core.util.OculusPipelineBridge;
import net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline;
import net.coderbot.iris.rendertarget.RenderTargets;

import java.util.function.Consumer;
import java.util.function.LongConsumer;

public class IrisVoxyRenderPipelineData {
    public static final class StructLayout {
        private final int size;
        private final String layout;
        private final LongConsumer updater;

        public StructLayout(int size, String layout, LongConsumer updater) {
            this.size = size;
            this.layout = layout;
            this.updater = updater;
        }

        public int size() { return size; }
        public String layout() { return layout; }
        public LongConsumer updater() { return updater; }
    }

    public static final class SamplerSet {
        private final String layout;
        private final Consumer<Integer> bindingFunction;

        public SamplerSet(String layout, Consumer<Integer> bindingFunction) {
            this.layout = layout;
            this.bindingFunction = bindingFunction;
        }

        public String layout() { return layout; }
        public Consumer<Integer> bindingFunction() { return bindingFunction; }
    }

    public IrisVoxyRenderPipeline thePipeline;
    public final IrisShaderPatch patch;
    public final int[] opaqueDrawTargets;
    public final int[] translucentDrawTargets;
    private final String opaquePatch;
    private final String translucentPatch;
    private final StructLayout uniforms;
    private final Runnable blendingSetup;
    private final SamplerSet samplerSet;
    public final boolean renderToVanillaDepth;
    public final float[] resolutionScale;
    public final String TAA;
    public final boolean useViewportDims;
    public final boolean deferTranslucency;
    public boolean skipShaderDepthHackFix;

    private IrisVoxyRenderPipelineData(IrisShaderPatch patch, int[] opaqueDrawTargets, int[] translucentDrawTargets,
                                       StructLayout uniforms, Runnable blendingSetup, SamplerSet samplerSet) {
        this.patch = patch;
        this.opaqueDrawTargets = opaqueDrawTargets;
        this.translucentDrawTargets = translucentDrawTargets;
        this.opaquePatch = patch.getPatchOpaqueSource();
        this.translucentPatch = patch.getPatchTranslucentSource();
        this.uniforms = uniforms;
        this.blendingSetup = blendingSetup;
        this.samplerSet = samplerSet;
        this.renderToVanillaDepth = patch.emitToVanillaDepth();
        this.TAA = patch.getTAAShift();
        this.resolutionScale = patch.getRenderScale();
        this.useViewportDims = patch.useViewportDims();
        this.deferTranslucency = patch.deferredTranslucentRendering();
        this.skipShaderDepthHackFix = patch.skipShaderDepthHackFix();
    }

    public SamplerSet getSamplerSet() { return this.samplerSet; }
    public StructLayout getUniforms() { return this.uniforms; }
    public Runnable getBlender() { return this.blendingSetup; }
    public String opaqueFragPatch() { return this.opaquePatch; }
    public String translucentFragPatch() { return this.translucentPatch; }
    public boolean shouldDeferTranslucency() { return this.deferTranslucency; }

    public static IrisVoxyRenderPipelineData buildPipeline(DeferredWorldRenderingPipeline dpipe, IrisShaderPatch patch) {
        RenderTargets rt = OculusPipelineBridge.getRenderTargets(dpipe);

        int[] oTargets = patch.getOpaqueTargets();
        int[] opaqueDrawTargets = new int[oTargets.length];
        for (int i = 0; i < oTargets.length; i++) {
            opaqueDrawTargets[i] = OculusPipelineBridge.getTextureId(rt, oTargets[i]);
        }

        int[] tTargets = patch.getTranslucentTargets();
        int[] translucentDrawTargets = new int[tTargets.length];
        for (int i = 0; i < tTargets.length; i++) {
            translucentDrawTargets[i] = OculusPipelineBridge.getTextureId(rt, tTargets[i]);
        }

        StructLayout uniformLayout = OculusUniformProvider.buildUniformLayout(patch.getUniformList());
        SamplerSet samplers = OculusPipelineBridge.buildSamplerSet(patch.getSamplerSet(), rt, dpipe);

        return new IrisVoxyRenderPipelineData(patch, opaqueDrawTargets, translucentDrawTargets, uniformLayout, patch.createBlendSetup(), samplers);
    }
}
