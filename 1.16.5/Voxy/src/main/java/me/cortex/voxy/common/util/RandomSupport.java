package me.cortex.voxy.common.util;

/**
 * Java 8 / 1.16.5 stand-in for {@code net.minecraft.world.level.levelgen.RandomSupport}, which
 * does not exist before 1.19.
 * <p>
 * Voxy only uses the mixer, and only to spread storage keys across backend shards -- it is a hash,
 * not a source of randomness, so it must produce exactly the same values as the vanilla method or
 * previously written data would hash to a different shard and be lost. This is the standard
 * splitmix64 finalizer Mojang uses, reproduced verbatim.
 */
public final class RandomSupport {
    private RandomSupport() {}

    public static long mixStafford13(long seed) {
        seed = (seed ^ seed >>> 30) * -4658895280553007687L;
        seed = (seed ^ seed >>> 27) * -7723592293110705685L;
        return seed ^ seed >>> 31;
    }
}
