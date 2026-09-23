package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.util.IrisUtil;

import static org.lwjgl.opengl.GL11C.*;

public final class RenderProperties {
    private final boolean isZero2One;
    private final boolean isReverseZ;
    private final boolean useBlockAtlasUVs;

    public RenderProperties(boolean isZero2One, boolean isReverseZ, boolean useBlockAtlasUVs) {
        this.isZero2One = isZero2One;
        this.isReverseZ = isReverseZ;
        this.useBlockAtlasUVs = useBlockAtlasUVs;
    }

    public boolean isZero2One() { return this.isZero2One; }
    public boolean isReverseZ() { return this.isReverseZ; }
    public boolean useBlockAtlasUVs() { return this.useBlockAtlasUVs; }

    @Override public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof RenderProperties)) return false;
        RenderProperties o = (RenderProperties) obj;
        return this.isZero2One == o.isZero2One && this.isReverseZ == o.isReverseZ && this.useBlockAtlasUVs == o.useBlockAtlasUVs;
    }

    @Override public int hashCode() { return java.util.Objects.hash(this.isZero2One, this.isReverseZ, this.useBlockAtlasUVs); }

    @Override public String toString() { return "RenderProperties[isZero2One=" + this.isZero2One + ", isReverseZ=" + this.isReverseZ + ", useBlockAtlasUVs=" + this.useBlockAtlasUVs + "]"; }


    public <T extends Shader.Builder<J>, J extends Shader> T apply(T builder) {
        return (T) builder.defineIf("USE_ZERO_ONE_DEPTH", this.isZero2One)
                .defineIf("USE_REVERSE_Z", this.isReverseZ);
    }

    public int closerEqualDepthCompare() {
        return this.isReverseZ?GL_GEQUAL:GL_LEQUAL;
    }

    public int closerDepthCompare() {
        return this.isReverseZ?GL_GREATER:GL_LESS;
    }

    public int furtherDepthCompare() {
        return this.isReverseZ?GL_LESS:GL_GREATER;
    }

    public float clearDepth() {
        return this.isReverseZ?0.0f:1.0f;
    }

    public float inverseClearDepth() {
        return this.isReverseZ?1.0f:0.0f;
    }







    // Stage 1 has no Iris pipeline, so the block-atlas UV path is never taken.
    private static boolean irisUseBlockAtlasUv() {
        return false;
    }

    /**
     * Minecraft 1.21.1 draws with the classic depth setup: no reverse-Z (that arrived with the
     * 1.21.5/1.21.6 render rewrite's DepthStencilState/CompareOp, neither of which exists here), and
     * no glClipControl call anywhere in the client, so the depth range stays -1..1 rather than 0..1.
     * Both probes are therefore constant on this version.
     */
    private static boolean useReverseZ() {
        return false;
    }

    private static boolean useZeroToOneDepth() {
        return false;
    }

    public static RenderProperties getRenderProperties() {
        RenderProperties properties = new RenderProperties(
                useZeroToOneDepth(),
                useReverseZ(),
                false);

        if (IrisUtil.IRIS_INSTALLED && IrisUtil.SHADER_SUPPORT) {
            properties = new RenderProperties(properties.isZero2One(), properties.isReverseZ(), irisUseBlockAtlasUv());
        }

        return properties;
    }
}
