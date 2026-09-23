package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.play.ClientPlayNetHandler;
import net.minecraft.network.play.server.SUpdateLightPacket;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ingests a chunk once its lighting has actually arrived.
 * <p>
 * Ingesting from Embeddium's {@code onChunkAdded} alone captured every section with light 0:
 * 1.16.5 delivers lighting in a separate {@link SUpdateLightPacket}, so at chunk-add time the
 * light engine still has empty NibbleArrays for those sections and voxy's lighting supplier falls
 * back to a constant zero -- which is what made LoD terrain render black.
 * <p>
 * This runs at the tail of the light packet handler, i.e. after the data has been handed to the
 * light engine, so {@code getDataLayerData} returns real arrays. The chunk-added hook is kept as
 * well because the two packets can arrive in either order: whichever fires when both chunk and
 * light are present wins, and the other bails out harmlessly (ingest requires lighting and
 * returns false without it).
 */
@Mixin(ClientPlayNetHandler.class)
public class MixinClientPacketListenerLight {
    @Inject(method = "handleLightUpdatePacked", at = @At("TAIL"))
    private void voxy$ingestAfterLight(SUpdateLightPacket packet, CallbackInfo ci) {
        if (!VoxyConfig.CONFIG.ingestEnabled) {
            return;
        }
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        // Ingest immediately if chunk is already loaded. handleLightUpdatePacked has placed the
        // packet's NibbleArrays into queuedSections, so getDataLayerData answers with real data.
        var chunk = level.getChunkSource().getChunk(packet.getX(), packet.getZ(), false);
        if (chunk != null) {
            VoxelIngestService.tryAutoIngestChunk(chunk);
        }
        me.cortex.voxy.client.DeferredRelightIngest.queue(packet.getX(), packet.getZ());
    }
}
