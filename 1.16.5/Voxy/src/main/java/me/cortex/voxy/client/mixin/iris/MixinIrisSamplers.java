package me.cortex.voxy.client.mixin.iris;

import com.google.common.collect.ImmutableSet;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.VoxySamplers;
import net.coderbot.iris.gl.sampler.SamplerHolder;
import net.coderbot.iris.rendertarget.RenderTargets;
import net.coderbot.iris.samplers.IrisSamplers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

@Mixin(value = IrisSamplers.class, remap = false)
public class MixinIrisSamplers {
    @Inject(method = "addRenderTargetSamplers", at = @At("TAIL"))
    private static void voxy$injectSamplers(SamplerHolder samplers, Supplier<ImmutableSet<Integer>> flipped, RenderTargets renderTargets, boolean isFullscreenPass, CallbackInfo ci) {
        if (VoxyConfig.CONFIG.enableRendering && IrisUtil.isIrisInstalled()) {
            VoxySamplers.addSamplers(samplers);
        }
    }
}
