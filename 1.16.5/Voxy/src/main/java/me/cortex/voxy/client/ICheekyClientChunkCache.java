package me.cortex.voxy.client;

import net.minecraft.world.chunk.Chunk;
import org.jetbrains.annotations.Nullable;

public interface ICheekyClientChunkCache {
    @Nullable
    Chunk voxy$cheekyGetChunk(int x, int z);
}
