package me.cortex.voxy.client.mixin.minecraft.util;

import me.cortex.voxy.client.GPUSelectorWindows2;
import me.cortex.voxy.common.util.ThreadUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinGPUSelect {
    // 1.21.1's Minecraft constructor never calls Options#save; HEAD still runs before the
    // window and GL context are created, which is all this needs.
    // HEAD of a constructor is *before* super(), where `this` does not yet exist, so Mixin requires
    // the handler to be static ("@Inject handler before super() invocation must be static").
    // Nothing here touches instance state, so that costs nothing.
    @Inject(method = "<init>", at = @At("HEAD"))
    private static void voxy$injectInitWindow(GameConfig gc, CallbackInfo ci) {
        //System.load("C:\\Program Files\\RenderDoc\\renderdoc.dll");
        var prop = System.getProperty("voxy.forceGpuSelectionIndex", "NO");
        if (!prop.equals("NO")) {
            GPUSelectorWindows2.doSelector(Integer.parseInt(prop));
        }

        //Force the current thread priority to be realtime
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        ThreadUtils.SetSelfThreadPriorityWin32(ThreadUtils.WIN32_THREAD_PRIORITY_TIME_CRITICAL);
    }
}
