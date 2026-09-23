package me.cortex.voxy.forge;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Bootstraps MixinExtras.
 * <p>
 * Newer loaders ship MixinExtras and initialise it themselves, but Forge 36.x does not, so the
 * shaded copy has to be started explicitly before any mixin using {@code @WrapOperation} is
 * applied. A mixin config plugin's {@code onLoad} is the earliest hook available for that.
 */
public class VoxyMixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {
        com.llamalad7.mixinextras.MixinExtrasBootstrap.init();
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains(".iris.")) {
            try {
                Class.forName("net.coderbot.iris.Iris", false, getClass().getClassLoader());
                return true;
            } catch (ClassNotFoundException e) {
                return false;
            }
        }
        return true;
    }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
