package me.cortex.voxy.client.mixin.iris;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.VoxyUniforms;
import net.coderbot.iris.gl.uniform.DynamicUniformHolder;
import net.coderbot.iris.shaderpack.IdMap;
import net.coderbot.iris.shaderpack.PackDirectives;
import net.coderbot.iris.uniforms.CommonUniforms;
import net.coderbot.iris.uniforms.FrameUpdateNotifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CommonUniforms.class, remap = false)
public class MixinMatrixUniforms {
    @Inject(method = "addCommonUniforms", at = @At("HEAD"))
    private static void voxy$InjectMatrixUniforms(DynamicUniformHolder uniforms, IdMap idMap, PackDirectives directives, FrameUpdateNotifier updateNotifier, CallbackInfo ci) {
        if (VoxyConfig.CONFIG.enableRendering && IrisUtil.isIrisInstalled()) {
            VoxyUniforms.addUniforms(uniforms);
        }
    }
}
