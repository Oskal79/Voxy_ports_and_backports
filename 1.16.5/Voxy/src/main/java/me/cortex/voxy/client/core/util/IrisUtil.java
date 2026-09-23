package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import org.joml.Matrix4f;

import java.lang.reflect.Method;

/**
 * Shader-pack integration facade.
 * <p>
 * Queries Oculus / Iris runtime state dynamically via {@code net.irisshaders.iris.api.v0.IrisApi}
 * using reflection to maintain zero compile-time coupling.
 */
public class IrisUtil {
    /**
     * Projection/model-view pair captured for a frame.
     * <p>
     * Stage 1 defines the matrices locally rather than borrowing Sodium's
     * {@code ChunkRenderMatrices}, which Embeddium 0.3.18 does not have.
     */
    public static final class CapturedViewportParameters {
        private final Matrix4f projection;
        private final Matrix4f modelView;
        private final VoxyFogParameters parameters;
        private final int width;
        private final int height;
        private final double x;
        private final double y;
        private final double z;

        public CapturedViewportParameters(Matrix4f projection, Matrix4f modelView,
                                          VoxyFogParameters parameters,
                                          int width, int height, double x, double y, double z) {
            this.projection = projection;
            this.modelView = modelView;
            this.parameters = parameters;
            this.width = width;
            this.height = height;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public Matrix4f projection() { return this.projection; }
        public Matrix4f modelView() { return this.modelView; }
        public VoxyFogParameters parameters() { return this.parameters; }
        public int width() { return this.width; }
        public int height() { return this.height; }
        public double x() { return this.x; }
        public double y() { return this.y; }
        public double z() { return this.z; }

        public Viewport<?> apply(VoxyRenderSystem vrs) {
            return vrs.setupViewport(this.projection, this.modelView, this.parameters,
                    this.width, this.height, this.x, this.y, this.z);
        }
    }

    public static CapturedViewportParameters CAPTURED_VIEWPORT_PARAMETERS;

    private static Class<?> IRIS_API_CLASS;
    private static Method GET_INSTANCE_METHOD;
    private static Method IS_SHADER_PACK_IN_USE_METHOD;
    private static Method IS_RENDERING_SHADOW_PASS_METHOD;
    private static Method GET_CONFIG_METHOD;
    private static Method ARE_SHADERS_ENABLED_METHOD;
    private static Method SET_SHADERS_ENABLED_AND_APPLY_METHOD;
    private static boolean INITIALIZED = false;

    private static synchronized void initIris() {
        if (INITIALIZED) return;
        INITIALIZED = true;
        try {
            IRIS_API_CLASS = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            GET_INSTANCE_METHOD = IRIS_API_CLASS.getMethod("getInstance");
            IS_SHADER_PACK_IN_USE_METHOD = IRIS_API_CLASS.getMethod("isShaderPackInUse");
            IS_RENDERING_SHADOW_PASS_METHOD = IRIS_API_CLASS.getMethod("isRenderingShadowPass");
            GET_CONFIG_METHOD = IRIS_API_CLASS.getMethod("getConfig");
            Class<?> configClass = Class.forName("net.irisshaders.iris.api.v0.IrisApiConfig");
            ARE_SHADERS_ENABLED_METHOD = configClass.getMethod("areShadersEnabled");
            SET_SHADERS_ENABLED_AND_APPLY_METHOD = configClass.getMethod("setShadersEnabledAndApply", boolean.class);
        } catch (Throwable t) {
            IRIS_API_CLASS = null;
        }
    }

    public static boolean isIrisInstalled() {
        initIris();
        return IRIS_API_CLASS != null;
    }

    public static final boolean IRIS_INSTALLED = true;
    public static final boolean SHADER_SUPPORT = true;

    public static boolean isShaderPackVoxyCompatible() {
        if (!irisShaderPackEnabled()) return false;
        try {
            return OculusPipelineBridge.findActiveShaderPatch() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean irisShadowActive() {
        initIris();
        if (IRIS_API_CLASS == null) return false;
        try {
            Object api = GET_INSTANCE_METHOD.invoke(null);
            if (api == null) return false;
            return Boolean.TRUE.equals(IS_RENDERING_SHADOW_PASS_METHOD.invoke(api));
        } catch (Throwable t) {
            return false;
        }
    }

    public static void clearIrisSamplers() {
        // no shader pack in stage 1, so no Iris samplers are bound
    }

    public static void reload() {
        OculusPipelineBridge.invalidateCache();
    }

    public static boolean irisShaderPackEnabled() {
        initIris();
        if (IRIS_API_CLASS == null) return false;
        try {
            Object api = GET_INSTANCE_METHOD.invoke(null);
            if (api == null) return false;
            return Boolean.TRUE.equals(IS_SHADER_PACK_IN_USE_METHOD.invoke(api));
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean irisShadersEnabledInConfig() {
        initIris();
        if (IRIS_API_CLASS == null) return false;
        try {
            Object api = GET_INSTANCE_METHOD.invoke(null);
            if (api == null) return false;
            Object cfg = GET_CONFIG_METHOD.invoke(api);
            if (cfg == null) return false;
            return Boolean.TRUE.equals(ARE_SHADERS_ENABLED_METHOD.invoke(cfg));
        } catch (Throwable t) {
            return false;
        }
    }

    public static void disableIrisShaders() {
        initIris();
        if (IRIS_API_CLASS == null) return;
        try {
            Object api = GET_INSTANCE_METHOD.invoke(null);
            if (api == null) return;
            Object cfg = GET_CONFIG_METHOD.invoke(api);
            if (cfg == null) return;
            SET_SHADERS_ENABLED_AND_APPLY_METHOD.invoke(cfg, false);
        } catch (Throwable ignored) {}
    }
}
