package me.cortex.voxy.client.iris;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import net.coderbot.iris.gl.uniform.UniformHolder;
import net.coderbot.iris.gl.uniform.UniformUpdateFrequency;
import org.joml.Matrix4f;

import java.util.function.Supplier;

public class VoxyUniforms {
    public static float[] getMatrixArray(Supplier<Matrix4f> supplier) {
        Matrix4f m = supplier.get();
        if (m == null) m = new Matrix4f();
        float[] arr = new float[16];
        m.get(arr);
        return arr;
    }

    public static Matrix4f getViewProjection() {
        VoxyRenderSystem vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs == null || vrs.getViewport() == null) {
            return new Matrix4f(getProjection()).mul(getModelView());
        }
        return new Matrix4f(vrs.getViewport().MVP);
    }

    public static Matrix4f getModelView() {
        VoxyRenderSystem vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs == null || vrs.getViewport() == null) {
            return new Matrix4f(OculusUniformProvider.currentModelView);
        }
        return new Matrix4f(vrs.getViewport().modelView);
    }

    public static Matrix4f getProjection() {
        VoxyRenderSystem vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs == null || vrs.getViewport() == null) {
            return new Matrix4f(OculusUniformProvider.currentProjection);
        }
        Matrix4f mat = vrs.getViewport().projection;
        if (mat == null) {
            return new Matrix4f(OculusUniformProvider.currentProjection);
        }
        return new Matrix4f(mat);
    }

    public static void addUniforms(UniformHolder uniforms) {
        uniforms.uniform1i(UniformUpdateFrequency.PER_FRAME, "vxRenderDistance",
                () -> Math.round(VoxyConfig.CONFIG.sectionRenderDistance * 32));

        uniforms.uniformMatrixFromArray(UniformUpdateFrequency.PER_FRAME, "vxViewProj", () -> getMatrixArray(VoxyUniforms::getViewProjection));
        uniforms.uniformMatrixFromArray(UniformUpdateFrequency.PER_FRAME, "vxViewProjInv", new InvertedArr(VoxyUniforms::getViewProjection));
        uniforms.uniformMatrixFromArray(UniformUpdateFrequency.PER_FRAME, "vxViewProjPrev", new PreviousMatArr(VoxyUniforms::getViewProjection));

        uniforms.uniformMatrixFromArray(UniformUpdateFrequency.PER_FRAME, "vxModelView", () -> getMatrixArray(VoxyUniforms::getModelView));
        uniforms.uniformMatrixFromArray(UniformUpdateFrequency.PER_FRAME, "vxModelViewInv", new InvertedArr(VoxyUniforms::getModelView));
        uniforms.uniformMatrixFromArray(UniformUpdateFrequency.PER_FRAME, "vxModelViewPrev", new PreviousMatArr(VoxyUniforms::getModelView));

        uniforms.uniformMatrixFromArray(UniformUpdateFrequency.PER_FRAME, "vxProj", () -> getMatrixArray(VoxyUniforms::getProjection));
        uniforms.uniformMatrixFromArray(UniformUpdateFrequency.PER_FRAME, "vxProjInv", new InvertedArr(VoxyUniforms::getProjection));
        uniforms.uniformMatrixFromArray(UniformUpdateFrequency.PER_FRAME, "vxProjPrev", new PreviousMatArr(VoxyUniforms::getProjection));
    }

    private static class InvertedArr implements Supplier<float[]> {
        private final Supplier<Matrix4f> parent;
        InvertedArr(Supplier<Matrix4f> parent) {
            this.parent = parent;
        }
        @Override
        public float[] get() {
            Matrix4f copy = new Matrix4f(this.parent.get());
            copy.invert();
            float[] arr = new float[16];
            copy.get(arr);
            return arr;
        }
    }

    private static class PreviousMatArr implements Supplier<float[]> {
        private final Supplier<Matrix4f> parent;
        private Matrix4f previous = new Matrix4f();
        PreviousMatArr(Supplier<Matrix4f> parent) {
            this.parent = parent;
        }
        @Override
        public float[] get() {
            Matrix4f prev = this.previous;
            this.previous = new Matrix4f(this.parent.get());
            float[] arr = new float[16];
            prev.get(arr);
            return arr;
        }
    }
}
