package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.util.OculusPipelineBridge;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import me.cortex.voxy.client.iris.OculusUniformProvider;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL45C.*;

public class IrisVoxyRenderPipeline extends AbstractRenderPipeline {
    private static final int UNIFORM_BINDING_POINT = 7;
    private static final int BASE_SAMPLER_BINDING_INDEX = 6;

    private final IrisVoxyRenderPipelineData data;
    private final FullscreenBlit depthBlit;
    public final DepthFramebuffer fbTranslucent;

    private final FullscreenBlit shaderDepthHackFixTransformBlit;
    private final GlBuffer shaderUniforms;

    public IrisVoxyRenderPipeline(RenderProperties properties, IrisVoxyRenderPipelineData data,
                                  AsyncNodeManager nodeManager, NodeCleaner nodeCleaner,
                                  HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        super(properties, nodeManager, nodeCleaner, traversal, frexSupplier, data.shouldDeferTranslucency());
        this.data = data;
        this.fbTranslucent = new DepthFramebuffer(this.fb.getFormat());

        if (this.data.thePipeline != null) {
            throw new IllegalStateException("Pipeline data already bound");
        }
        this.data.thePipeline = this;

        // Bind the drawbuffers for opaque
        int[] oDT = this.data.opaqueDrawTargets;
        int[] binding = new int[oDT.length];
        for (int i = 0; i < oDT.length; i++) {
            binding[i] = GL30.GL_COLOR_ATTACHMENT0 + i;
            glNamedFramebufferTexture(this.fb.framebuffer.id, GL30.GL_COLOR_ATTACHMENT0 + i, oDT[i], 0);
        }
        glNamedFramebufferDrawBuffers(this.fb.framebuffer.id, binding);

        // Bind the drawbuffers for translucent
        int[] tDT = this.data.translucentDrawTargets;
        binding = new int[tDT.length];
        for (int i = 0; i < tDT.length; i++) {
            binding[i] = GL30.GL_COLOR_ATTACHMENT0 + i;
            glNamedFramebufferTexture(this.fbTranslucent.framebuffer.id, GL30.GL_COLOR_ATTACHMENT0 + i, tDT[i], 0);
        }
        glNamedFramebufferDrawBuffers(this.fbTranslucent.framebuffer.id, binding);

        this.fb.framebuffer.verify();
        this.fbTranslucent.framebuffer.verify();

        if (data.getUniforms() != null) {
            this.shaderUniforms = new GlBuffer(data.getUniforms().size());
        } else {
            this.shaderUniforms = null;
        }

        if (!this.data.skipShaderDepthHackFix) {
            this.shaderDepthHackFixTransformBlit = new FullscreenBlit(properties, "voxy:post/fullscreen2.vert", "voxy:post/noop.frag");
        } else {
            this.shaderDepthHackFixTransformBlit = null;
        }

        this.depthBlit = new FullscreenBlit(properties, "voxy:post/blit_texture_depth_cutout.frag");
    }

    @Override
    public void setupExtraModelBakeryData(ModelBakerySubsystem modelService) {
        if (modelService != null && modelService.factory != null) {
            try {
                if (net.coderbot.iris.Iris.getCurrentPack().isPresent()) {
                    modelService.factory.setCustomBlockStateMapping(net.coderbot.iris.block_rendering.BlockRenderingSettings.INSTANCE.getBlockStateIds());
                } else {
                    modelService.factory.setCustomBlockStateMapping(null);
                }
            } catch (Throwable t) {
                me.cortex.voxy.common.Logger.error("Failed to set custom blockstate mapping from Oculus", t);
                modelService.factory.setCustomBlockStateMapping(null);
            }
        }
    }

    public IrisVoxyRenderPipelineData getData() {
        return this.data;
    }

    @Override
    public void free() {
        if (this.data.thePipeline != this) {
            throw new IllegalStateException();
        }
        this.data.thePipeline = null;

        this.depthBlit.delete();
        this.fbTranslucent.free();

        if (this.shaderDepthHackFixTransformBlit != null) {
            this.shaderDepthHackFixTransformBlit.delete();
        }

        if (this.shaderUniforms != null) {
            this.shaderUniforms.free();
        }

        super.free0();
    }

    @Override
    public void preSetup(Viewport<?> viewport) {
        super.preSetup(viewport);
        if (this.shaderUniforms != null) {
            OculusUniformProvider.prevModelView.set(OculusUniformProvider.currentModelView);
            OculusUniformProvider.prevProjection.set(OculusUniformProvider.currentProjection);
            OculusUniformProvider.currentModelView.set(viewport.modelView);
            OculusUniformProvider.currentProjection.set(viewport.projection);
            OculusUniformProvider.currentModelViewInv.set(viewport.modelView).invert();
            OculusUniformProvider.currentProjectionInv.set(viewport.projection).invert();

            long ptr = UploadStream.INSTANCE.uploadTo(this.shaderUniforms);
            this.data.getUniforms().updater().accept(ptr);
            UploadStream.INSTANCE.commit();
        }
    }

    @Override
    protected int setup(Viewport<?> viewport, int sourceDepthTexture, int srcWidth, int srcHeight) {
        this.fb.resize(viewport.width, viewport.height);
        this.fbTranslucent.resize(viewport.width, viewport.height);

        if (!this.data.useViewportDims) {
            srcWidth = viewport.width;
            srcHeight = viewport.height;
        }
        this.initDepthStencil(sourceDepthTexture, this.fb.framebuffer.id, srcWidth, srcHeight, viewport.width, viewport.height);
        return this.fb.getDepthTex().id;
    }

    @Override
    protected void postOpaquePreTranslucent(Viewport<?> viewport, int sourceDepthTexture) {
        if (this.shaderDepthHackFixTransformBlit != null) {
            this.fb.bind();
            glEnable(GL_DEPTH_TEST);
            glColorMask(false, false, false, false);
            glDepthFunc(GL_ALWAYS);
            glStencilFunc(GL_EQUAL, 0, 0xFF);
            this.shaderDepthHackFixTransformBlit.blit();
            glStencilFunc(GL_EQUAL, 1, 0xFF);
            glDepthFunc(this.properties.closerEqualDepthCompare());
            glColorMask(true, true, true, true);
        }

        glTextureBarrier();

        int msk = GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT;
        glBlitNamedFramebuffer(this.fb.framebuffer.id, this.fbTranslucent.framebuffer.id,
                0, 0, viewport.width, viewport.height,
                0, 0, viewport.width, viewport.height, msk, GL_NEAREST);
    }

    @Override
    protected void finish(Viewport<?> viewport, int sourceDepthTexture, int outputFramebuffer, int srcWidth, int srcHeight) {
        if (this.data.renderToVanillaDepth) {
            boolean mustFiddledViewport = srcWidth != viewport.width || srcHeight != viewport.height;
            if (this.data.useViewportDims || !mustFiddledViewport) {
                glColorMask(false, false, false, false);
                if (mustFiddledViewport) {
                    glViewport(0, 0, viewport.width, viewport.height);
                }
                AbstractRenderPipeline.transformBlitDepth(this.depthBlit,
                        this.fbTranslucent.getDepthTex().id, outputFramebuffer,
                        viewport, new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView));
                if (mustFiddledViewport) {
                    glViewport(0, 0, srcWidth, srcHeight);
                }
                glColorMask(true, true, true, true);
            }
        } else {
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_DEPTH_TEST);
        }
    }

    @Override
    public void bindUniforms() {
        this.bindUniforms(UNIFORM_BINDING_POINT);
    }

    @Override
    public void bindUniforms(int bindingPoint) {
        if (this.shaderUniforms != null) {
            GL30.glBindBufferBase(GL_UNIFORM_BUFFER, bindingPoint, this.shaderUniforms.id);
        }
    }

    private void doBindings() {
        this.bindUniforms();
        if (this.data.getSamplerSet() != null) {
            this.data.getSamplerSet().bindingFunction().accept(BASE_SAMPLER_BINDING_INDEX);
        }
    }

    @Override
    public void setupAndBindOpaque(Viewport<?> viewport) {
        this.fb.bind();
        this.doBindings();
    }

    @Override
    public void setupAndBindTranslucent(Viewport<?> viewport) {
        this.fbTranslucent.bind();
        this.doBindings();
        if (this.data.getBlender() != null) {
            this.data.getBlender().run();
        }
    }

    @Override
    public void addDebug(List<String> debug) {
        debug.add("Using: " + this.getClass().getSimpleName());
        super.addDebug(debug);
    }

    private StringBuilder buildGenericShaderHeader(AbstractSectionRenderer<?, ?> renderer, String input) {
        StringBuilder builder = new StringBuilder(input).append("\n\n\n");

        builder.append("#ifndef MC_VERSION\n#define MC_VERSION 11605\n#endif\n");
        builder.append("#ifndef MC_GL_VERSION\n#define MC_GL_VERSION 320\n#endif\n");
        builder.append("#ifndef MC_GLSL_VERSION\n#define MC_GLSL_VERSION 150\n#endif\n");
        builder.append("#ifndef MC_HAND_DEPTH\n#define MC_HAND_DEPTH 0.125\n#endif\n");
        builder.append("#ifndef MC_RENDER_STAGE_TERRAIN_SOLID\n#define MC_RENDER_STAGE_TERRAIN_SOLID 1\n#endif\n");
        builder.append("#ifndef MC_RENDER_STAGE_TERRAIN_TRANSLUCENT\n#define MC_RENDER_STAGE_TERRAIN_TRANSLUCENT 1\n#endif\n");
        builder.append("#ifndef MC_RENDER_QUALITY\n#define MC_RENDER_QUALITY 1.0\n#endif\n");
        builder.append("#ifndef MC_SHADOW_QUALITY\n#define MC_SHADOW_QUALITY 1.0\n#endif\n");

        String stdMacros = OculusPipelineBridge.getStandardEnvironmentDefines();
        if (stdMacros != null && !stdMacros.isEmpty()) {
            builder.append(stdMacros).append("\n");
        }

        builder.append("#ifndef VOXY\n#define VOXY 2\n#endif\n");
        builder.append("#ifndef VOXY_PATCH\n#define VOXY_PATCH\n#endif\n");
        builder.append("#ifndef IRIS_FEATURE_VOXY\n#define IRIS_FEATURE_VOXY 1\n#endif\n");
        builder.append("float miplevel = 0.0;\n\n");

        if (this.data.getUniforms() != null) {
            builder.append("layout(binding = ").append(UNIFORM_BINDING_POINT).append(", std140) uniform ShaderUniformBindings ")
                    .append(this.data.getUniforms().layout())
                    .append(";\n\n");
        }

        if (this.data.getSamplerSet() != null) {
            builder.append("#define BASE_SAMPLER_BINDING_INDEX ").append(BASE_SAMPLER_BINDING_INDEX).append("\n");
            builder.append(this.data.getSamplerSet().layout()).append("\n\n");
        }

        return builder.append("\n\n");
    }

    @Override
    public String patchOpaqueShader(AbstractSectionRenderer<?, ?> renderer, String input) {
        StringBuilder builder = this.buildGenericShaderHeader(renderer, input);
        builder.append(this.data.opaqueFragPatch());
        return builder.toString();
    }

    @Override
    public String patchTranslucentShader(AbstractSectionRenderer<?, ?> renderer, String input) {
        if (this.data.translucentFragPatch() == null) return null;
        StringBuilder builder = this.buildGenericShaderHeader(renderer, input);
        builder.append(this.data.translucentFragPatch());
        return builder.toString();
    }

    @Override
    public boolean hasTAA() {
        return this.data.TAA != null && this.data.TAA.contains("return");
    }

    @Override
    public String taaFunction(String functionName) {
        return this.taaFunction(UNIFORM_BINDING_POINT, functionName);
    }

    @Override
    public String taaFunction(int uboBindingPoint, String functionName) {
        StringBuilder builder = new StringBuilder();
        if (this.data.getUniforms() != null) {
            builder.append("layout(binding = ").append(uboBindingPoint).append(", std140) uniform ShaderUniformBindings ")
                    .append(this.data.getUniforms().layout())
                    .append(";\n\n");
        }

        builder.append("vec2 ").append(functionName).append("()\n");
        if (this.data.TAA != null && this.data.TAA.contains("return")) {
            builder.append(this.data.TAA);
        } else {
            builder.append("{\n    return vec2(0.0);\n}\n");
        }
        builder.append("\n");
        return builder.toString();
    }

    @Override
    public float[] getRenderScalingFactor() {
        return this.data.resolutionScale;
    }
}
