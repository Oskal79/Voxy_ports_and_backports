package me.cortex.voxy.client.core.util;

import com.mojang.blaze3d.systems.RenderSystem;

/**
 * Stand-in for Sodium's {@code net.caffeinemc.mods.sodium.client.util.FogParameters}, which does not
 * exist in the Sodium 0.8.x line that targets 1.21.1.
 * <p>
 * Voxy only ever reads the environmental fog distances and the fog colour off that type, so this
 * record keeps the exact same accessor names and simply sources the values from 1.21.1's
 * {@link RenderSystem} fog state. That keeps {@code Viewport} and {@code NormalRenderPipeline}
 * unchanged apart from the type name.
 */
public final class VoxyFogParameters {
    private final float environmentalStart;
    private final float environmentalEnd;
    private final float red;
    private final float green;
    private final float blue;
    private final float alpha;

    public VoxyFogParameters(float environmentalStart, float environmentalEnd, float red, float green, float blue, float alpha) {
        this.environmentalStart = environmentalStart;
        this.environmentalEnd = environmentalEnd;
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.alpha = alpha;
    }

    public float environmentalStart() { return this.environmentalStart; }
    public float environmentalEnd() { return this.environmentalEnd; }
    public float red() { return this.red; }
    public float green() { return this.green; }
    public float blue() { return this.blue; }
    public float alpha() { return this.alpha; }

    @Override public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof VoxyFogParameters)) return false;
        VoxyFogParameters o = (VoxyFogParameters) obj;
        return this.environmentalStart == o.environmentalStart && this.environmentalEnd == o.environmentalEnd && this.red == o.red && this.green == o.green && this.blue == o.blue && this.alpha == o.alpha;
    }

    @Override public int hashCode() { return java.util.Objects.hash(this.environmentalStart, this.environmentalEnd, this.red, this.green, this.blue, this.alpha); }

    @Override public String toString() { return "VoxyFogParameters[environmentalStart=" + this.environmentalStart + ", environmentalEnd=" + this.environmentalEnd + ", red=" + this.red + ", green=" + this.green + ", blue=" + this.blue + ", alpha=" + this.alpha + "]"; }


    public static final VoxyFogParameters NONE = new VoxyFogParameters(Float.MAX_VALUE, Float.MAX_VALUE, 0, 0, 0, 0);

    /** Snapshots the fog state Minecraft currently has bound. */
    public static VoxyFogParameters capture() {
        // 1.16.5 predates shader-driven fog (RenderSystem#getShaderFogStart and friends arrived
        // with the 1.17 blaze3d rewrite). Fog here is still fixed-function GL state, so query it
        // back from the driver -- that is the same state the game just bound.
        java.nio.FloatBuffer colour = org.lwjgl.BufferUtils.createFloatBuffer(4);
        org.lwjgl.opengl.GL11.glGetFloatv(org.lwjgl.opengl.GL11.GL_FOG_COLOR, colour);
        float start = org.lwjgl.opengl.GL11.glGetFloat(org.lwjgl.opengl.GL11.GL_FOG_START);
        float end = org.lwjgl.opengl.GL11.glGetFloat(org.lwjgl.opengl.GL11.GL_FOG_END);
        return new VoxyFogParameters(start, end,
                colour.get(0), colour.get(1), colour.get(2), colour.get(3));
    }
}
