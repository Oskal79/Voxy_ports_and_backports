package me.cortex.voxy.client.core.util;

import me.jellysquid.mods.sodium.client.util.color.ColorABGR;

/**
 * sRGB &harr; linear conversion, matching the {@code ColorSRGB} that modern Sodium exposes.
 * <p>
 * Embeddium 0.3.18 for 1.16.5 predates it -- that build only ships {@code ColorABGR},
 * {@code ColorARGB}, {@code ColorMixer} and {@code ColorU8} -- so voxy's texture downsampling
 * needs its own. Averaging texels has to happen in linear space; doing it on raw sRGB bytes
 * makes mipped block textures visibly too dark.
 * <p>
 * These are the standard sRGB transfer functions rather than a gamma-2.2 approximation, which is
 * what Sodium's lookup table encodes (its entry for 1/255 is exactly {@code (1/255)/12.92}, the
 * linear segment). The table here is computed at class-init from the same formula so the values
 * agree.
 */
public final class ColorSRGB {
    private static final float[] FROM_SRGB8 = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            float c = i / 255.0f;
            FROM_SRGB8[i] = c <= 0.04045f
                    ? c / 12.92f
                    : (float) Math.pow((c + 0.055f) / 1.055f, 2.4);
        }
    }

    private ColorSRGB() {}

    /** Converts one 8-bit sRGB channel to linear. */
    public static float srgbToLinear(int c) {
        return FROM_SRGB8[c & 0xFF];
    }

    /** Converts linear RGB back to sRGB and packs it with the given alpha, ABGR order. */
    public static int linearToSrgb(float r, float g, float b, int a) {
        return ColorABGR.pack(linearToSrgb(r), linearToSrgb(g), linearToSrgb(b), a);
    }

    private static int linearToSrgb(float c) {
        if (!(c > 0.0f)) {   // also catches NaN
            c = 0.0f;
        } else if (c > 1.0f) {
            c = 1.0f;
        }
        float srgb = c <= 0.0031308f
                ? c * 12.92f
                : 1.055f * (float) Math.pow(c, 1.0 / 2.4) - 0.055f;
        return Math.round(srgb * 255.0f);
    }
}
