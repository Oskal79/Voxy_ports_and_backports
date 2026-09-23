package me.cortex.voxy.commonImpl.importers;

import com.mojang.serialization.Codec;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.Pair;
import me.cortex.voxy.common.util.UnsafeUtil;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import net.minecraft.util.IObjectIntIterable;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.util.palette.IdentityPalette;
import net.minecraft.util.palette.IPalette;
import net.minecraft.util.palette.PalettedContainer;
import net.minecraft.util.registry.DynamicRegistries;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.biome.BiomeContainer;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.INBT;
import net.minecraftforge.common.util.Constants;
import net.minecraft.nbt.NBTDynamicOps;
import net.minecraft.network.PacketBuffer;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.Biomes;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.storage.RegionFileVersion;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.lwjgl.system.MemoryUtil;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class WorldImporter implements IDataImporter {
    private final WorldEngine world;
    // 1.16.5 has no Codec for PalettedContainer (those arrived in 1.18) and no Holder, so the
    // section is decoded the way vanilla's own ChunkSerializer does it on this version:
    // PalettedContainer#read(ListNBT, long[]) over the section's "Palette" / "BlockStates" tags.
    private final Registry<Biome> biomeRegistry;
    private final IPalette<BlockState> globalBlockPalette;
    private final AtomicInteger estimatedTotalChunks = new AtomicInteger();//Slowly converges to the true value
    private final AtomicInteger totalChunks = new AtomicInteger();
    private final AtomicInteger chunksProcessed = new AtomicInteger();

    private final ConcurrentLinkedDeque<Runnable> jobQueue = new ConcurrentLinkedDeque<>();
    private final Service service;

    private volatile boolean isRunning;

    public WorldImporter(WorldEngine worldEngine, World mcWorld, ServiceManager sm, BooleanSupplier runChecker) {
        this.world = worldEngine;
        this.service = sm.createService(()->new Pair<>(()->this.jobQueue.poll().run(), ()->{}), 3, "World importer", runChecker);

        // World itself exposes no registry access on 1.16.5; the concrete subclasses do.
        DynamicRegistries registries;
        if (mcWorld instanceof ClientWorld) {
            registries = ((ClientWorld) mcWorld).registryAccess();
        } else if (mcWorld instanceof ServerWorld) {
            registries = ((ServerWorld) mcWorld).registryAccess();
        } else {
            throw new IllegalArgumentException("Cannot resolve a biome registry from " + mcWorld);
        }
        this.biomeRegistry = registries.registryOrThrow(Registry.BIOME_REGISTRY);
        this.globalBlockPalette = new IdentityPalette<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState());
    }


    @Override
    public void runImport(IUpdateCallback updateCallback, ICompletionCallback completionCallback) {
        if (this.isRunning) {
            throw new IllegalStateException();
        }
        if (this.worker == null) {//Can happen if no files
            completionCallback.onCompletion(0);
            return;
        }
        this.isRunning = true;
        this.world.acquireRef();
        this.updateCallback = updateCallback;
        this.completionCallback = completionCallback;
        this.worker.start();
    }

    @Override
    public WorldEngine getEngine() {
        return this.world;
    }

    private final AtomicBoolean isShutdown = new AtomicBoolean();
    public void shutdown() {
        if (this.isShutdown.getAndSet(true)) {
            return;
        }
        this.isRunning = false;
        if (this.worker != null) {
            try {
                this.worker.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        if (this.service.isLive()) {
            this.world.releaseRef();
            this.service.shutdown();
        }
        //Free all the remaining entries by running the lambda
        while (!this.jobQueue.isEmpty()) {
            this.jobQueue.poll().run();
        }
    }

    private interface IImporterMethod <T> {
        void importRegion(T file) throws Exception;
    }

    private volatile Thread worker;
    private IUpdateCallback updateCallback;
    private ICompletionCallback completionCallback;
    public void importRegionDirectoryAsync(File directory) {
        var files = directory.listFiles((dir, name) -> {
            var sections = name.split("\\.");
            if (sections.length != 4 || (!sections[0].equals("r")) || (!sections[3].equals("mca"))) {
                Logger.error("Unknown file: " + name);
                return false;
            }
            return true;
        });
        // Finding nothing used to report "import finished, 0 chunks" as if it had succeeded. Say
        // what was actually looked at, so a wrong path is obvious instead of looking like an
        // empty world.
        String[] voxyAll = directory.list();
        Logger.info("[voxy-diag] import scanning: " + directory.getAbsolutePath()
                + " exists=" + directory.exists()
                + " isDir=" + directory.isDirectory()
                + " totalEntries=" + (voxyAll == null ? -1 : voxyAll.length)
                + " regionFiles=" + (files == null ? -1 : files.length));
        if (voxyAll != null && voxyAll.length > 0) {
            Logger.info("[voxy-diag] import first entries: " + String.join(", ",
                    java.util.Arrays.copyOfRange(voxyAll, 0, Math.min(voxyAll.length, 8))));
        }
        if (files == null || files.length == 0) {
            Logger.error("[voxy-diag] import found no .mca region files in "
                    + directory.getAbsolutePath() + " -- nothing to import.");
        }
        if (files == null) {
            return;
        }
        Arrays.sort(files, File::compareTo);
        this.importRegionsAsync(files, this::importRegionFile);
    }

    public void importZippedRegionDirectoryAsync(File zip, String innerDirectory) {
        try {
            innerDirectory = innerDirectory.replace("\\\\", "\\").replace("\\", "/");
            // ZipFile.builder() is a newer commons-compress API than 1.16.5 bundles.
            var file = new ZipFile(zip);
            ArrayList<ZipArchiveEntry> regions = new ArrayList<>();
            for (var e = file.getEntries(); e.hasMoreElements();) {
                var entry = e.nextElement();
                if (entry.isDirectory()||!entry.getName().startsWith(innerDirectory)) {
                    continue;
                }
                var parts = entry.getName().split("/");
                var name = parts[parts.length-1];
                var sections = name.split("\\.");
                if (sections.length != 4 || (!sections[0].equals("r")) || (!sections[3].equals("mca"))) {
                    Logger.error("Unknown file: " + name);
                    continue;
                }
                regions.add(entry);
            }
            this.importRegionsAsync(regions.toArray(new ZipArchiveEntry[0]), (entry)->{
                if (entry.getSize() == 0) {
                    return;
                }
                var buf = new MemoryBuffer(entry.getSize());
                try (var channel = Channels.newChannel(file.getInputStream(entry))) {
                    if (channel.read(buf.asByteBuffer()) != buf.size) {
                        buf.free();
                        throw new IllegalStateException("Could not read full zip entry");
                    }
                }

                var parts = entry.getName().split("/");
                var name = parts[parts.length-1];
                var sections = name.split("\\.");

                try {
                    this.importRegion(buf, Integer.parseInt(sections[1]), Integer.parseInt(sections[2]));
                } catch (NumberFormatException e) {
                    Logger.error("Invalid format for region position, x: \""+sections[1]+"\" z: \"" + sections[2] + "\" skipping region");
                }
                buf.free();
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    private <T> void importRegionsAsync(T[] regionFiles, IImporterMethod<T> importer) {
        this.totalChunks.set(0);
        this.estimatedTotalChunks.set(0);
        this.chunksProcessed.set(0);
        this.worker = new Thread(() -> {
            this.estimatedTotalChunks.addAndGet(regionFiles.length*1024);
            for (var file : regionFiles) {
                this.estimatedTotalChunks.addAndGet(-1024);
                try {
                    importer.importRegion(file);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                while ((this.totalChunks.get()-this.chunksProcessed.get() > 10_000) && this.isRunning) {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                if (!this.isRunning) {
                    this.service.blockTillEmpty();
                    this.completionCallback.onCompletion(this.totalChunks.get());
                    this.worker = null;
                    return;
                }
            }
            this.service.blockTillEmpty();
            while (this.chunksProcessed.get() != this.totalChunks.get() && this.isRunning) {
                Thread.yield();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            if (!this.isShutdown.getAndSet(true)) {
                this.worker = null;
                this.service.shutdown();
                this.world.releaseRef();
            }
            this.completionCallback.onCompletion(this.totalChunks.get());
        });
        this.worker.setName("World importer");
    }

    public boolean isBusy() {
        return this.isRunning || this.worker != null;
    }

    public boolean isRunning() {
        return this.isRunning || (this.worker != null && this.worker.isAlive());
    }

    private void importRegionFile(File file) throws IOException {
        var name = file.getName();
        var sections = name.split("\\.");
        if (sections.length != 4 || (!sections[0].equals("r")) || (!sections[3].equals("mca"))) {
            Logger.error("Unknown file: " + name);
            throw new IllegalStateException();
        }
        int rx = 0;
        int rz = 0;
        try {
            rx = Integer.parseInt(sections[1]);
            rz = Integer.parseInt(sections[2]);
        } catch (NumberFormatException e) {
            Logger.error("Invalid format for region position, x: \""+sections[1]+"\" z: \"" + sections[2] + "\" skipping region");
            return;
        }
        try (var fileStream = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            if (fileStream.size() == 0) {
                return;
            }
            var fileData = new MemoryBuffer(fileStream.size());
            if (fileStream.read(fileData.asByteBuffer(), 0) < 8192) {
                fileData.free();
                Logger.warn("Header of region file invalid");
                return;
            }
            this.importRegion(fileData, rx, rz);
            fileData.free();
        }
    }


    private void importRegion(MemoryBuffer regionFile, int x, int z) {
        //Find and load all saved chunks
        if (regionFile.size < 8192) {//File not big enough
            Logger.warn("Header of region file invalid");
            return;
        }
        for (int idx = 0; idx < 1024; idx++) {
            int sectorMeta = Integer.reverseBytes(MemoryUtil.memGetInt(regionFile.address+idx*4));//Assumes little endian
            if (sectorMeta == 0) {
                //Empty chunk
                continue;
            }
            int sectorStart = sectorMeta>>>8;
            int sectorCount = sectorMeta&((1<<8)-1);

            if (sectorCount == 0) {
                continue;
            }

            //TODO: create memory copy for each section
            if (regionFile.size < ((sectorCount-1) + sectorStart) * 4096L) {
                Logger.warn("Cannot access chunk sector as it goes out of bounds. start bytes: " + (sectorStart*4096) + " sector count: " + sectorCount + " fileSize: " + regionFile.size);
                continue;
            }

            {
                long base = regionFile.address + sectorStart * 4096L;
                int chunkLen = sectorCount * 4096;
                int m = Integer.reverseBytes(MemoryUtil.memGetInt(base));
                byte b = MemoryUtil.memGetByte(base + 4L);
                if (m == 0) {
                    Logger.error("Chunk is allocated, but stream is missing");
                } else {
                    int n = m - 1;
                    if (regionFile.size < (n + sectorStart*4096L)) {
                        Logger.warn("Chunk stream to small");
                    } else if ((b & 128) != 0) {
                        if (n != 0) {
                            Logger.error("Chunk has both internal and external streams");
                        }
                        Logger.error("Chunk has external stream which is not supported");
                    } else if (n > chunkLen-5) {
                        Logger.error("Chunk stream is truncated: expected "+n+" but read " + (chunkLen-5));
                    } else if (n < 0) {
                        Logger.error("Declared size of chunk is negative");
                    } else {
                        var data = new MemoryBuffer(n).cpyFrom(base + 5);
                        this.jobQueue.add(()-> {
                            if (!this.isRunning) {
                                data.free();
                                return;
                            }
                            try {
                                try (var decompressedData = this.decompress(b, data)) {
                                    if (decompressedData == null) {
                                        Logger.error("Error decompressing chunk data");
                                    } else {
                                        var nbt = CompressedStreamTools.read(decompressedData);
                                        this.importChunkNBT(nbt, x, z);
                                    }
                                }
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            } finally {
                                data.free();
                            }
                        });
                        this.totalChunks.incrementAndGet();
                        this.estimatedTotalChunks.incrementAndGet();
                        this.service.execute();
                    }
                }
            }
        }
    }

    private static InputStream createInputStream(MemoryBuffer data) {
        return new InputStream() {
            private long offset = 0;
            @Override
            public int read() {
                return MemoryUtil.memGetByte(data.address + (this.offset++)) & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                len = Math.min(len, this.available());
                if (len == 0) {
                    return -1;
                }
                UnsafeUtil.memcpy(data.address+this.offset, len, b, off); this.offset+=len;
                return len;
            }

            @Override
            public int available() {
                return (int) (data.size-this.offset);
            }
        };
    }

    private DataInputStream decompress(byte flags, MemoryBuffer stream) throws IOException {
        RegionFileVersion chunkStreamVersion = RegionFileVersion.fromId(flags);
        if (chunkStreamVersion == null) {
            Logger.error("Chunk has invalid chunk stream version");
            return null;
        } else {
            return new DataInputStream(chunkStreamVersion.wrap(createInputStream(stream)));
        }
    }

    private void importChunkNBT(CompoundNBT chunk, int regionX, int regionZ) {
        if (!chunk.contains("Status")) {
            //Its not real so decrement the chunk
            this.totalChunks.decrementAndGet();
            return;
        }

        //Dont process non full chunk sections
        // Everything lives under a "Level" compound in the 1.16.5 region format.
        CompoundNBT level = chunk.contains("Level") ? chunk.getCompound("Level") : chunk;
        var status = ChunkStatus.byName((level.contains("Status") ? level.getString("Status") : null));
        if (status != ChunkStatus.FULL && status != ChunkStatus.EMPTY) {//We also import empty since they are from data upgrade
            this.totalChunks.decrementAndGet();
            return;
        }

        try {
            int x = (level.contains("xPos") ? level.getInt("xPos") : Integer.MIN_VALUE);
            int z = (level.contains("zPos") ? level.getInt("zPos") : Integer.MIN_VALUE);
            if (x>>5 != regionX || z>>5 != regionZ) {
                Logger.error("Chunk position is not located in correct region, expected: (" + regionX + ", " + regionZ+"), got: " + "(" + (x>>5) + ", " + (z>>5)+"), importing anyway");
            }

            // Biomes are per chunk column here, stored as a flat int[] of registry ids, not as a
            // per-section container. Chunks written before biomes were persisted have none.
            int[] biomeIds = level.getIntArray("Biomes");
            BiomeContainer biomes = biomeIds.length == 0
                    ? new BiomeContainer(this.biomeRegistry, new int[BiomeContainer.BIOMES_SIZE])
                    : new BiomeContainer(this.biomeRegistry, biomeIds);

            for (var sectionE : level.getList("Sections", Constants.NBT.TAG_COMPOUND)) {
                var section = (CompoundNBT) sectionE;
                int y = (section.contains("Y") ? section.getInt("Y") : Integer.MIN_VALUE);
                this.importSectionNBT(x, y, z, section, biomes);
            }
        } catch (Exception e) {
            Logger.error("Exception importing world chunk:",e);
        }

        this.updateCallback.onUpdate(this.chunksProcessed.incrementAndGet(), this.estimatedTotalChunks.get());
    }

    private static final byte[] EMPTY = new byte[0];
    private static final ThreadLocal<VoxelizedSection> SECTION_CACHE = ThreadLocal.withInitial(VoxelizedSection::createEmpty);
    private void importSectionNBT(int x, int y, int z, CompoundNBT section, BiomeContainer biomes) {
        // 1.16.5 stores the palette and packed states as two sibling tags on the section rather
        // than 1.21.1's nested "block_states" compound.
        if (!section.contains("Palette") || !section.contains("BlockStates")) {
            return;
        }

        byte[] blockLightData = section.getByteArray("BlockLight");
        byte[] skyLightData = section.getByteArray("SkyLight");

        NibbleArray blockLight;
        if (blockLightData.length != 0) {
            blockLight = new NibbleArray(blockLightData);
        } else {
            blockLight = null;
        }

        NibbleArray skyLight;
        if (skyLightData.length != 0) {
            skyLight = new NibbleArray(skyLightData);
        } else {
            skyLight = null;
        }

        PalettedContainer<BlockState> blockStates = new PalettedContainer<>(
                this.globalBlockPalette,
                Block.BLOCK_STATE_REGISTRY,
                NBTUtil::readBlockState,
                NBTUtil::writeBlockState,
                Blocks.AIR.defaultBlockState());
        blockStates.read(section.getList("Palette", Constants.NBT.TAG_COMPOUND), section.getLongArray("BlockStates"));

        VoxelizedSection csec = WorldConversionFactory.convert(
                SECTION_CACHE.get().setPosition(x, y, z),
                this.world.getMapper(),
                blockStates,
                biomes,
                y,
                (bx, by, bz) -> {
                    int block = 0;
                    int sky = 0;
                    if (blockLight != null) {
                        block = blockLight.get(bx, by, bz);
                    }
                    if (skyLight != null) {
                        sky = skyLight.get(bx, by, bz);
                    }
                    return (byte) (sky|(block<<4));
                }
        );

        WorldVoxilizedSectionMipper.mipSection(csec, this.world.getMapper());
        WorldUpdater.insertUpdate(this.world, csec);
    }
}
