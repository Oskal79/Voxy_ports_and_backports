package me.cortex.voxy.client.core.rendering.util;


import me.cortex.voxy.client.core.gl.GlFence;
import me.cortex.voxy.client.core.gl.GlPersistentMappedBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.AllocationArena;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;

import static org.lwjgl.opengl.ARBMapBufferRange.GL_MAP_READ_BIT;
import static org.lwjgl.opengl.GL11.glFinish;
import static org.lwjgl.opengl.GL44.GL_MAP_COHERENT_BIT;

//Special download stream which allows access to the download buffer directly
public class RawDownloadStream {
    //NOTE: after the callback returns the pointer is no longer valid for client use
    public interface IDownloadCompletedCallback{void accept(long ptr);}
    private static final class DownloadFragment {
        private final int allocation;
        private final IDownloadCompletedCallback callback;

        public DownloadFragment(int allocation, IDownloadCompletedCallback callback) {
            this.allocation = allocation;
            this.callback = callback;
        }

        public int allocation() { return this.allocation; }
        public IDownloadCompletedCallback callback() { return this.callback; }

        @Override public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof DownloadFragment)) return false;
            DownloadFragment o = (DownloadFragment) obj;
            return this.allocation == o.allocation && java.util.Objects.equals(this.callback, o.callback);
        }

        @Override public int hashCode() { return java.util.Objects.hash(this.allocation, this.callback); }

        @Override public String toString() { return "DownloadFragment[allocation=" + this.allocation + ", callback=" + this.callback + "]"; }

    }
    private static final class DownloadFrame {
        private final GlFence fence;
        private final DownloadFragment[] fragments;

        public DownloadFrame(GlFence fence, DownloadFragment[] fragments) {
            this.fence = fence;
            this.fragments = fragments;
        }

        public GlFence fence() { return this.fence; }
        public DownloadFragment[] fragments() { return this.fragments; }

        @Override public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof DownloadFrame)) return false;
            DownloadFrame o = (DownloadFrame) obj;
            return java.util.Objects.equals(this.fence, o.fence) && java.util.Objects.equals(this.fragments, o.fragments);
        }

        @Override public int hashCode() { return java.util.Objects.hash(this.fence, this.fragments); }

        @Override public String toString() { return "DownloadFrame[fence=" + this.fence + ", fragments=" + this.fragments + "]"; }

    }

    private final GlPersistentMappedBuffer downloadBuffer;
    private final AllocationArena allocationArena = new AllocationArena();
    private final ArrayList<DownloadFragment> frameFragments = new ArrayList<>();
    private final Deque<DownloadFrame> frames = new ArrayDeque<>();

    public RawDownloadStream(int size) {
        this.downloadBuffer = new GlPersistentMappedBuffer(size, GL_MAP_READ_BIT|GL_MAP_COHERENT_BIT).name("RawDownloadStream");
        this.allocationArena.setLimit(size);
    }

    public int download(int size, IDownloadCompletedCallback callback) {
        int allocation = (int) this.allocationArena.alloc(size);
        if (allocation == AllocationArena.SIZE_LIMIT) {
            Logger.warn("Raw download stream full, preemptively committing, this could cause bad things to happen");
            //Hit the download limit, attempt to free
            glFinish();
            this.tick();
            allocation = (int) this.allocationArena.alloc(size);
            if (allocation == AllocationArena.SIZE_LIMIT) {
                throw new IllegalStateException("Unable free enough memory for raw download stream");
            }
        }
        this.frameFragments.add(new DownloadFragment(allocation, callback));
        return allocation;
    }

    //Creates a new "frame" for previously allocated downloads and enqueues a fence
    // also invalidates all previous download pointers from this instance
    public void submit() {
        if (!this.frameFragments.isEmpty()) {
            var fragments = this.frameFragments.toArray(new DownloadFragment[0]);
            this.frameFragments.clear();
            this.frames.add(new DownloadFrame(new GlFence(), fragments));
        }
    }

    public void tick() {
        this.submit();

        while (!this.frames.isEmpty()) {
            //If the first element is not signaled, none of the others will be signaled so break
            if (!this.frames.peek().fence.signaled()) {
                break;
            }
            var frame = this.frames.poll();
            for (var fragment : frame.fragments) {
                long addr = this.downloadBuffer.addr() + fragment.allocation;
                fragment.callback.accept(addr);
                this.allocationArena.free(fragment.allocation);
            }
            frame.fence.free();
        }
    }

    public int getBufferId() {
        return this.downloadBuffer.id;
    }

    public void free() {
        glFinish();
        this.tick();
        GlFence fence = new GlFence();
        while (!fence.signaled()) {
            glFinish();
        }
        fence.free();
        this.tick();
        if (this.frames.size() != 0) {
            throw new IllegalStateException();
        }
        this.frames.forEach(a->a.fence.free());
        this.downloadBuffer.free();
    }
}
