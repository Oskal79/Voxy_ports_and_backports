package me.cortex.voxy.common.util;

/**
 * Java 8 implementations of {@code Integer}/{@code Long}'s {@code compress} and {@code expand},
 * which are Java 19+ (they map to the x86 PEXT/PDEP instructions).
 * <p>
 * Voxy uses these in the per-block conversion loop to scatter/gather bit fields, so behaviour
 * must match the JDK exactly: {@code compress} takes the bits of {@code i} selected by
 * {@code mask} and packs them into the low-order bits of the result, in order;
 * {@code expand} is the inverse.
 */
public final class Bits {
    private Bits() {}

    public static int compress(int i, int mask) {
        int result = 0;
        int out = 0;
        while (mask != 0) {
            int bit = mask & -mask;          // lowest set bit of the mask
            if ((i & bit) != 0) {
                result |= 1 << out;
            }
            out++;
            mask &= mask - 1;                // clear it
        }
        return result;
    }

    public static int expand(int i, int mask) {
        int result = 0;
        int in = 0;
        while (mask != 0) {
            int bit = mask & -mask;
            if ((i & (1 << in)) != 0) {
                result |= bit;
            }
            in++;
            mask &= mask - 1;
        }
        return result;
    }

    public static long compress(long i, long mask) {
        long result = 0;
        int out = 0;
        while (mask != 0) {
            long bit = mask & -mask;
            if ((i & bit) != 0) {
                result |= 1L << out;
            }
            out++;
            mask &= mask - 1;
        }
        return result;
    }

    public static long expand(long i, long mask) {
        long result = 0;
        int in = 0;
        while (mask != 0) {
            long bit = mask & -mask;
            if ((i & (1L << in)) != 0) {
                result |= bit;
            }
            in++;
            mask &= mask - 1;
        }
        return result;
    }
}
