package me.cortex.voxy.client.mixin.iris;

import com.google.common.collect.ImmutableSet;
import net.coderbot.iris.shaderpack.ProgramDirectives;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Set;

@Mixin(value = ProgramDirectives.class, remap = false)
public abstract class MixinProgramDirectives {
    @ModifyVariable(method = "<init>(Lnet/coderbot/iris/shaderpack/ProgramSource;Lnet/coderbot/iris/shaderpack/ShaderProperties;Ljava/util/Set;Lnet/coderbot/iris/gl/blending/BlendModeOverride;)V", at = @At("HEAD"), argsOnly = true)
    private static Set<Integer> voxy$ensureAllProgramTargets(Set<Integer> set) {
        if (set == null || set.size() < 32) {
            ImmutableSet.Builder<Integer> builder = ImmutableSet.builder();
            for (int i = 0; i < 32; i++) {
                builder.add(i);
            }
            return builder.build();
        }
        return set;
    }
}
