package me.cortex.voxy.client.mixin.minecraft;


import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.VoxyClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//Thanks iris for making me need todo this ;-; _irritater_
@Mixin(RenderSystem.class)
public class MixinRenderSystem {
    //We need to inject before iris to initalize our systems
    // Mixin 0.8.5 (Forge 36.x) has no injector `order`; ordering vs Oculus is handled
    // by mixin config priority instead.
    @Inject(method = "initRenderer", remap = false, at = @At("RETURN"))
    // 1.21.1: initRenderer(int debugVerbosity, boolean synchronous) -- there is no GpuDevice yet.
    private static void voxy$injectInit(int debugVerbosity, boolean synchronous, CallbackInfo ci) {
        VoxyClient.initVoxyClient();
    }
}
