package me.cortex.voxy.client.mixin.iris;

import com.google.common.collect.ImmutableSet;
import net.coderbot.iris.shaderpack.PackRenderTargetDirectives;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

@Mixin(value = PackRenderTargetDirectives.class, remap = false)
public abstract class MixinPackRenderTargetDirectives {
    @Shadow
    @Final
    @Mutable
    public static Set<Integer> BASELINE_SUPPORTED_RENDER_TARGETS;

    private static Set<Integer> voxy$createExpandedTargets() {
        ImmutableSet.Builder<Integer> builder = ImmutableSet.builder();
        for (int i = 0; i < 32; i++) {
            builder.add(i);
        }
        return builder.build();
    }

    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void voxy$expandSupportedRenderTargets(CallbackInfo ci) {
        BASELINE_SUPPORTED_RENDER_TARGETS = voxy$createExpandedTargets();
    }

    @ModifyVariable(method = "<init>(Ljava/util/Set;)V", at = @At("HEAD"), argsOnly = true)
    private static Set<Integer> voxy$ensureAllTargets(Set<Integer> set) {
        if (set == null || set.size() < 32) {
            return voxy$createExpandedTargets();
        }
        return set;
    }
}
