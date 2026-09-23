package me.cortex.voxy.client.core.rendering.util;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlFence;
import me.cortex.voxy.client.core.gl.GlPersistentMappedBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.AllocationArena;
import me.cortex.voxy.common.util.MemoryBuffer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.function.Consumer;

import static me.cortex.voxy.common.util.AllocationArena.SIZE_LIMIT;
import static org.lwjgl.opengl.GL11.glFinish;
import static org.lwjgl.opengl.GL30C.GL_MAP_READ_BIT;
import static org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL44.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT;
import static org.lwjgl.opengl.GL45.glCopyNamedBufferSubData;

public class DownloadStream {
    public interface DownloadResultConsumer {
        void consume(long ptr, long size);
    }

    private final AllocationArena allocationArena = new AllocationArena();
    private final GlPersistentMappedBuffer downloadBuffer;

    private final Deque<DownloadFrame> frames = new ArrayDeque<>();
    private final LongArrayList thisFrameAllocations = new LongArrayList();
    private final Deque<DownloadData> downloadList = new ArrayDeque<>();
    private final ArrayList<DownloadData> thisFrameDownloadList = new ArrayList<>();

    public DownloadStream(long size) {
        this.downloadBuffer = new GlPersistentMappedBuffer(size, GL_MAP_READ_BIT);//|GL_MAP_COHERENT_BIT
        this.allocationArena.setLimit(size);
    }

    private long caddr = -1;
    private long offset = 0;

    //Pulls the entire buffer from the gpu
    public void download(GlBuffer buffer, DownloadResultConsumer resultConsumer) {
        this.download(buffer, 0, buffer.size(), resultConsumer);
    }

    public void download(GlBuffer buffer, Consumer<MemoryBuffer> resultConsumer) {
        this.download(buffer, 0, buffer.size(), resultConsumer);
    }

    public void download(GlBuffer buffer, long downloadOffset, long size, Consumer<MemoryBuffer> consumer) {
        this.download(buffer, downloadOffset, size, (ptr,size2)-> {
            consumer.accept(MemoryBuffer.createUntrackedUnfreeableRawFrom(ptr, size));
        });
    }

    public void download(GlBuffer buffer, long downloadOffset, long size, DownloadResultConsumer resultConsumer) {
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException();
        }
        if (size <= 0) {
            throw new IllegalArgumentException();
        }
        if (downloadOffset+size > buffer.size()) {
            throw new IllegalArgumentException();
        }

        long addr;
        if (this.caddr == -1 || !this.allocationArena.expand(this.caddr, (int) size)) {
            this.caddr = this.allocationArena.alloc((int) size);//TODO: replace with allocFromLargest
            if (this.caddr == SIZE_LIMIT) {
                Logger.warn("Download stream full, preemptively committing, this could cause bad things to happen");
                this.commit();
                int attempts = 10;
                while (--attempts != 0 && this.caddr == SIZE_LIMIT) {
                    glFinish();
                    this.tick();
                    this.caddr = this.allocationArena.alloc((int) size);
                }
                if (this.caddr == SIZE_LIMIT) {
                    throw new IllegalStateException("Could not allocate memory segment big enough for upload even after force flush");
                }
            }
            this.thisFrameAllocations.add(this.caddr);
            this.offset = size;
            addr = this.caddr;
        } else {//Could expand the allocation so just update it
            addr = this.caddr + this.offset;
            this.offset += size;
        }

        if (this.caddr + size > this.downloadBuffer.size()) {
            throw new IllegalStateException();
        }

        this.downloadList.add(new DownloadData(buffer, addr, downloadOffset, size, resultConsumer));

        //TODO: maybe not auto-commit
        this.commit();
    }


    public void commit() {
        if (this.downloadList.isEmpty()) {
            return;
        }
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        //Copies all the data from target buffers into the download stream
        for (var entry : this.downloadList) {
            glCopyNamedBufferSubData(entry.target.id, this.downloadBuffer.id, entry.targetOffset, entry.downloadStreamOffset, entry.size);
        }
        glMemoryBarrier(GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
        this.thisFrameDownloadList.addAll(this.downloadList);
        this.downloadList.clear();

        this.caddr = -1;
        this.offset = 0;
    }

    public void tick() {
        this.commit();
        if (!this.thisFrameAllocations.isEmpty()) {
            this.frames.add(new DownloadFrame(new GlFence(), new LongArrayList(this.thisFrameAllocations), new ArrayList<>(this.thisFrameDownloadList)));
            this.thisFrameAllocations.clear();
            this.thisFrameDownloadList.clear();
        }

        while (!this.frames.isEmpty()) {
            //Since the ordering of frames is the ordering of the gl commands if we encounter an unsignaled fence
            // all the other fences should also be unsignaled
            if (!this.frames.peek().fence.signaled()) {
                break;
            }

            //Release all the allocations from the frame
            var frame = this.frames.pop();

            //Apply all the callbacks
            for (var data : frame.data) {
                data.resultConsumer.consume(this.downloadBuffer.addr() + data.downloadStreamOffset, data.size);
            }

            frame.allocations.forEach((java.util.function.LongConsumer) this.allocationArena::free);
            frame.fence.free();
        }
    }

    //Synchonize force flushes everything
    public void waitDiscard() {
        glFinish();
        var fence = new GlFence();
        glFinish();
        while (!fence.signaled())
            Thread.yield();  // Thread.onSpinWait() is Java 9+
        fence.free();
        while (!this.frames.isEmpty()) {
            var frame = this.frames.pop();
            while (!frame.fence.signaled()) Thread.yield();  // Thread.onSpinWait() is Java 9+
            frame.allocations.forEach((java.util.function.LongConsumer) this.allocationArena::free);
            frame.fence.free();
        }
    }

    public void flushWaitClear() {
        glFinish();
        this.tick();
        var fence = new GlFence();
        glFinish();
        while (!fence.signaled()) {
            glFinish();
            Thread.yield();  // Thread.onSpinWait() is Java 9+
        }
        fence.free();
        this.tick();
        if (!this.frames.isEmpty()) {
            throw new IllegalStateException();
        }
    }

    private static final class DownloadFrame {
        private final GlFence fence;
        private final LongArrayList allocations;
        private final ArrayList<DownloadData> data;

        public DownloadFrame(GlFence fence, LongArrayList allocations, ArrayList<DownloadData> data) {
            this.fence = fence;
            this.allocations = allocations;
            this.data = data;
        }

        public GlFence fence() { return this.fence; }
        public LongArrayList allocations() { return this.allocations; }
        public ArrayList<DownloadData> data() { return this.data; }

        @Override public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof DownloadFrame)) return false;
            DownloadFrame o = (DownloadFrame) obj;
            return java.util.Objects.equals(this.fence, o.fence) && java.util.Objects.equals(this.allocations, o.allocations) && java.util.Objects.equals(this.data, o.data);
        }

        @Override public int hashCode() { return java.util.Objects.hash(this.fence, this.allocations, this.data); }

        @Override public String toString() { return "DownloadFrame[fence=" + this.fence + ", allocations=" + this.allocations + ", data=" + this.data + "]"; }

    }
    private static final class DownloadData {
        private final GlBuffer target;
        private final long downloadStreamOffset;
        private final long targetOffset;
        private final long size;
        private final DownloadResultConsumer resultConsumer;

        public DownloadData(GlBuffer target, long downloadStreamOffset, long targetOffset, long size, DownloadResultConsumer resultConsumer) {
            this.target = target;
            this.downloadStreamOffset = downloadStreamOffset;
            this.targetOffset = targetOffset;
            this.size = size;
            this.resultConsumer = resultConsumer;
        }

        public GlBuffer target() { return this.target; }
        public long downloadStreamOffset() { return this.downloadStreamOffset; }
        public long targetOffset() { return this.targetOffset; }
        public long size() { return this.size; }
        public DownloadResultConsumer resultConsumer() { return this.resultConsumer; }

        @Override public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof DownloadData)) return false;
            DownloadData o = (DownloadData) obj;
            return java.util.Objects.equals(this.target, o.target) && this.downloadStreamOffset == o.downloadStreamOffset && this.targetOffset == o.targetOffset && this.size == o.size && java.util.Objects.equals(this.resultConsumer, o.resultConsumer);
        }

        @Override public int hashCode() { return java.util.Objects.hash(this.target, this.downloadStreamOffset, this.targetOffset, this.size, this.resultConsumer); }

        @Override public String toString() { return "DownloadData[target=" + this.target + ", downloadStreamOffset=" + this.downloadStreamOffset + ", targetOffset=" + this.targetOffset + ", size=" + this.size + ", resultConsumer=" + this.resultConsumer + "]"; }

    }


    // Global download stream
    public static final DownloadStream INSTANCE = new DownloadStream(1<<25);//32 mb download buffer
}
