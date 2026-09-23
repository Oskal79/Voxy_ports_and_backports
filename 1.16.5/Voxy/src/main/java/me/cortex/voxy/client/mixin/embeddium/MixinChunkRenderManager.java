package me.cortex.voxy.client.mixin.embeddium;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderContainer;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderManager;
import me.jellysquid.mods.sodium.client.render.chunk.data.ChunkRenderData;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.SectionPos;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks into Embeddium's ChunkRenderManager to:
 * 1. Feed chunks into Voxy's LoD store as Embeddium loads them (onChunkAdded).
 * 2. Reset the visible sections stream at the start of each frame (reset).
 * 3. Record visible built sections into visbleSectionStream so Voxy's LoD renderer
 *    can discard LoD geometry where vanilla chunks are being rendered (addChunk).
 */
@Mixin(value = ChunkRenderManager.class, remap = false)
public class MixinChunkRenderManager {

    @Inject(method = "reset", at = @At("HEAD"), remap = false)
    private void voxy$resetVisibleStream(CallbackInfo ci) {
        VoxyRenderSystem vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs != null && !IrisUtil.irisShadowActive()) {
            vrs.visbleSectionStream.reset();
        }
    }

    @Inject(method = "addChunkToRenderLists", at = @At("HEAD"), remap = false)
    private void voxy$recordVisibleSection(ChunkRenderContainer<?> chunk, CallbackInfo ci) {
        if (chunk == null) {
            return;
        }
        ChunkRenderData data = chunk.getData();
        if (data == null || data == ChunkRenderData.ABSENT || chunk.isEmpty() || chunk.getFacesWithData() == 0) {
            return;
        }
        if (IrisUtil.irisShadowActive()) {
            return;
        }
        VoxyRenderSystem vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs != null) {
            vrs.visbleSectionStream.put(SectionPos.asLong(chunk.getChunkX(), chunk.getChunkY(), chunk.getChunkZ()));
        }
    }

    @Inject(method = "onChunkAdded", at = @At("TAIL"), remap = false)
    private void voxy$ingestOnChunkAdded(int x, int z, CallbackInfo ci) {
        if (!VoxyConfig.CONFIG.ingestEnabled) {
            return;
        }
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Chunk chunk = level.getChunkSource().getChunk(x, z, false);
        if (chunk == null) {
            return;
        }
        VoxelIngestService.tryAutoIngestChunk(chunk);
    }
}
