package me.cortex.voxy.client.core.model.bakery;


import com.mojang.blaze3d.vertex.IVertexBuilder;
import me.cortex.voxy.common.util.MemoryBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.model.BakedQuad;
import org.lwjgl.system.MemoryUtil;

public final class ReuseVertexConsumer implements IVertexBuilder {
    public static final int VERTEX_FORMAT_SIZE = 24;
    private MemoryBuffer buffer = new MemoryBuffer(8192);
    private long ptr;
    private int count;
    private int defaultMeta;

    public boolean anyShaded;
    public boolean anyDarkendTex;
    public boolean anyDiscard;

    private final int globalOrMetadata;
    public ReuseVertexConsumer() {
        this(0);
    }
    public ReuseVertexConsumer(int globalOrMetadata) {
        this.reset();
        this.globalOrMetadata = globalOrMetadata;
    }

    public ReuseVertexConsumer setDefaultMeta(int meta) {
        this.defaultMeta = meta;
        return this;
    }

    public int getDefaultMeta() {
        return this.defaultMeta;
    }

    @Override
    public ReuseVertexConsumer vertex(double x, double y, double z) {
        this.ensureCanPut();
        this.ptr += VERTEX_FORMAT_SIZE; this.count++; //Goto next vertex
        this.meta(this.defaultMeta|this.globalOrMetadata);
        MemoryUtil.memPutFloat(this.ptr, (float) x);
        MemoryUtil.memPutFloat(this.ptr + 4, (float) y);
        MemoryUtil.memPutFloat(this.ptr + 8, (float) z);
        return this;
    }

    public ReuseVertexConsumer meta(int metadata) {
        this.anyDiscard |= (metadata&1)!=0;
        MemoryUtil.memPutInt(this.ptr + 12, metadata);
        return this;
    }

    @Override
    public ReuseVertexConsumer color(int red, int green, int blue, int alpha) {
        return this;
    }

    public IVertexBuilder setColor(int i) {
        return this;
    }

    @Override
    public ReuseVertexConsumer uv(float u, float v) {
        MemoryUtil.memPutFloat(this.ptr + 16, u);
        MemoryUtil.memPutFloat(this.ptr + 20, v);
        return this;
    }

    @Override
    public ReuseVertexConsumer overlayCoords(int u, int v) {
        return this;
    }

    @Override
    public ReuseVertexConsumer uv2(int u, int v) {
        return this;
    }

    @Override
    public ReuseVertexConsumer normal(float x, float y, float z) {
        return this;
    }

    /** Stride of DefaultVertexFormat.BLOCK, in ints per vertex (pos3 + colour1 + uv2 + light1 + normal1). */
    private static final int QUAD_VERTEX_STRIDE = 8;

    public ReuseVertexConsumer quad(BakedQuad quad, RenderType layer) {
        return this.quad(quad, layer, false);
    }

    public ReuseVertexConsumer quad(BakedQuad quad, RenderType layer, boolean forceSolid) {
        int meta = 0;
        // 1.21.1 has no per-quad material info -- the render layer is a property of the *block*, so
        // the caller passes in whatever RenderTypeLookup resolved for this state.
        meta |= forceSolid ? 0 : (layer != RenderType.solid() ? 1 : 0);//has discard
        meta |= quad.isTinted() ? 4 : 0;//has tinting
        return this.quad(quad, meta);
    }

    public ReuseVertexConsumer quad(BakedQuad quad, int metadata) {
        this.anyShaded |= quad.isShade();
        // MipmapStrategy (and therefore DARK_CUTOUT sprites) does not exist in 1.21.1.
        this.ensureCanPut();

        int[] vertices = quad.getVertices();
        for (int i = 0; i < 4; i++) {
            int base = i * QUAD_VERTEX_STRIDE;
            this.addVertex(Float.intBitsToFloat(vertices[base]),
                           Float.intBitsToFloat(vertices[base + 1]),
                           Float.intBitsToFloat(vertices[base + 2]));
            this.setUv(Float.intBitsToFloat(vertices[base + 4]),
                       Float.intBitsToFloat(vertices[base + 5]));

            this.meta(metadata | this.globalOrMetadata);
        }
        return this;
    }

    private void ensureCanPut() {
        if ((long) (this.count + 5) * VERTEX_FORMAT_SIZE < this.buffer.size) {
            return;
        }
        long offset = this.ptr-this.buffer.address;
        //1.5x the size
        var newBuffer = new MemoryBuffer((((int)(this.buffer.size*2)+VERTEX_FORMAT_SIZE-1)/VERTEX_FORMAT_SIZE)*VERTEX_FORMAT_SIZE);
        this.buffer.cpyTo(newBuffer.address);
        this.buffer.free();
        this.buffer = newBuffer;
        this.ptr = offset + newBuffer.address;
    }

    public ReuseVertexConsumer reset() {
        this.anyShaded = false;
        this.anyDarkendTex = false;
        this.anyDiscard = false;
        this.defaultMeta = 0;//RESET THE DEFAULT META
        this.count = 0;
        this.ptr = this.buffer.address - VERTEX_FORMAT_SIZE;//the thing is first time this gets incremented by FORMAT_STRIDE
        return this;
    }

    public void free() {
        this.ptr = 0;
        this.count = 0;
        this.buffer.free();
        this.buffer = null;
    }

    public boolean isEmpty() {
        return this.count == 0;
    }

    public int quadCount() {
        if (this.count%4 != 0) throw new IllegalStateException();
        return this.count/4;
    }

    public long getAddress() {
        return this.buffer.address;
    }

    // 1.16.5's IVertexBuilder finalises a vertex explicitly. This consumer advances its write
    // pointer at the start of the *next* vertex instead, so there is nothing to flush here.
    @Override
    public void endVertex() {
    }

    // Aliases for the 1.21.1 method names, so the rest of the bakery reads unchanged.
    public ReuseVertexConsumer addVertex(float x, float y, float z) { return this.vertex(x, y, z); }
    public ReuseVertexConsumer setColor(int r, int g, int b, int a) { return this.color(r, g, b, a); }
    public ReuseVertexConsumer setUv(float u, float v) { return this.uv(u, v); }
    public ReuseVertexConsumer setUv1(int u, int v) { return this.overlayCoords(u, v); }
    public ReuseVertexConsumer setUv2(int u, int v) { return this.uv2(u, v); }
    public ReuseVertexConsumer setNormal(float x, float y, float z) { return this.normal(x, y, z); }
}
