package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.voxelization.ILightingSupplier;
import me.cortex.voxy.common.voxelization.SectionSnapshot;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.util.math.SectionPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.biome.BiomeContainer;
import net.minecraft.world.chunk.ChunkSection;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentLinkedDeque;

public class VoxelIngestService {
    private static final ThreadLocal<VoxelizedSection> SECTION_CACHE = ThreadLocal.withInitial(VoxelizedSection::createEmpty);
    private final Service service;
    private static final class IngestSection {
        private final int cx;
        private final int cy;
        private final int cz;
        private final WorldEngine world;
        private final SectionSnapshot section;
        // 1.16.5 keeps biomes on the chunk, not the section, so carry it alongside.
        private final BiomeContainer biomes;
        private final NibbleArray blockLight;
        private final NibbleArray skyLight;
        /** Sky level to assume when {@link #skyLight} carries nothing; see enqueueIngest. */
        private final int skyFallback;

        public IngestSection(int cx, int cy, int cz, WorldEngine world, SectionSnapshot section, BiomeContainer biomes, NibbleArray blockLight, NibbleArray skyLight) {
            this(cx, cy, cz, world, section, biomes, blockLight, skyLight, 0);
        }

        public IngestSection(int cx, int cy, int cz, WorldEngine world, SectionSnapshot section, BiomeContainer biomes, NibbleArray blockLight, NibbleArray skyLight, int skyFallback) {
            this.skyFallback = skyFallback;
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.world = world;
            this.section = section;
            this.biomes = biomes;
            this.blockLight = blockLight;
            this.skyLight = skyLight;
        }

        public int cx() { return this.cx; }
        public int cy() { return this.cy; }
        public int cz() { return this.cz; }
        public WorldEngine world() { return this.world; }
        public SectionSnapshot section() { return this.section; }
        public BiomeContainer biomes() { return this.biomes; }
        public NibbleArray blockLight() { return this.blockLight; }
        public NibbleArray skyLight() { return this.skyLight; }

        @Override public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof IngestSection)) return false;
            IngestSection o = (IngestSection) obj;
            return this.cx == o.cx && this.cy == o.cy && this.cz == o.cz && java.util.Objects.equals(this.world, o.world) && java.util.Objects.equals(this.section, o.section) && java.util.Objects.equals(this.biomes, o.biomes) && java.util.Objects.equals(this.blockLight, o.blockLight) && java.util.Objects.equals(this.skyLight, o.skyLight);
        }

        @Override public int hashCode() { return java.util.Objects.hash(this.cx, this.cy, this.cz, this.world, this.section, this.biomes, this.blockLight, this.skyLight); }

        @Override public String toString() { return "IngestSection[cx=" + this.cx + ", cy=" + this.cy + ", cz=" + this.cz + ", world=" + this.world + ", section=" + this.section + ", biomes=" + this.biomes + ", blockLight=" + this.blockLight + ", skyLight=" + this.skyLight + "]"; }

    }
    private final ConcurrentLinkedDeque<IngestSection> ingestQueue = new ConcurrentLinkedDeque<>();

    public VoxelIngestService(ServiceManager pool) {
        this.service = pool.createServiceNoCleanup(()->this::processJob, 5000, "Ingest service");
    }

    private void processJob() {
        var task = this.ingestQueue.pop();
        try {
            var section = task.section;
            var vs = SECTION_CACHE.get().setPosition(task.cx, task.cy, task.cz);

            if (section.empty && task.blockLight == null && task.skyLight == null && task.skyFallback == 0) {//If the chunk section has lighting data, propagate it
                WorldUpdater.insertUpdate(task.world, vs.zero());
            } else {
                VoxelizedSection csec = WorldConversionFactory.convert(
                        vs,
                        task.world.getMapper(),
                        section,
                        task.biomes,
                        task.cy,
                        getLightingSupplier(task)
                );
                voxy$censusSection(csec);
                WorldVoxilizedSectionMipper.mipSection(csec, task.world.getMapper());
                WorldUpdater.insertUpdate(task.world, csec);
            }
        } finally {
            //Release the ref we had acquired for the world
            task.world.releaseRef();
        }
    }

    private static int VOXY_CHUNK_CENSUS = 0;
    private static final java.util.concurrent.atomic.AtomicInteger VOXY_CENSUS = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Reports what actually went into the first few hundred stored sections: how many voxels are
     * non-air and what sky/block light they carry. Sections rendered black must show up here as
     * either light 0 or a block id that resolves to nothing.
     */
    private static void voxy$censusSection(VoxelizedSection csec) {
        if (VOXY_CENSUS.get() >= 200) return;
        int nonAir = 0, zeroLight = 0, skyMin = 15, skyMax = 0, blkMin = 15, blkMax = 0, maxBlockId = 0;
        var data = csec.section;
        for (int i = 0; i <= 0xFFF; i++) {
            long v = data[i];
            int blockId = (int) ((v >>> 27) & 0xFFFFF);
            if (blockId == 0) continue;
            nonAir++;
            if (blockId > maxBlockId) maxBlockId = blockId;
            int light = (int) ((v >>> 56) & 0xFF);
            if (light == 0) zeroLight++;
            int sky = light & 0xF;
            int blk = (light >>> 4) & 0xF;
            if (sky < skyMin) skyMin = sky;
            if (sky > skyMax) skyMax = sky;
            if (blk < blkMin) blkMin = blk;
            if (blk > blkMax) blkMax = blk;
        }
        if (VOXY_CENSUS.getAndIncrement() >= 200) return;
        if (nonAir == 0) {
            //An all-air section: what matters is whether its sky light reached the store, because
            //that is what lights the top faces of the ground underneath it.
            int aSkyMin = 15, aSkyMax = 0;
            for (int i = 0; i <= 0xFFF; i++) {
                int sky = (int) ((data[i] >>> 56) & 0xF);
                if (sky < aSkyMin) aSkyMin = sky;
                if (sky > aSkyMax) aSkyMax = sky;
            }
            Logger.info(String.format("[voxy-census] sec %d,%d,%d AIR sky[%d..%d]",
                    csec.x, csec.y, csec.z, aSkyMin, aSkyMax));
            return;
        }
        Logger.info(String.format("[voxy-census] sec %d,%d,%d nonAir=%d sky[%d..%d] block[%d..%d] darkVoxels=%d maxBlockId=%d",
                csec.x, csec.y, csec.z, nonAir, skyMin, skyMax, blkMin, blkMax, zeroLight, maxBlockId));
    }

    private static int VOXY_DIAG_LIGHT = 0;

    @NotNull
    private static ILightingSupplier getLightingSupplier(IngestSection task) {
        if (VOXY_DIAG_LIGHT++ < 10) {
            me.cortex.voxy.common.Logger.info(String.format(
                    "[voxy-diag] light for section y=%d: skyLight=%s blockLight=%s",
                    task.cy,
                    task.skyLight == null ? "NULL"
                            : (task.skyLight.isEmpty() ? "empty" : "present"),
                    task.blockLight == null ? "NULL"
                            : (task.blockLight.isEmpty() ? "empty" : "present")));
        }
        final int skyFallback = task.skyFallback;
        final var sla = task.skyLight;
        final var bla = task.blockLight;

        ILightingSupplier supplier = (x, y, z) -> {
            int sky = (sla == null || sla.isEmpty()) ? skyFallback : Math.min(15, sla.get(x, y, z));
            int block = (bla == null || bla.isEmpty()) ? 0 : Math.min(15, bla.get(x, y, z));
            return (byte) (sky | (block << 4));
        };
        return supplier;
    }

    private static boolean shouldIngestSection(ChunkSection section, int cx, int cy, int cz) {
        return true;
    }

    public boolean enqueueIngest(WorldEngine engine, Chunk chunk) {
        if (!this.service.isLive()) {
            return false;
        }
        if (!engine.isLive()) {
            throw new IllegalStateException("Tried inserting chunk into WorldEngine that was not alive");
        }

        engine.markActive();

        var lightingProvider = chunk.getLevel().getLightEngine();
        boolean gotLighting = false;

        var sections = chunk.getSections();
        var blpLayer = lightingProvider.getLayerListener(LightType.SKY);
        final boolean hasSkyLight = chunk.getLevel().dimensionType().hasSkyLight();

        //Highest section that has a stored sky layer. 1.16.5 sends no NibbleArray at all for
        //sections above the terrain -- vanilla reads those as fully lit (SkyLightStorage returns 15
        //above its top section), so anything above this index is open sky, not darkness.
        int topSkySection = -1;
        int topNonNullSection = -1;
        for (int j = 0; j < sections.length; j++) {
            var data = blpLayer.getDataLayerData(SectionPos.of(chunk.getPos(), j));
            if (data != null && !data.isEmpty()) {
                topSkySection = j;
            }
            if (sections[j] != null && !sections[j].isEmpty()) {
                topNonNullSection = j;
            }
        }

        if (VOXY_CHUNK_CENSUS++ < 20) {
            StringBuilder nulls = new StringBuilder();
            for (int j = 0; j < sections.length; j++) {
                if (sections[j] == null) nulls.append(j).append(' ');
            }
            Logger.info("[voxy-census] chunk " + chunk.getPos().x + "," + chunk.getPos().z
                    + " topSkySection=" + topSkySection + " topNonNull=" + topNonNullSection
                    + " airSectionsIngested=[" + nulls.toString().trim() + "]");
        }

        int i = -1;
        boolean allEmpty = true;
        for (var section : sections) {
            i++;
            if (section == null) continue;
            if (!shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
            allEmpty&=section.isEmpty();
            //if (section.isEmpty()) continue;
            var pos = SectionPos.of(chunk.getPos(), i);
            var slData = lightingProvider.getLayerListener(LightType.SKY).getDataLayerData(pos);
            var blData = lightingProvider.getLayerListener(LightType.BLOCK).getDataLayerData(pos);
            if ((slData == null || slData.isEmpty()) && (blData == null || blData.isEmpty()))
                continue;
            gotLighting = true;
        }

        if (allEmpty&&!gotLighting) {
            //Special case all empty chunk columns, we need to clear it out
            i = -1;
            for (var section : chunk.getSections()) {
                i++;
                if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
                var snapshot = SectionSnapshot.of(section);
                if (snapshot == null) continue;
                engine.acquireRef();
                this.ingestQueue.add(new IngestSection(chunk.getPos().x, i, chunk.getPos().z, engine, snapshot, chunk.getBiomes(), null, null));
                try {
                    this.service.execute();
                } catch (Exception e) {
                    Logger.error("Executing had an error: assume shutting down, aborting",e);
                    engine.releaseRef();//we must manually release
                    break;
                }
            }
        }

        if (!gotLighting) {
            return false;
        }

        //In a sky-lit dimension, no visible sky layer at all or not reaching top non-null section means
        //the light packet has not been published yet. Storing now would bake in darkness;
        //deferred re-ingest will come back once the real data is readable.
        if (hasSkyLight && (topSkySection == -1 || topSkySection < topNonNullSection)) {
            return false;
        }

        var blp = lightingProvider.getLayerListener(LightType.BLOCK);
        var slp = lightingProvider.getLayerListener(LightType.SKY);


        i = -1;
        for (var section : sections) {
            i++;
            //All sections (including air above terrain) are ingested so that adjacent cliffs
            //and mountains can read full daylight sky light across chunk boundaries.
            if (section != null && !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) {
                continue;
            }
            //if (section.isEmpty()) continue;
            var pos = SectionPos.of(chunk.getPos(), i);

            var bl = blp.getDataLayerData(pos);
            if (bl != null) {
                bl = bl.copy();
            }

            var sl = slp.getDataLayerData(pos);
            if (sl != null) {
                sl = sl.copy();
            }

            //If its null for either, assume failure to obtain lighting and ignore section
            //if (blNone && slNone) {
            //    continue;
            //}
            //Copy the section's palette and packed block indices here, on the thread that owns the
            //chunk. Handing the live ChunkSection to a worker let a concurrent palette resize tear
            //the two apart -- see SectionSnapshot.
            var snapshot = section == null ? SectionSnapshot.air() : SectionSnapshot.of(section);
            if (snapshot == null) continue;
            engine.acquireRef();//This is not great but dont really have a better solution as all the others have there own problem
            int fallback = (hasSkyLight && (topNonNullSection == -1 || i >= topNonNullSection)) ? 15 : 0;
            this.ingestQueue.add(new IngestSection(chunk.getPos().x, i, chunk.getPos().z, engine, snapshot, chunk.getBiomes(), bl, sl, fallback));
            try {
                this.service.execute();
            } catch (Exception e) {
                Logger.error("Executing had an error: assume shutting down, aborting",e);
                engine.releaseRef();//we must manually release
                break;
            }
        }
        return true;
    }

    public int getTaskCount() {
        return this.service.numJobs();
    }

    public void shutdown() {
        this.service.shutdown();
        while (!this.ingestQueue.isEmpty()) {
            //We need to manually release all our world locks
            this.ingestQueue.pop().world.releaseRef();
        }

    }

    //Utility method to ingest a chunk into the given WorldIdentifier or world
    public static boolean tryIngestChunk(WorldIdentifier worldId, Chunk chunk) {
        if (worldId == null) return false;
        var instance = VoxyCommon.getInstance();
        if (instance == null) return false;
        if (!instance.isIngestEnabled(worldId)) return false;
        var engine = instance.getOrCreate(worldId);
        if (engine == null) return false;
        return instance.getIngestService().enqueueIngest(engine, chunk);
    }

    //Try to automatically ingest the chunk into the correct world
    public static boolean tryAutoIngestChunk(Chunk chunk) {
        return tryIngestChunk(WorldIdentifier.of(chunk.getLevel()), chunk);
    }

    private boolean rawIngest0(WorldEngine engine, SectionSnapshot section, BiomeContainer biomes, int x, int y, int z, NibbleArray bl, NibbleArray sl, int skyFallback) {
        engine.acquireRef();
        this.ingestQueue.add(new IngestSection(x, y, z, engine, section, biomes, bl, sl, skyFallback));
        try {
            this.service.execute();
            return true;
        } catch (Exception e) {
            Logger.error("Executing had an error: assume shutting down, aborting",e);
            engine.releaseRef();//we must manually release
            return false;
        }
    }

    public static boolean rawIngest(WorldIdentifier id, ChunkSection section, BiomeContainer biomes, int x, int y, int z, NibbleArray bl, NibbleArray sl) {
        if (id == null) return false;
        var engine = id.getOrCreateEngine();
        if (engine == null) return false;
        return rawIngest(engine, section, biomes, x, y, z, bl, sl);
    }

    public static boolean rawIngest(WorldEngine engine, ChunkSection section, BiomeContainer biomes, int x, int y, int z, NibbleArray bl, NibbleArray sl) {
        if (section == null) return false;
        if (!shouldIngestSection(section, x, y, z)) return false;
        if (engine.instanceIn == null) return false;
        if (!engine.instanceIn.isIngestEnabled(null)) return false;//TODO: dont pass in null
        //Snapshot on the caller's thread; the caller owns the chunk, a worker does not.
        var snapshot = SectionSnapshot.of(section);
        if (snapshot == null) return false;
        int skyFallback = (sl == null || sl.isEmpty()) && y >= 4 ? 15 : 0;
        return engine.instanceIn.getIngestService().rawIngest0(engine, snapshot, biomes, x, y, z, bl, sl, skyFallback);
    }
}
