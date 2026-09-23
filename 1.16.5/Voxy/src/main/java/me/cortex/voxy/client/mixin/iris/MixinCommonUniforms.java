package me.cortex.voxy.client.mixin.iris;

import me.cortex.voxy.client.iris.OculusExtendedUniforms;
import net.coderbot.iris.gl.uniform.UniformHolder;
import net.coderbot.iris.shaderpack.PackDirectives;
import net.coderbot.iris.uniforms.CommonUniforms;
import net.coderbot.iris.uniforms.FrameUpdateNotifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CommonUniforms.class, remap = false)
public class MixinCommonUniforms {
    @Inject(method = "generalCommonUniforms", at = @At("RETURN"))
    private static void voxy$addExtendedUniforms(UniformHolder uniforms, FrameUpdateNotifier updateNotifier, PackDirectives directives, CallbackInfo ci) {
        OculusExtendedUniforms.addExtendedUniforms(uniforms, directives);
    }
}
