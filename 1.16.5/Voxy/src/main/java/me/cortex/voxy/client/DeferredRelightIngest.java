package me.cortex.voxy.client;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.ChunkPos;

/**
 * Re-ingests a chunk a few ticks after its lighting packet arrives.
 * <p>
 * 1.16.5's {@code SectionLightStorage} keeps two maps: {@code updatingSectionData}, which the
 * light packet writes into, and {@code visibleSectionData}, which {@code getDataLayerData} reads.
 * The two are only reconciled inside {@code runUpdates}, on a later tick. Ingesting at the tail of
 * the packet handler therefore reads the state from *before* the packet -- usually a null or empty
 * NibbleArray -- and voxy stored those sections with sky light 0.
 * <p>
 * That is why the unlit areas were patchy and moved around: whether a chunk read stale or fresh
 * light depended on whether an unrelated light update had already forced a swap that tick.
 * <p>
 * 1.21.1 has the same two-map design, but upstream never hits this because Sodium's chunk tracker
 * only reports a chunk once both its block data and its light data have been applied, so voxy's
 * ingest runs from a point where the light is already visible. Embeddium 0.3.18 fires
 * {@code onChunkAdded} straight from {@code replaceWithPacketData} with no light gate at all.
 */
public class DeferredRelightIngest {
    /** Wall-clock delay before re-reading the light; comfortably more than one runUpdates pass. */
    private static final long DELAY_MS = 80;

    private static final it.unimi.dsi.fastutil.longs.Long2LongMap PENDING =
            new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

    public static void queue(int x, int z) {
        synchronized (PENDING) {
            PENDING.put(ChunkPos.asLong(x, z), System.currentTimeMillis() + DELAY_MS);
        }
    }

    public static void reset() {
        synchronized (PENDING) {
            PENDING.clear();
        }
    }

    /** Called once per frame from the render thread, which is also where light updates run. */
    public static void pump() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        var level = mc == null ? null : mc.level;
        if (level == null || !VoxyConfig.CONFIG.ingestEnabled) {
            reset();
            return;
        }
        LongArrayList due = null;
        long now = System.currentTimeMillis();
        synchronized (PENDING) {
            if (PENDING.isEmpty()) {
                return;
            }
            var it = PENDING.long2LongEntrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (e.getLongValue() <= now) {
                    if (due == null) due = new LongArrayList();
                    due.add(e.getLongKey());
                    it.remove();
                }
            }
        }
        if (due == null) {
            return;
        }
        for (int i = 0; i < due.size(); i++) {
            long pos = due.getLong(i);
            var chunk = level.getChunkSource().getChunk(ChunkPos.getX(pos), ChunkPos.getZ(pos), false);
            if (chunk != null) {
                VoxelIngestService.tryAutoIngestChunk(chunk);
            }
        }
    }
}
