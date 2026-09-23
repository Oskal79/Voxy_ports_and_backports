package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.iris.IrisShaderPatch;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import me.cortex.voxy.common.Logger;
import net.coderbot.iris.Iris;
import net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline;
import net.coderbot.iris.pipeline.PipelineManager;
import net.coderbot.iris.pipeline.WorldRenderingPipeline;
import net.coderbot.iris.rendertarget.DepthTexture;
import net.coderbot.iris.rendertarget.RenderTarget;
import net.coderbot.iris.rendertarget.RenderTargets;
import net.coderbot.iris.shaderpack.ShaderPack;
import net.coderbot.iris.shaderpack.include.AbsolutePackPath;
import net.coderbot.iris.shadows.ShadowRenderTargets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AtlasTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;

public class OculusPipelineBridge {
    private static Field RENDER_TARGETS_FIELD;
    private static Field SHADOW_RENDER_TARGETS_FIELD;
    private static Field SOURCE_PROVIDER_FIELD;

    static {
        try {
            RENDER_TARGETS_FIELD = DeferredWorldRenderingPipeline.class.getDeclaredField("renderTargets");
            RENDER_TARGETS_FIELD.setAccessible(true);
        } catch (Exception e) {
            Logger.error("Failed to locate DeferredWorldRenderingPipeline.renderTargets field", e);
        }

        try {
            SHADOW_RENDER_TARGETS_FIELD = DeferredWorldRenderingPipeline.class.getDeclaredField("shadowRenderTargets");
            SHADOW_RENDER_TARGETS_FIELD.setAccessible(true);
        } catch (Exception e) {
            Logger.error("Failed to locate DeferredWorldRenderingPipeline.shadowRenderTargets field", e);
        }

        try {
            SOURCE_PROVIDER_FIELD = ShaderPack.class.getDeclaredField("sourceProvider");
            SOURCE_PROVIDER_FIELD.setAccessible(true);
        } catch (Exception e) {
            Logger.error("Failed to locate ShaderPack.sourceProvider field", e);
        }
    }

    public static DeferredWorldRenderingPipeline getDeferredPipeline() {
        if (!IrisUtil.isIrisInstalled()) {
            return null;
        }
        PipelineManager pm = Iris.getPipelineManager();
        if (pm == null) {
            return null;
        }
        WorldRenderingPipeline pipe = pm.getPipelineNullable();
        if (pipe instanceof DeferredWorldRenderingPipeline) {
            return (DeferredWorldRenderingPipeline) pipe;
        }
        return null;
    }

    public static RenderTargets getRenderTargets(DeferredWorldRenderingPipeline pipeline) {
        if (pipeline == null || RENDER_TARGETS_FIELD == null) {
            return null;
        }
        try {
            return (RenderTargets) RENDER_TARGETS_FIELD.get(pipeline);
        } catch (Exception e) {
            Logger.error("Failed to get RenderTargets from DeferredWorldRenderingPipeline", e);
            return null;
        }
    }

    public static ShadowRenderTargets getShadowRenderTargets(DeferredWorldRenderingPipeline pipeline) {
        if (pipeline == null || SHADOW_RENDER_TARGETS_FIELD == null) {
            return null;
        }
        try {
            return (ShadowRenderTargets) SHADOW_RENDER_TARGETS_FIELD.get(pipeline);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Function<AbsolutePackPath, String> getSourceProvider(ShaderPack pack) {
        if (pack == null || SOURCE_PROVIDER_FIELD == null) {
            return null;
        }
        try {
            return (Function<AbsolutePackPath, String>) SOURCE_PROVIDER_FIELD.get(pack);
        } catch (Exception e) {
            Logger.error("Failed to get sourceProvider from ShaderPack", e);
            return null;
        }
    }

    private static boolean CACHE_VALID = false;
    private static IrisShaderPatch CACHED_PATCH = null;
    private static String LAST_PACK_NAME = null;
    private static String LAST_DIMENSION = null;
    private static Object LAST_PIPELINE = null;

    public static void invalidateCache() {
        CACHE_VALID = false;
        CACHED_PATCH = null;
        LAST_PACK_NAME = null;
        LAST_DIMENSION = null;
        LAST_PIPELINE = null;
    }

    public static IrisShaderPatch findActiveShaderPatch() {
        if (!IrisUtil.isIrisInstalled()) {
            return null;
        }
        String packName = Iris.getCurrentPackName();
        if (packName == null || packName.trim().isEmpty() || "OFF".equalsIgnoreCase(packName)) {
            return null;
        }

        String dimension = "world0";
        try {
            if (Minecraft.getInstance().level != null && Minecraft.getInstance().level.dimension() != null) {
                String loc = Minecraft.getInstance().level.dimension().location().toString();
                if (loc.contains("nether")) dimension = "world-1";
                else if (loc.contains("end")) dimension = "world1";
            }
        } catch (Throwable ignored) {}

        Object currentPipeline = null;
        try {
            currentPipeline = Iris.getPipelineManager().getPipeline();
        } catch (Throwable ignored) {}

        if (CACHE_VALID && Objects.equals(packName, LAST_PACK_NAME)
                && Objects.equals(dimension, LAST_DIMENSION)
                && Objects.equals(currentPipeline, LAST_PIPELINE)) {
            return CACHED_PATCH;
        }

        Path shaderpacksDir = Iris.getShaderpacksDirectory();
        if (shaderpacksDir == null) {
            return null;
        }
        Path packPath = shaderpacksDir.resolve(packName);
        if (!Files.exists(packPath)) {
            Logger.warn("[voxy-iris] Active shaderpack file not found: " + packPath);
            CACHE_VALID = true;
            CACHED_PATCH = null;
            LAST_PACK_NAME = packName;
            LAST_DIMENSION = dimension;
            LAST_PIPELINE = currentPipeline;
            return null;
        }

        ShaderPack pack = Iris.getCurrentPack().orElse(null);
        IrisShaderPatch patch = IrisShaderPatch.loadFromPack(packPath, dimension, pack);
        if (patch != null) {
            CACHE_VALID = true;
            CACHED_PATCH = patch;
            LAST_PACK_NAME = packName;
            LAST_DIMENSION = dimension;
            LAST_PIPELINE = currentPipeline;
            return patch;
        }

        // Fallback to old makePatch if loadFromPack didn't find anything
        Function<AbsolutePackPath, String> sp = getSourceProvider(pack);
        if (sp != null) {
            AbsolutePackPath programDir = AbsolutePackPath.fromAbsolutePath("/shaders/program");
            patch = IrisShaderPatch.makePatch(pack, programDir, sp);
            if (patch == null) {
                AbsolutePackPath rootDir = AbsolutePackPath.fromAbsolutePath("/shaders");
                patch = IrisShaderPatch.makePatch(pack, rootDir, sp);
            }
            if (patch != null) {
                CACHE_VALID = true;
                CACHED_PATCH = patch;
                LAST_PACK_NAME = packName;
                LAST_DIMENSION = dimension;
                LAST_PIPELINE = currentPipeline;
                return patch;
            }
        }

        Logger.warn("[voxy-iris] Shaderpack " + packName + " has no compatible Voxy configuration");
        CACHE_VALID = true;
        CACHED_PATCH = null;
        LAST_PACK_NAME = packName;
        LAST_DIMENSION = dimension;
        LAST_PIPELINE = currentPipeline;
        return null;
    }

    public static int getTextureId(RenderTargets rt, int targetIndex) {
        if (rt == null || targetIndex < 0 || targetIndex >= rt.getRenderTargetCount()) {
            return 0;
        }
        RenderTarget target = rt.get(targetIndex);
        return target != null ? target.getMainTexture() : 0;
    }

    public static int getNoiseTextureId() {
        return 0;
    }

    public static IrisVoxyRenderPipelineData.SamplerSet buildSamplerSet(
            Map<String, String> samplers,
            RenderTargets rt,
            DeferredWorldRenderingPipeline dpipe) {

        Map<String, String> effectiveSamplers = new LinkedHashMap<>();
        if (samplers != null) {
            effectiveSamplers.putAll(samplers);
        }
        effectiveSamplers.putIfAbsent("vxDepthTexOpaque", "sampler2D");
        effectiveSamplers.putIfAbsent("vxDepthTexTrans", "sampler2D");

        StringBuilder layout = new StringBuilder();
        int baseBinding = 6;
        int idx = 0;

        IntSupplier[] suppliers = new IntSupplier[effectiveSamplers.size()];
        int[] targets = new int[effectiveSamplers.size()];

        for (Map.Entry<String, String> entry : effectiveSamplers.entrySet()) {
            String name = entry.getKey();
            String type = entry.getValue();
            int binding = baseBinding + idx;

            layout.append("layout(binding = ").append(binding).append(") uniform ").append(type).append(" ").append(name).append(";\n");

            targets[idx] = GL11.GL_TEXTURE_2D;
            final String sName = name;

            suppliers[idx] = () -> resolveSamplerTexture(sName, rt, dpipe);
            idx++;
        }

        Consumer<Integer> bindingFunc = baseUnit -> {
            for (int i = 0; i < suppliers.length; i++) {
                int unit = baseUnit + i;
                int texId = suppliers[i].getAsInt();
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
                GL11.glBindTexture(targets[i], texId);
            }
        };

        return new IrisVoxyRenderPipelineData.SamplerSet(layout.toString(), bindingFunc);
    }

    private static int resolveSamplerTexture(String name, RenderTargets rt, DeferredWorldRenderingPipeline dpipe) {
        if ("tex".equals(name)) {
            return Minecraft.getInstance().getTextureManager().getTexture(AtlasTexture.LOCATION_BLOCKS).getId();
        }
        if ("vxDepthTexOpaque".equals(name)) {
            return me.cortex.voxy.client.iris.VoxySamplers.getOpaqueDepthTextureId();
        }
        if ("vxDepthTexTrans".equals(name)) {
            return me.cortex.voxy.client.iris.VoxySamplers.getTranslucentDepthTextureId();
        }
        if ("depthtex0".equals(name)) {
            return rt != null ? rt.getDepthTexture() : 0;
        }
        if ("depthtex1".equals(name)) {
            if (rt != null) {
                DepthTexture dt = rt.getDepthTextureNoTranslucents();
                return dt != null ? dt.getTextureId() : rt.getDepthTexture();
            }
            return 0;
        }
        if ("depthtex2".equals(name)) {
            if (rt != null) {
                DepthTexture dt = rt.getDepthTextureNoHand();
                return dt != null ? dt.getTextureId() : rt.getDepthTexture();
            }
            return 0;
        }
        if ("shadowtex0".equals(name) || "shadowtex1".equals(name)) {
            ShadowRenderTargets srt = getShadowRenderTargets(dpipe);
            return (srt != null && srt.getDepthTexture() != null) ? srt.getDepthTexture().getTextureId() : 0;
        }
        if ("shadowcolor0".equals(name)) {
            ShadowRenderTargets srt = getShadowRenderTargets(dpipe);
            return (srt != null && srt.getNumColorTextures() > 0) ? srt.getColorTextureId(0) : 0;
        }
        if ("noisetex".equals(name)) {
            return getNoiseTextureId();
        }
        if (name.startsWith("colortex")) {
            try {
                int id = Integer.parseInt(name.substring("colortex".length()));
                return getTextureId(rt, id);
            } catch (Exception ignored) {}
        }
        if ("gaux1".equals(name)) return getTextureId(rt, 4);
        if ("gaux2".equals(name)) return getTextureId(rt, 5);
        if ("gaux3".equals(name)) return getTextureId(rt, 6);
        if ("gaux4".equals(name)) return getTextureId(rt, 7);

        return 0;
    }

    public static String getStandardEnvironmentDefines() {
        StringBuilder sb = new StringBuilder();
        try {
            for (net.coderbot.iris.shaderpack.StringPair sp : net.coderbot.iris.gl.shader.StandardMacros.createStandardEnvironmentDefines()) {
                sb.append("#ifndef ").append(sp.getKey()).append("\n")
                  .append("#define ").append(sp.getKey()).append(" ").append(sp.getValue()).append("\n")
                  .append("#endif\n");
            }
        } catch (Throwable ignored) {}
        return sb.toString();
    }
}
