package me.cortex.voxy.client.core.util;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Snapshot / diff / restore of the GL state voxy's LoD pass is capable of touching.
 * <p>
 * Two things make this necessary on 1.16.5.
 * <p>
 * First, voxy changes most of its state with raw GL calls ({@code glDisable(GL_CULL_FACE)},
 * {@code glDepthMask(false)}, {@code glColorMask(...)}, {@code glDepthFunc(...)} in
 * MDICSectionRenderer, HiZBuffer and the pipelines). 1.16.5's {@link GlStateManager} *caches* the
 * state it believes GL to be in, and raw calls bypass that cache entirely.
 * <p>
 * Second -- and this is what makes a naive fix silently fail -- every GlStateManager setter is
 * guarded by that cache. {@code _enableCull()} when the cache already reads "enabled" issues no GL
 * call at all. So restoring through GlStateManager after a raw {@code glDisable} is a no-op, and the
 * driver stays wrong while the cache insists everything is fine.
 * <p>
 * Restores here therefore always drive the real state with a raw call *and* force the cache by
 * toggling it through the opposite value first, so both ends land on the captured value regardless
 * of where they started.
 */
public final class GlStateProbe {
    private static final String[] NAMES = {
            "program", "drawFbo", "readFbo", "vao", "arrayBuf", "elemBuf",
            "activeTexture", "tex0", "tex1", "tex2", "tex3", "sampler0",
            "depthTest", "depthFunc", "depthMask",
            "blend", "blendSrcRgb", "blendDstRgb", "blendSrcAlpha", "blendDstAlpha", "blendEq",
            "cullFace", "cullFaceMode", "frontFace",
            "stencilTest", "stencilFunc", "stencilRef", "stencilValueMask", "stencilWriteMask",
            "colorMaskR", "colorMaskG", "colorMaskB", "colorMaskA",
            "viewportX", "viewportY", "viewportW", "viewportH",
            "scissorTest", "polygonOffsetFill", "provokingVertex",
            "primitiveRestart", "polygonModeFront",
            "uboGeneric", "ssboGeneric", "drawIndirect", "parameterBuf", "dispatchIndirect",
            "copyRead", "copyWrite", "pixelPack", "pixelUnpack", "textureBufferBinding",
            "alphaTest", "alphaFunc", "alphaRefx1000", "fogEnable",
            "depthRangeNearx1e6", "depthRangeFarx1e6",
            "scissorX", "scissorY", "scissorW", "scissorH",
    };

    public static final int SIZE = NAMES.length;

    private GlStateProbe() {}

    private static int b(int pname) {
        return GL11.glGetBoolean(pname) ? 1 : 0;
    }

    /** Reads the current GL state into a fresh array. Leaves GL exactly as it found it. */
    public static int[] capture() {
        int[] s = new int[SIZE];
        int i = 0;
        s[i++] = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        s[i++] = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        s[i++] = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        s[i++] = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        s[i++] = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        s[i++] = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);

        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        s[i++] = active;
        // Reading a unit's binding means making it current, so put the active unit back afterwards.
        for (int unit = 0; unit < 4; unit++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            s[i++] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        s[i++] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL13.glActiveTexture(active);

        s[i++] = b(GL11.GL_DEPTH_TEST);
        s[i++] = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        s[i++] = b(GL11.GL_DEPTH_WRITEMASK);

        s[i++] = b(GL11.GL_BLEND);
        s[i++] = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        s[i++] = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        s[i++] = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        s[i++] = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        s[i++] = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);

        s[i++] = b(GL11.GL_CULL_FACE);
        s[i++] = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
        s[i++] = GL11.glGetInteger(GL11.GL_FRONT_FACE);

        s[i++] = b(GL11.GL_STENCIL_TEST);
        s[i++] = GL11.glGetInteger(GL11.GL_STENCIL_FUNC);
        s[i++] = GL11.glGetInteger(GL11.GL_STENCIL_REF);
        s[i++] = GL11.glGetInteger(GL11.GL_STENCIL_VALUE_MASK);
        s[i++] = GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK);

        ByteBuffer cm = BufferUtils.createByteBuffer(4);
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, cm);
        s[i++] = cm.get(0);
        s[i++] = cm.get(1);
        s[i++] = cm.get(2);
        s[i++] = cm.get(3);

        IntBuffer vp = BufferUtils.createIntBuffer(16);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
        s[i++] = vp.get(0);
        s[i++] = vp.get(1);
        s[i++] = vp.get(2);
        s[i++] = vp.get(3);

        s[i++] = b(GL11.GL_SCISSOR_TEST);
        s[i++] = b(GL11.GL_POLYGON_OFFSET_FILL);
        // MDICSectionRenderer sets GL_FIRST_VERTEX_CONVENTION and never puts it back.
        s[i++] = GL11.glGetInteger(org.lwjgl.opengl.GL32.GL_PROVOKING_VERTEX);

        s[i++] = b(org.lwjgl.opengl.GL31.GL_PRIMITIVE_RESTART);
        IntBuffer pm = BufferUtils.createIntBuffer(16);
        GL11.glGetIntegerv(GL11.GL_POLYGON_MODE, pm);
        s[i++] = pm.get(0);
        s[i++] = GL11.glGetInteger(org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER_BINDING);
        s[i++] = GL11.glGetInteger(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        s[i++] = GL11.glGetInteger(org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER_BINDING);
        s[i++] = GL11.glGetInteger(org.lwjgl.opengl.ARBIndirectParameters.GL_PARAMETER_BUFFER_BINDING_ARB);
        s[i++] = GL11.glGetInteger(org.lwjgl.opengl.GL43.GL_DISPATCH_INDIRECT_BUFFER_BINDING);
        s[i++] = GL11.glGetInteger(0x8F36 /* GL_COPY_READ_BUFFER_BINDING */);
        s[i++] = GL11.glGetInteger(0x8F37 /* GL_COPY_WRITE_BUFFER_BINDING */);
        s[i++] = GL11.glGetInteger(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        s[i++] = GL11.glGetInteger(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        s[i++] = GL11.glGetInteger(0x8C2A /* GL_TEXTURE_BUFFER_BINDING */);

        // 1.16.5 is a compatibility context: cutout layers use fixed-function alpha test and
        // GlStateManager caches it, so a raw change would survive every RenderType shard.
        s[i++] = b(GL11.GL_ALPHA_TEST);
        s[i++] = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
        s[i++] = (int) (GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF) * 1000f);
        s[i++] = b(GL11.GL_FOG);

        java.nio.FloatBuffer dr = BufferUtils.createFloatBuffer(16);
        GL11.glGetFloatv(GL11.GL_DEPTH_RANGE, dr);
        s[i++] = (int) (dr.get(0) * 1_000_000f);
        s[i++] = (int) (dr.get(1) * 1_000_000f);

        IntBuffer sc = BufferUtils.createIntBuffer(16);
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, sc);
        s[i++] = sc.get(0);
        s[i++] = sc.get(1);
        s[i++] = sc.get(2);
        s[i++] = sc.get(3);
        return s;
    }

    /** Human-readable list of every entry that changed, or null if nothing did. */
    public static String diff(int[] before, int[] after) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SIZE; i++) {
            if (before[i] != after[i]) {
                if (sb.length() != 0) sb.append(", ");
                sb.append(NAMES[i]).append(' ').append(before[i]).append("->").append(after[i]);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * Puts GL back exactly as {@link #capture()} found it, and drags GlStateManager's cache along
     * with it. Every cached value is toggled through a different value first so the setter's
     * "already correct" guard cannot swallow the call.
     */
    public static void restore(int[] s) {
        int i = 0;
        int program = s[i++];
        int drawFbo = s[i++];
        int readFbo = s[i++];
        int vao = s[i++];
        int arrayBuf = s[i++];
        int elemBuf = s[i++];
        int activeTexture = s[i++];
        int tex0 = s[i++], tex1 = s[i++], tex2 = s[i++], tex3 = s[i++];
        int sampler0 = s[i++];
        int depthTest = s[i++], depthFunc = s[i++], depthMask = s[i++];
        int blend = s[i++];
        int blendSrcRgb = s[i++], blendDstRgb = s[i++], blendSrcAlpha = s[i++], blendDstAlpha = s[i++];
        int blendEq = s[i++];
        int cullFace = s[i++], cullFaceMode = s[i++], frontFace = s[i++];
        int stencilTest = s[i++], stencilFunc = s[i++], stencilRef = s[i++], stencilValueMask = s[i++], stencilWriteMask = s[i++];
        int cmR = s[i++], cmG = s[i++], cmB = s[i++], cmA = s[i++];
        int vpX = s[i++], vpY = s[i++], vpW = s[i++], vpH = s[i++];
        int scissor = s[i++], polyOffset = s[i++];
        int provokingVertex = s[i++];
        int primRestart = s[i++], polyMode = s[i++];
        int uboGeneric = s[i++], ssboGeneric = s[i++], drawIndirect = s[i++], parameterBuf = s[i++], dispatchIndirect = s[i++];
        int copyRead = s[i++], copyWrite = s[i++], pixelPack = s[i++], pixelUnpack = s[i++], texBufBinding = s[i++];
        int alphaTest = s[i++], alphaFunc = s[i++], alphaRefx1000 = s[i++], fogEnable = s[i++];
        int drNear = s[i++], drFar = s[i++];
        int scX = s[i++], scY = s[i++], scW = s[i++], scH = s[i++];

        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuf);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, elemBuf);
        GL11.glViewport(vpX, vpY, vpW, vpH);

        // Program: raw call fixes the driver, GlStateManager call fixes the cache.
        GL20.glUseProgram(program);
        GlStateManager._glUseProgram(program);

        int[] tex = {tex0, tex1, tex2, tex3};
        for (int unit = 0; unit < tex.length; unit++) {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex[unit]);
            GlStateManager._bindTexture(tex[unit]);
        }
        GL33.glBindSampler(0, sampler0);
        GlStateManager._activeTexture(activeTexture == GL13.GL_TEXTURE0 ? GL13.GL_TEXTURE1 : GL13.GL_TEXTURE0);
        GlStateManager._activeTexture(activeTexture);

        if (depthTest != 0) {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GlStateManager._disableDepthTest();
            GlStateManager._enableDepthTest();
        } else {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GlStateManager._enableDepthTest();
            GlStateManager._disableDepthTest();
        }
        GL11.glDepthFunc(depthFunc);
        GlStateManager._depthFunc(depthFunc == GL11.GL_LESS ? GL11.GL_ALWAYS : GL11.GL_LESS);
        GlStateManager._depthFunc(depthFunc);
        GL11.glDepthMask(depthMask != 0);
        GlStateManager._depthMask(depthMask == 0);
        GlStateManager._depthMask(depthMask != 0);

        GL20.glBlendEquationSeparate(blendEq, blendEq);
        GL14.glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
        GlStateManager._blendFuncSeparate(blendSrcRgb == 0 ? 1 : 0, blendDstRgb, blendSrcAlpha, blendDstAlpha);
        GlStateManager._blendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
        if (blend != 0) {
            GL11.glEnable(GL11.GL_BLEND);
            GlStateManager._disableBlend();
            GlStateManager._enableBlend();
        } else {
            GL11.glDisable(GL11.GL_BLEND);
            GlStateManager._enableBlend();
            GlStateManager._disableBlend();
        }

        GL11.glCullFace(cullFaceMode);
        GL11.glFrontFace(frontFace);
        if (cullFace != 0) {
            GL11.glEnable(GL11.GL_CULL_FACE);
            GlStateManager._disableCull();
            GlStateManager._enableCull();
        } else {
            GL11.glDisable(GL11.GL_CULL_FACE);
            GlStateManager._enableCull();
            GlStateManager._disableCull();
        }

        if (stencilTest != 0) {
            GL11.glEnable(GL11.GL_STENCIL_TEST);
        } else {
            GL11.glDisable(GL11.GL_STENCIL_TEST);
        }
        //glStencilFunc drives both faces, so this also repairs the back-face function.
        GL11.glStencilFunc(stencilFunc, stencilRef, stencilValueMask);
        GL11.glStencilMask(stencilWriteMask);

        GL11.glColorMask(cmR != 0, cmG != 0, cmB != 0, cmA != 0);
        GlStateManager._colorMask(cmR == 0, cmG != 0, cmB != 0, cmA != 0);
        GlStateManager._colorMask(cmR != 0, cmG != 0, cmB != 0, cmA != 0);

        if (scissor != 0) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
        } else {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        if (polyOffset != 0) {
            GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
            GlStateManager._disablePolygonOffset();
            GlStateManager._enablePolygonOffset();
        } else {
            GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
            GlStateManager._enablePolygonOffset();
            GlStateManager._disablePolygonOffset();
        }
        org.lwjgl.opengl.GL32.glProvokingVertex(provokingVertex);

        if (primRestart != 0) GL11.glEnable(org.lwjgl.opengl.GL31.GL_PRIMITIVE_RESTART);
        else GL11.glDisable(org.lwjgl.opengl.GL31.GL_PRIMITIVE_RESTART);
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, polyMode);

        GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER, uboGeneric);
        GL15.glBindBuffer(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, ssboGeneric);
        GL15.glBindBuffer(org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER, drawIndirect);
        GL15.glBindBuffer(org.lwjgl.opengl.ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB, parameterBuf);
        GL15.glBindBuffer(org.lwjgl.opengl.GL43.GL_DISPATCH_INDIRECT_BUFFER, dispatchIndirect);
        GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER, copyRead);
        GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER, copyWrite);
        GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, pixelPack);
        GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, pixelUnpack);
        GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_TEXTURE_BUFFER, texBufBinding);

        // Alpha test + fog are GlStateManager-cached fixed-function state on 1.16.5.
        float alphaRef = alphaRefx1000 / 1000f;
        GL11.glAlphaFunc(alphaFunc, alphaRef);
        GlStateManager._alphaFunc(alphaFunc == GL11.GL_GREATER ? GL11.GL_ALWAYS : GL11.GL_GREATER, alphaRef + 0.5f);
        GlStateManager._alphaFunc(alphaFunc, alphaRef);
        if (alphaTest != 0) {
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GlStateManager._disableAlphaTest();
            GlStateManager._enableAlphaTest();
        } else {
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GlStateManager._enableAlphaTest();
            GlStateManager._disableAlphaTest();
        }
        if (fogEnable != 0) {
            GL11.glEnable(GL11.GL_FOG);
            GlStateManager._disableFog();
            GlStateManager._enableFog();
        } else {
            GL11.glDisable(GL11.GL_FOG);
            GlStateManager._enableFog();
            GlStateManager._disableFog();
        }

        GL11.glDepthRange(drNear / 1_000_000.0, drFar / 1_000_000.0);
        GL11.glScissor(scX, scY, scW, scH);
    }

    // ---------------------------------------------------------------------------------------------
    // Extended, report-only state. Not restored -- voxy owns most of it -- but reported when it
    // moves, so a leak outside the restored set names itself instead of having to be guessed at.
    // ---------------------------------------------------------------------------------------------

    private static final String[] EXT_NAMES = {
            "sampler0", "sampler1", "sampler2", "sampler3", "sampler4", "sampler5", "sampler6", "sampler7",
            "tex4", "tex5", "tex6", "tex7",
            "texArray0", "texArray1", "texArray2", "texArray3",
            "image0", "image1", "image2", "image3",
            "ubo0", "ubo1", "ubo2", "ubo3",
            "ssbo0", "ssbo1", "ssbo2", "ssbo3", "ssbo4", "ssbo5", "ssbo6", "ssbo7",
            "drawIndirectBuf", "pixelUnpackBuf", "pixelPackBuf",
            "unpackAlign", "unpackRowLen", "packAlign", "packRowLen",
            "framebufferSrgb", "depthClamp", "programPointSize", "multisample", "dither",
            "sampleAlphaToCoverage",
            "stencilRef", "stencilFail", "stencilPassDepthPass", "stencilPassDepthFail",
            "stencilBackFunc", "stencilBackWriteMask",
            "rasterizerDiscard", "clipOrigin", "clipDepthMode",
            "tex8", "tex9", "tex10", "tex11",
            "texArray4", "texArray5", "texArray6", "texArray7",
            "tex3d0", "tex3d1", "texBuffer0", "texBuffer1", "texBuffer2", "texBuffer3",
            "sampler8", "sampler9", "sampler10", "sampler11",
    };

    public static int[] captureExtended() {
        int[] s = new int[EXT_NAMES.length];
        int i = 0;
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        for (int u = 0; u < 8; u++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + u);
            s[i++] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        }
        for (int u = 4; u < 8; u++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + u);
            s[i++] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }
        for (int u = 0; u < 4; u++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + u);
            s[i++] = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
        }
        GL13.glActiveTexture(active);

        for (int u = 0; u < 4; u++) s[i++] = GL30.glGetIntegeri(org.lwjgl.opengl.GL42.GL_IMAGE_BINDING_NAME, u);
        for (int u = 0; u < 4; u++) s[i++] = GL30.glGetIntegeri(org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER_BINDING, u);
        for (int u = 0; u < 8; u++) s[i++] = GL30.glGetIntegeri(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER_BINDING, u);

        s[i++] = GL11.glGetInteger(org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER_BINDING);
        s[i++] = GL11.glGetInteger(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        s[i++] = GL11.glGetInteger(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING);

        s[i++] = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        s[i++] = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
        s[i++] = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        s[i++] = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);

        s[i++] = b(GL30.GL_FRAMEBUFFER_SRGB);
        s[i++] = b(org.lwjgl.opengl.GL32.GL_DEPTH_CLAMP);
        s[i++] = b(org.lwjgl.opengl.GL32.GL_PROGRAM_POINT_SIZE);
        s[i++] = b(GL13.GL_MULTISAMPLE);
        s[i++] = b(GL11.GL_DITHER);
        s[i++] = b(GL13.GL_SAMPLE_ALPHA_TO_COVERAGE);

        s[i++] = GL11.glGetInteger(GL11.GL_STENCIL_REF);
        s[i++] = GL11.glGetInteger(GL11.GL_STENCIL_FAIL);
        s[i++] = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_PASS);
        s[i++] = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_FAIL);
        s[i++] = GL11.glGetInteger(GL20.GL_STENCIL_BACK_FUNC);
        s[i++] = GL11.glGetInteger(GL20.GL_STENCIL_BACK_WRITEMASK);

        s[i++] = b(GL30.GL_RASTERIZER_DISCARD);
        s[i++] = GL11.glGetInteger(org.lwjgl.opengl.GL45.GL_CLIP_ORIGIN);
        s[i++] = GL11.glGetInteger(org.lwjgl.opengl.GL45.GL_CLIP_DEPTH_MODE);
        int active2 = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        for (int u = 8; u < 12; u++) { GL13.glActiveTexture(GL13.GL_TEXTURE0 + u); s[i++] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D); }
        for (int u = 4; u < 8; u++)  { GL13.glActiveTexture(GL13.GL_TEXTURE0 + u); s[i++] = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY); }
        for (int u = 0; u < 2; u++)  { GL13.glActiveTexture(GL13.GL_TEXTURE0 + u); s[i++] = GL11.glGetInteger(org.lwjgl.opengl.GL12.GL_TEXTURE_BINDING_3D); }
        for (int u = 0; u < 4; u++)  { GL13.glActiveTexture(GL13.GL_TEXTURE0 + u); s[i++] = GL11.glGetInteger(org.lwjgl.opengl.GL31.GL_TEXTURE_BINDING_BUFFER); }
        for (int u = 8; u < 12; u++) { GL13.glActiveTexture(GL13.GL_TEXTURE0 + u); s[i++] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING); }
        GL13.glActiveTexture(active2);
        return s;
    }

    public static String diffExtended(int[] before, int[] after) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < EXT_NAMES.length; i++) {
            if (before[i] != after[i]) {
                if (sb.length() != 0) sb.append(", ");
                sb.append(EXT_NAMES[i]).append(' ').append(before[i]).append("->").append(after[i]);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Drains and names any pending GL errors, so a broken call on this version is not silent. */
    public static String drainErrors() {
        StringBuilder sb = new StringBuilder();
        for (int n = 0; n < 16; n++) {
            int e = GL11.glGetError();
            if (e == GL11.GL_NO_ERROR) break;
            if (sb.length() != 0) sb.append(", ");
            sb.append("0x").append(Integer.toHexString(e));
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
