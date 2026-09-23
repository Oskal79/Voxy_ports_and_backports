package me.cortex.voxy.common.voxelization;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.block.BlockState;
import net.minecraft.util.palette.PalettedContainer;
import net.minecraft.world.biome.BiomeContainer;

import java.util.WeakHashMap;

public class WorldConversionFactory {

    private static final class Cache {
        private final int[] biomeCache = new int[4*4*4];
        private final WeakHashMap<Mapper, Reference2IntOpenHashMap<BlockState>> localMapping = new WeakHashMap<>();
        private int[] paletteCache = new int[1024];
        private final long[] zoomCellCache = new long[5*5*5];
        private Reference2IntOpenHashMap<BlockState> getLocalMapping(Mapper mapper) {
            return this.localMapping.computeIfAbsent(mapper, (a_)->new Reference2IntOpenHashMap<>());
        }
        private int[] getPaletteCache(int size) {
            if (this.paletteCache.length < size) {
                this.paletteCache = new int[size];
            }
            return this.paletteCache;
        }
    }

    //TODO: create a mapping for world/mapper -> local mapping
    private static final ThreadLocal<Cache> THREAD_LOCAL = ThreadLocal.withInitial(Cache::new);

    private static int voxy$unresolvedPaletteReports = 0;

    /**
     * Resolves a snapshotted local palette to voxy block ids.
     * <p>
     * A palette slot that could not be read is air, never {@code -1}. Upstream stored the -1 and
     * {@link Mapper#composeMappingId} then shifted it into the 20-bit block field as 0xFFFFF,
     * which both overflowed into the biome and light fields of that voxel and, on 1.16.5, killed
     * the ingest service outright with an out-of-range lookup.
     */
    private static int setupLocalPalette(BlockState[] states, Reference2IntOpenHashMap<BlockState> blockCache, Mapper mapper, int[] pc) {
        int c = states.length;
        int unresolved = 0;
        for (int i = 0; i < c; i++) {
            var state = states[i];
            int blockId = 0;//air
            if (state != null) {
                blockId = blockCache.getOrDefault(state, -1);
                if (blockId == -1) {
                    blockId = mapper.getIdForBlockState(state);
                    blockCache.put(state, blockId);
                }
                if (blockId < 0) {
                    blockId = 0;//air
                    unresolved++;
                }
            }
            pc[i] = blockId;
        }
        if (unresolved != 0 && voxy$unresolvedPaletteReports++ < 5) {
            me.cortex.voxy.common.Logger.warn("[voxy] " + unresolved + " of " + c
                    + " palette entries could not be resolved to a block id; treating them as air");
        }
        return c;
    }

    public static VoxelizedSection convert(VoxelizedSection section,
                                           Mapper stateMapper,
                                           PalettedContainer<BlockState> blockContainer,
                                           BiomeContainer biomeContainer,
                                           int sectionY,
                                           ILightingSupplier lightSupplier) {
        return convert(section, stateMapper, blockContainer, biomeContainer, sectionY, lightSupplier, false, 0);
    }

    /**
     * Entry point for callers that own their container outright (the region-file importer builds
     * one per task), so reading it in place is safe. Everything fed from a live client chunk goes
     * through {@link SectionSnapshot} instead -- see that class for why.
     */
    public static VoxelizedSection convert(VoxelizedSection section,
                                           Mapper stateMapper,
                                           PalettedContainer<BlockState> blockContainer,
                                           BiomeContainer biomeContainer,
                                           int sectionY,
                                           ILightingSupplier lightSupplier,
                                           boolean shouldZoom,
                                           long zoomSeed) {
        var snapshot = SectionSnapshot.of(blockContainer, false);
        if (snapshot == null) {
            return null;
        }
        return convert(section, stateMapper, snapshot, biomeContainer, sectionY, lightSupplier, shouldZoom, zoomSeed);
    }

    public static VoxelizedSection convert(VoxelizedSection section,
                                           Mapper stateMapper,
                                           SectionSnapshot snapshot,
                                           BiomeContainer biomeContainer,
                                           int sectionY,
                                           ILightingSupplier lightSupplier) {
        return convert(section, stateMapper, snapshot, biomeContainer, sectionY, lightSupplier, false, 0);
    }

    public static VoxelizedSection convert(VoxelizedSection section,
                                           Mapper stateMapper,
                                           SectionSnapshot snapshot,
                                           BiomeContainer biomeContainer,
                                           int sectionY,
                                           ILightingSupplier lightSupplier,
                                           boolean shouldZoom,
                                           long zoomSeed) {
        //Cheat by creating a local pallet then read the data directly
        var cache = THREAD_LOCAL.get();
        var blockCache = cache.getLocalMapping(stateMapper);

        var biomes = cache.biomeCache;
        var data = section.section;
        var zoomCells = cache.zoomCellCache;

        // 1.21.1 reached the palette and storage through the PalettedContainer.Data record, so a
        // worker always saw a matching pair. 1.16.5 holds them as two mutable fields, so they are
        // captured together on the owning thread instead -- see SectionSnapshot.
        int pcc = 0;
        int[] pc;
        boolean identity = snapshot.identity;
        if (identity) {
            // The identity palette maps straight onto block-state registry ids, so there is no
            // local palette to build and pcc is unused on that path.
            pc = cache.getPaletteCache(1);
        } else {
            pc = cache.getPaletteCache(snapshot.palette.length);
            pcc = setupLocalPalette(snapshot.palette, blockCache, stateMapper, pc);
            pcc = Math.max(0, pcc - 1);
        }

        {
            int i = 0;
            int inital = -1;
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        // 1.16.5 stores biomes per *chunk column*, not per section, and indexes
                        // them in quart (4-block) coordinates over the whole height -- hence the
                        // section offset. 1.21.1's container was section-local and needed none.
                        int bid = stateMapper.getIdForBiome(
                                biomeContainer.getNoiseBiome(x, (sectionY << 2) + y, z));
                        biomes[i++] = bid;
                        if (inital==-1) inital = bid;
                        shouldZoom &= inital == bid;//Evil hacky trick, we only need to zoom if on a biome boarder
                    }
                }
            }

            if (shouldZoom) {
                computeZoomCells(biomes, zoomSeed, zoomCells);
            }
        }


        int nonZeroCnt = 0;
        {
            var bDat = snapshot.raw;
            int iterPerLong = (64 / snapshot.bits) - 1;

            int MSK = (1 << snapshot.bits) - 1;
            int eBits = snapshot.bits;

            long sample = 0;
            int c = 0;
            int dec = 0;
            for (int i = 0; i <= 0xFFF; i++) {
                if (dec-- == 0) {
                    sample = bDat[c++];
                    dec = iterPerLong;
                }
                int bId;
                if (!identity) {
                    bId = pc[Math.min((int) (sample & MSK), pcc)];
                } else {
                    var gs = net.minecraft.block.Block.BLOCK_STATE_REGISTRY.byId((int) (sample & MSK));
                    bId = gs == null ? 0 : stateMapper.getIdForBlockState(gs);
                }
                sample >>>= eBits;

                byte light = lightSupplier.supply(i&0xF, (i>>8)&0xF, (i>>4)&0xF);
                nonZeroCnt += (bId != 0)?1:0;
                data[i] = Mapper.composeMappingId(light, bId, biomes[me.cortex.voxy.common.util.Bits.compress(i,0b1100_1100_1100)]);
            }
        }
        section.lvl0NonAirCount = nonZeroCnt;
        return section;
    }


    private static void computeZoomCells(int[] biomes, long zoomSeed, long[] zoomInfo) {
        for (int cy = 0; cy<4; cy++) {
            for (int cz = 0; cz<4; cz++) {
                for (int cx = 0; cx<4; cx++) {

                }
            }
        }
    }

    //Support for other mods etc that use this entry point
    @Deprecated  // forRemoval is Java 9+
    public static void mipSection(VoxelizedSection section, Mapper mapper) {
        WorldVoxilizedSectionMipper.mipSection(section, mapper);
    }
}
