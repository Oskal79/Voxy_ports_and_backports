package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SectionCollector;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records which sections Sodium is going to draw itself, so Voxy can skip LoDs there.
 * <p>
 * Replaces the 0.9.x-era {@code MixinVisibleChunkCollector} + {@code MixinFallbackVisibleChunkCollector}.
 * Sodium 0.8.13 (the 1.21.1 line) has no {@code VisibleChunkCollector}/{@code FallbackVisibleChunkCollector}
 * and no {@code RenderSectionFlags.MASK_IS_BUILT}; visible sections instead funnel through
 * {@link SectionCollector}'s private {@code visit(RenderSection, int)}, and {@link RenderSection}
 * carries both its chunk coordinates and an {@code isBuilt()} flag directly -- so this is a single,
 * simpler hook with no region/local-index lookup needed.
 */
@Mixin(value = SectionCollector.class, remap = false)
public class MixinSectionCollector {
    /**
     * A fresh SectionCollector marks the start of a visibility pass. 0.9.x reset the stream from
     * RenderSectionManager#renderOutOfGraph / #readRenderListFromTree, neither of which exists in
     * 0.8.13, so the reset lives here instead -- same "reset, then collect" ordering.
     */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void voxy$resetVisibleStream(int frame, TaskQueueType importantRebuild, TaskQueueType importantSort, CallbackInfo ci) {
        VoxyRenderSystem vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs != null && !IrisUtil.irisShadowActive()) {
            vrs.visbleSectionStream.reset();
        }
    }

    @Inject(method = "visit(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;I)V",
            at = @At("HEAD"), remap = false)
    private void voxy$recordVisibleSection(RenderSection section, int flags, CallbackInfo ci) {
        if (section == null || !section.isBuilt()) {
            return;
        }
        if (IrisUtil.irisShadowActive()) {
            return;
        }
        VoxyRenderSystem vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs != null) {
            vrs.visbleSectionStream.put(SectionPos.asLong(section.getChunkX(), section.getChunkY(), section.getChunkZ()));
        }
    }
}
