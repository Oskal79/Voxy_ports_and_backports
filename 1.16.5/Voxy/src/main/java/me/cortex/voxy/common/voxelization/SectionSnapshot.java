package me.cortex.voxy.common.voxelization;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.BitArray;
import net.minecraft.util.palette.ArrayPalette;
import net.minecraft.util.palette.HashMapPalette;
import net.minecraft.util.palette.IPalette;
import net.minecraft.util.palette.IdentityPalette;
import net.minecraft.util.palette.PalettedContainer;
import net.minecraft.world.chunk.ChunkSection;

/**
 * An immutable copy of a chunk section's block data, taken on the thread that owns the section.
 * <p>
 * voxy converts sections on background workers. On 1.21.1 that is survivable because
 * {@code PalettedContainer} keeps its palette and bit storage together in a single {@code Data}
 * record, so one field read hands the worker a matching pair. 1.16.5 keeps them as two separate
 * mutable fields, and {@code PalettedContainer#setBits} replaces both when a section's palette
 * grows -- which the render thread does whenever a block is placed or a chunk arrives.
 * <p>
 * A worker that reads {@code palette} before that swap and {@code storage} after it decodes the
 * new, wider indices against the old, shorter palette. {@code ArrayPalette#valueFor} and
 * {@code HashMapPalette#valueFor} answer {@code null} for the out-of-range indices that follow,
 * the conversion recorded those as block id {@code -1}, and {@code Mapper#composeMappingId}
 * shifted {@code -1} into a 20-bit field as {@code 0xFFFFF}. That id crashed the ingest service
 * outright before it was bounds-checked, and reads back as air (drawn black) now that it is.
 * <p>
 * Copying the palette and the packed longs up front removes the race rather than tolerating it:
 * the arrays here cannot change once handed to a worker.
 */
public final class SectionSnapshot {
    /** Local palette, already resolved to block states. Null when {@link #identity} is set. */
    public final BlockState[] palette;
    /** True when the section used the global/identity palette, i.e. raw values are registry ids. */
    public final boolean identity;
    /** The packed block indices, copied. Never null. */
    public final long[] raw;
    /** Bits per entry in {@link #raw}. */
    public final int bits;
    /** Whether the source section reported itself empty. */
    public final boolean empty;

    private SectionSnapshot(BlockState[] palette, boolean identity, long[] raw, int bits, boolean empty) {
        this.palette = palette;
        this.identity = identity;
        this.raw = raw;
        this.bits = bits;
        this.empty = empty;
    }

    /**
     * A 16x16x16 block of air.
     * <p>
     * 1.21.1 allocates a {@code LevelChunkSection} for every slice of a chunk, so voxy always sees
     * the air above the terrain and stores its sky light. 1.16.5 leaves all-air sections as
     * {@code Chunk.EMPTY_SECTION}, i.e. null, and skipping those left voxy's store holding air with
     * light 0 above the ground. The mesher lights a fully-opaque block's face from the voxel on the
     * far side of that face, so a surface whose air neighbour was never ingested renders unlit.
     */
    public static SectionSnapshot air() {
        //bits=4 gives 16 entries per long, which is what the conversion loop's iterPerLong expects
        //for a 4096-entry section; every index is 0, which the palette maps to air.
        return new SectionSnapshot(new BlockState[]{Blocks.AIR.defaultBlockState()}, false,
                new long[256], 4, true);
    }

    public static SectionSnapshot of(ChunkSection section) {
        return of(section.getStates(), section.isEmpty());
    }

    public static SectionSnapshot of(PalettedContainer<BlockState> container, boolean empty) {
        IPalette<BlockState> vp = container.palette;
        BitArray storage = container.storage;
        if (storage == null) {
            //A container that has not been initialised has nothing to snapshot.
            return null;
        }
        long[] raw = storage.getRaw().clone();
        int bits = storage.bits;

        if (vp instanceof IdentityPalette) {
            return new SectionSnapshot(null, true, raw, bits, empty);
        }

        int size = paletteSize(vp);
        //The palette is only ever read with `bits`-wide indices, so size the copy to cover every
        //index the storage can produce. Entries past the palette's own end stay null and are
        //treated as air rather than as the -1 that used to poison the mapping id.
        //1.16.5 only uses a local palette up to 8 bits per entry; wider sections use the identity
        //palette, handled above. Bounding the pad at 256 keeps the copy small.
        int cap = Math.max(size, 1 << Math.min(bits, 8));
        BlockState[] states = new BlockState[cap];
        for (int i = 0; i < Math.min(size, cap); i++) {
            BlockState state = null;
            try {
                state = vp.valueFor(i);
            } catch (Exception ignored) {
                //An index the palette rejects is air as far as voxy is concerned.
            }
            states[i] = state;
        }
        return new SectionSnapshot(states, false, raw, bits, empty);
    }

    /**
     * 1.16.5's {@link IPalette} does not declare a size accessor -- {@code getSize()} only exists
     * on the concrete implementations, and {@link IdentityPalette} has none at all.
     */
    private static int paletteSize(IPalette<BlockState> vp) {
        if (vp instanceof ArrayPalette) {
            return ((ArrayPalette<BlockState>) vp).getSize();
        }
        if (vp instanceof HashMapPalette) {
            return ((HashMapPalette<BlockState>) vp).getSize();
        }
        throw new IllegalStateException("Unknown palette type: " + vp);
    }
}
