package me.cortex.voxy.client.mixin.iris;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.IrisShaderPatch;
import net.coderbot.iris.gl.shader.StandardMacros;
import net.coderbot.iris.shaderpack.StringPair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Collection;
import java.util.List;

@Mixin(value = StandardMacros.class, remap = false)
public abstract class MixinStandardMacros {
    @Shadow
    private static void define(List<StringPair> defines, String key, String value) {}

    @WrapOperation(method = "createStandardEnvironmentDefines", at = @At(value = "INVOKE", target = "Lcom/google/common/collect/ImmutableList;copyOf(Ljava/util/Collection;)Lcom/google/common/collect/ImmutableList;"))
    private static ImmutableList<StringPair> voxy$injectVoxyDefine(Collection<StringPair> list, Operation<ImmutableList<StringPair>> original) {
        if (VoxyConfig.CONFIG.enableRendering && IrisUtil.isIrisInstalled()) {
            define((List<StringPair>) list, "VOXY", Integer.toString(IrisShaderPatch.SHADER_DEFINE_VERSION));
            define((List<StringPair>) list, "IRIS_FEATURE_VOXY", "1");
        }
        return original.call(list);
    }
}
