package me.cortex.voxy.client.iris;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.IrisVoxyRenderPipeline;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import net.coderbot.iris.gl.sampler.SamplerHolder;

public class VoxySamplers {
    private static int dummyDepthTexture = 0;

    public static int getDummyDepthTexture() {
        if (dummyDepthTexture == 0) {
            dummyDepthTexture = org.lwjgl.opengl.GL11.glGenTextures();
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, dummyDepthTexture);
            java.nio.FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(1);
            buf.put(1.0f);
            ((java.nio.Buffer) buf).flip();
            org.lwjgl.opengl.GL11.glTexImage2D(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24, 1, 1, 0, org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT, org.lwjgl.opengl.GL11.GL_FLOAT, buf);
            org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER, org.lwjgl.opengl.GL11.GL_NEAREST);
            org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER, org.lwjgl.opengl.GL11.GL_NEAREST);
            org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
            org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0);
        }
        return dummyDepthTexture;
    }

    public static int getOpaqueDepthTextureId() {
        VoxyRenderSystem vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs == null) return getDummyDepthTexture();
        AbstractRenderPipeline p = vrs.getPipeline();
        if (p instanceof IrisVoxyRenderPipeline) {
            IrisVoxyRenderPipeline ivrp = (IrisVoxyRenderPipeline) p;
            if (ivrp.fb != null && ivrp.fb.getDepthTex() != null) {
                return ivrp.fb.getDepthTex().id;
            }
        }
        return getDummyDepthTexture();
    }

    public static int getTranslucentDepthTextureId() {
        VoxyRenderSystem vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs == null) return getDummyDepthTexture();
        AbstractRenderPipeline p = vrs.getPipeline();
        if (p instanceof IrisVoxyRenderPipeline) {
            IrisVoxyRenderPipeline ivrp = (IrisVoxyRenderPipeline) p;
            if (ivrp.fbTranslucent != null && ivrp.fbTranslucent.getDepthTex() != null) {
                return ivrp.fbTranslucent.getDepthTex().id;
            }
        }
        return getDummyDepthTexture();
    }

    public static void addSamplers(SamplerHolder samplers) {
        samplers.addDynamicSampler(VoxySamplers::getOpaqueDepthTextureId, "vxDepthTexOpaque");
        samplers.addDynamicSampler(VoxySamplers::getTranslucentDepthTextureId, "vxDepthTexTrans");
    }
}
