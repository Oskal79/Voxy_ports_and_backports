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
public record VoxyFogParameters(float environmentalStart, float environmentalEnd,
                                float red, float green, float blue, float alpha) {

    public static final VoxyFogParameters NONE = new VoxyFogParameters(Float.MAX_VALUE, Float.MAX_VALUE, 0, 0, 0, 0);

    /** Snapshots the fog state Minecraft currently has bound. */
    public static VoxyFogParameters capture() {
        float[] colour = RenderSystem.getShaderFogColor();
        float r = 0, g = 0, b = 0, a = 1;
        if (colour != null && colour.length >= 4) {
            r = colour[0];
            g = colour[1];
            b = colour[2];
            a = colour[3];
        }
        return new VoxyFogParameters(RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd(), r, g, b, a);
    }
}
