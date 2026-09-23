package me.cortex.voxy.client.core.model;

import java.util.Arrays;

public final class ColourDepthTextureData {
    private final int[] colour;
    private final int[] depth;
    private final int width;
    private final int height;
    private final int hash;

    public ColourDepthTextureData(int[] colour, int[] depth, int width, int height, int hash) {
        this.colour = colour;
        this.depth = depth;
        this.width = width;
        this.height = height;
        this.hash = hash;
    }

    public int[] colour() { return this.colour; }
    public int[] depth() { return this.depth; }
    public int width() { return this.width; }
    public int height() { return this.height; }
    public int hash() { return this.hash; }

    @Override public String toString() { return "ColourDepthTextureData[colour=" + this.colour + ", depth=" + this.depth + ", width=" + this.width + ", height=" + this.height + ", hash=" + this.hash + "]"; }

    public ColourDepthTextureData(int[] colour, int[] depth, int width, int height) {
        this(colour, depth, width, height, width * 312337173 * (Arrays.hashCode(colour) ^ Arrays.hashCode(depth)) ^ height);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) return false;
        var other = ((ColourDepthTextureData)obj);
        return this.hash == other.hash && Arrays.equals(other.colour, this.colour) && Arrays.equals(other.depth, this.depth);
    }

    @Override
    public int hashCode() {
        return this.hash;
    }

    @Override
    public ColourDepthTextureData clone() {
        return new ColourDepthTextureData(Arrays.copyOf(this.colour, this.colour.length), Arrays.copyOf(this.depth, this.depth.length), this.width, this.height, this.hash);
    }
}
