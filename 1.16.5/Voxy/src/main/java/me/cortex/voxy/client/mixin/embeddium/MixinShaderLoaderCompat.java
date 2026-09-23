package me.cortex.voxy.client.mixin.embeddium;

import me.jellysquid.mods.sodium.client.gl.shader.ShaderLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fixes a fatal OpenGL shader program linking error when Oculus and Magnesium Extras are used together:
 * Magnesium Extras' FadeInChunks injects "varying float v_FadeInProgress;" into Sodium's chunk_gl20.f.glsl,
 * but Oculus redirects vertex shader loading to raw source without FadeInChunks, resulting in:
 * "fragment shader varying v_FadeInProgress not written by vertex shader".
 *
 * This mixin runs at priority 2000 (after Magnesium Extras' priority 1000) and strips out the broken
 * varying so that the shader program links successfully without crashing.
 */
@Mixin(value = ShaderLoader.class, remap = false, priority = 2000)
public class MixinShaderLoaderCompat {
    @Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true, remap = false)
    private static void voxy$sanitizeShaderSource(String path, CallbackInfoReturnable<String> cir) {
        String src = cir.getReturnValue();
        if (src != null && src.contains("v_FadeInProgress")) {
            src = src.replace("varying float v_FadeInProgress;\n", "")
                     .replace("varying float v_FadeInProgress;", "")
                     .replace("(min(v_FadeInProgress, getFogFactor()),", "(getFogFactor(),");
            cir.setReturnValue(src);
        }
    }
}
