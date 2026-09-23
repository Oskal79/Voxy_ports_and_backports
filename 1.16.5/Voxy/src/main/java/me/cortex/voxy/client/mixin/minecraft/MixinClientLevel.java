package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.multiplayer.ClientChunkProvider;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.network.play.ClientPlayNetHandler;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.SectionPos;
import net.minecraft.util.RegistryKey;
import net.minecraft.profiler.IProfiler;
import net.minecraft.world.World;
import net.minecraft.world.LightType;
import net.minecraft.block.BlockState;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

@Mixin(ClientWorld.class)
public abstract class MixinClientLevel {

    @Unique
    private int bottomSectionY;

    @Shadow public abstract ClientChunkProvider getChunkSource();

    @Inject(method = "<init>", at = @At("TAIL"))
    // 1.21.1's ClientWorld ctor takes a profiler Supplier before the WorldRenderer and has no
    // trailing seaLevel argument; handler params must mirror it exactly.
    private void voxy$getBottom(ClientPlayNetHandler connection, ClientWorld.ClientWorldInfo levelData, RegistryKey dimension, net.minecraft.world.DimensionType dimensionType, int serverChunkRadius, Supplier<IProfiler> profiler, WorldRenderer levelRenderer, boolean isDebug, long biomeZoomSeed, CallbackInfo ci) {
        // 1.16.5 worlds always start at y=0.
        this.bottomSectionY = 0;
    }

    @Inject(method = "setBlocksDirty", at = @At("TAIL"))
    private void voxy$injectIngestOnStateChange(BlockPos pos, BlockState old, BlockState updated, CallbackInfo cir) {
        if (old == updated) return;

        //TODO: is this _really_ needed, we should have enough processing power to not need todo it if its only a
        // block removal
        if (!updated.isAir()) return;
        if (VoxyCommon.getInstance()==null) return;
        if (!VoxyConfig.CONFIG.ingestEnabled) return;//Only ingest if setting enabled

        var self = (World)(Object)this;
        var wi = WorldIdentifier.of(self);
        if (wi == null) {
            return;
        }

        int x = pos.getX()&15;
        int y = pos.getY()&15;
        int z = pos.getZ()&15;
        if (x == 0 || x==15 || y==0 || y==15 || z==0||z==15) {//Update if there is a statechange on the boarder
            var csp = SectionPos.of(pos);
            //Is not using voxy$cheekyGetChunk as dont think is need
            var chunk = self.getChunk(pos.getX()>>4, pos.getZ()>>4, ChunkStatus.FULL, false);
            if (chunk != null) {
                // 1.16.5's Chunk has no getSection(int); index the section array directly.
                var section = chunk.getSections()[csp.y() - this.bottomSectionY];
                var lp = self.getLightEngine();

                var blp = lp.getLayerListener(LightType.BLOCK).getDataLayerData(csp);
                var slp = lp.getLayerListener(LightType.SKY).getDataLayerData(csp);

                // 1.16.5 keeps biomes on the chunk, so they are passed alongside the section.
                VoxelIngestService.rawIngest(wi, section, chunk.getBiomes(), csp.x(), csp.y(), csp.z(), blp == null ? null : blp.copy(), slp == null ? null : slp.copy());
            }
        }
    }
}
