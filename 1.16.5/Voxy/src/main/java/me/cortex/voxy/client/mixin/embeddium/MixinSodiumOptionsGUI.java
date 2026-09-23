package me.cortex.voxy.client.mixin.embeddium;

import me.cortex.voxy.client.config.VoxyOptionPage;
import me.cortex.voxy.common.Logger;
import me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Adds voxy's page to Embeddium's video-settings screen.
 * <p>
 * Embeddium 0.3.18 builds its page list inline in the constructor and offers no registration API
 * for third-party pages, so the only way in is to append after it has finished. Modern Sodium
 * (which the 1.21.1 build targets) has ConfigManager for exactly this.
 */
@Mixin(value = SodiumOptionsGUI.class, remap = false)
public class MixinSodiumOptionsGUI {
    @Shadow @Final private List<OptionPage> pages;

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void voxy$addPage(Screen prevScreen, CallbackInfo ci) {
        try {
            this.pages.add(VoxyOptionPage.create());
        } catch (Throwable t) {
            // A broken options page must not take the whole video-settings screen down.
            Logger.error("Failed to add voxy's options page", t);
        }
    }
}
