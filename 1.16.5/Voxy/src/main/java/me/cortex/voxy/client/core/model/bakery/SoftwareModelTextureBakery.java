package me.cortex.voxy.client.core.model.bakery;

import com.mojang.blaze3d.matrix.MatrixStack;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.common.util.UnsafeUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.world.IBlockDisplayReader;
import net.minecraft.client.renderer.FluidBlockRenderer;
import net.minecraftforge.client.model.data.EmptyModelData;
import net.minecraft.client.renderer.RenderTypeLookup;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.Direction;
import net.minecraft.util.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.LightType;
import net.minecraft.block.Blocks;
import net.minecraft.block.FlowingFluidBlock;
import net.minecraft.block.BlockRenderType;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.block.BlockState;
import net.minecraft.world.lighting.WorldLightManager;
import net.minecraft.fluid.FluidState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.ARBDirectStateAccess.glGetTextureImage;
import static org.lwjgl.opengl.ARBDirectStateAccess.glGetTextureLevelParameteri;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL12.GL_PACK_IMAGE_HEIGHT;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;

public class SoftwareModelTextureBakery {
    //Note: the first bit of metadata is if alpha discard is enabled
    private static final Matrix4f[] VIEWS = new Matrix4f[6];

    private final ReuseVertexConsumer opaqueVC = new ReuseVertexConsumer();
    private final ReuseVertexConsumer translucentVC = new ReuseVertexConsumer(1/*has discard*/);
    private final SoftwareRasterizer rasterizer = new SoftwareRasterizer(ModelFactory.MODEL_TEXTURE_SIZE);

    public SoftwareModelTextureBakery() {
    }

    /**
     * 1.21.1 has no FluidStateModelSet; fluids are drawn by {@link FluidBlockRenderer}. Minecraft's
     * own instance already has its sprites bound (BlockRenderDispatcher calls setupSprites on reload),
     * so reuse that rather than constructing a second, unsprited one.
     */
    private FluidBlockRenderer fluidRenderer() {
        // 1.16.5 exposes no accessor; the field is widened by voxy's access transformer.
        return Minecraft.getInstance().getBlockRenderer().liquidBlockRenderer;
    }

    public void setupTexture() {
        // 1.21.1 has no GpuTexture/TextureFormat abstraction -- AbstractTexture hands back the raw
        // GL name, and the level size is queried straight from the driver.
        int texId = Minecraft.getInstance().getTextureManager()
                .getTexture(new ResourceLocation("minecraft", "textures/atlas/blocks.png"))
                .getId();

        int targetMipLevel = 0;//todo: we want to target the mip layer that has the 16x16 sized textures

        int width = glGetTextureLevelParameteri(texId, targetMipLevel, GL_TEXTURE_WIDTH);
        int height = glGetTextureLevelParameteri(texId, targetMipLevel, GL_TEXTURE_HEIGHT);

        //Just do it ourselves as doing it with b3d has some issues, (doing it ourselves is also just much much much shorter)
        var texture = new int[width * height];

        glFlush();
        glFinish();
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        glPixelStorei(GL_PACK_ROW_LENGTH, 0);
        glPixelStorei(GL_PACK_IMAGE_HEIGHT, 0);
        glPixelStorei(GL_PACK_SKIP_ROWS, 0);
        glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
        glPixelStorei(GL_PACK_ALIGNMENT, 4);
        glGetTextureImage(texId, targetMipLevel, GL_RGBA, GL_UNSIGNED_BYTE, texture);
        glPixelStorei(GL_PACK_ROW_LENGTH, 0);
        this.rasterizer.setSamplerTexture(texture, width, height);
    }

    private void bakeBlockModel(BlockState state) {
        if (state.getRenderShape() == BlockRenderType.INVISIBLE) {
            return;//Dont bake if invisible
        }
        // 1.21.1 has no BlockStateModelSet/BlockStateModelPart: models come from the block renderer as
        // a BakedModel, and the render layer is per *block* rather than per quad.
        var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        RenderType layer = RenderTypeLookup.getChunkRenderType(state);
        var target = layer == RenderType.translucent() ? this.translucentVC : this.opaqueVC;
        boolean forceSolid = state.is(BlockTags.LEAVES);

        var random = new java.util.Random(42L);
        for (Direction direction : new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null}) {
            random.setSeed(42L);
            for (var quad : model.getQuads(state, direction, random, EmptyModelData.INSTANCE)) {
                target.quad(quad, layer, forceSolid);
            }
        }
    }


    private void bakeFluidState(BlockState state, int face) {
        this.fluidRenderer().tesselate(new IBlockDisplayReader() {
            @Override
            public WorldLightManager getLightEngine() {
                // 1.21.1 has no WorldLightManager.EMPTY; nothing here dereferences it because every
                // brightness path below is stubbed out.
                return null;
            }

            @Override
            public int getRawBrightness(BlockPos pos, int amount) {
                return 0;
            }

            @Override
            public int getBrightness(LightType type, BlockPos pos) {
                return 0;
            }
            @Override
            public float getShade(Direction direction, boolean shade) {
                // 26.x handed the baker CardinalLighting.DEFAULT, which is *not* neutral:
                //   down 0.5, up 1.0, north/south 0.8, west/east 0.6
                // 1.21.1 has no CardinalLighting, so reproduce those exact multipliers here --
                // returning a flat 1.0 bakes LoD models without directional shading and makes them
                // look flatter than the 26.1.2 build.
                return switch (direction) {
                    case DOWN -> 0.5f;
                    case UP -> 1.0f;
                    case NORTH, SOUTH -> 0.8f;
                    case WEST, EAST -> 0.6f;
                };
            }

            @Override
            public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
                //This is such a stupid and bad hack, we can inject tinting state here since this is called
                // before the quad is added
                //TODO: need to make a quad once tinting thing
                translucentVC.setDefaultMeta(translucentVC.getDefaultMeta()|4);//Tinting
                opaqueVC.setDefaultMeta(opaqueVC.getDefaultMeta()|4);//Tinting
                return -1;
            }

            @Nullable
            @Override
            public TileEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState();
                }

                //Fixme:
                // This makes it so that the top face of water is always air, if this is commented out
                //  the up block will be a liquid state which makes the sides full
                // if this is uncommented, that issue is fixed but e.g. stacking water layers ontop of eachother
                //  doesnt fill the side of the block

                //if (pos.getY() == 1) {
                //    return Blocks.AIR.getDefaultState();
                //}
                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState().getFluidState();
                }

                return state.getFluidState();
            }

                        public int getHeight() {
                return 0;
            }

                        public int getMinBuildHeight() {
                return 0;
            }
        // 1.16.5: tesselate(IBlockDisplayReader, BlockPos, IVertexBuilder, FluidState)
        }, BlockPos.ZERO, this.fluidConsumerFor(state), state.getFluidState());
        this.translucentVC.setDefaultMeta(0);//Reset default meta
        this.opaqueVC.setDefaultMeta(0);//Reset default meta
    }

    private static boolean shouldReturnAirForFluid(BlockPos pos, int face) {
        var fv = Direction.from3DDataValue(face).getNormal();
        int dot = fv.getX()*pos.getX() + fv.getY()*pos.getY() + fv.getZ()*pos.getZ();
        return dot >= 1;
    }

    public void free() {
        this.opaqueVC.free();
        this.translucentVC.free();
    }

    private static final long SINGLE_FACE_OUTPUT_SIZE = (ModelFactory.MODEL_TEXTURE_SIZE * ModelFactory.MODEL_TEXTURE_SIZE)*8;
    //The outputBuffer layout is different from the non software rasterized ModelTextureBakery
    // in this version the values are simply appended (0,0),(1,0),(2,0),(0,1),(1,1),(2,1)

    public int renderToOutput(BlockState state, long outputBuffer) {
        MemoryUtil.memSet(outputBuffer,0,16*16*8*6);


        boolean isBlock = true;
        if (state.getBlock() instanceof FlowingFluidBlock) {
            isBlock = false;
        }

        //TODO: support block model entities
        //BakedBlockEntityModel bbem = null;
        if (state instanceof net.minecraft.block.ITileEntityProvider) {
            //bbem = BakedBlockEntityModel.bake(state);
        }

        boolean isAnyShaded = false;
        boolean isAnyDarkend = false;
        boolean anyTranslucent = false;
        boolean anyDiscard = false;
        if (isBlock) {
            this.opaqueVC.reset();
            this.translucentVC.reset();
            this.bakeBlockModel(state);
            isAnyShaded |= this.opaqueVC.anyShaded|this.translucentVC.anyShaded;
            isAnyDarkend |= this.opaqueVC.anyDarkendTex|this.translucentVC.anyDarkendTex;
            anyTranslucent |= !this.translucentVC.isEmpty();
            anyDiscard |= this.opaqueVC.anyDiscard;
            if (!(this.opaqueVC.isEmpty()&&this.translucentVC.isEmpty())) {//only render if there... is shit to render
                for (int i = 0; i < VIEWS.length; i++) {
                    this.rasterizer.setFaceCull(i==1||i==2||i==4);
                    this.rasterizer.clear();
                    this.rasterizer.setBlending(false);
                    this.rasterizer.raster(VIEWS[i], this.opaqueVC);
                    this.rasterizer.setBlending(true);
                    this.rasterizer.raster(VIEWS[i], this.translucentVC);
                    UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(), outputBuffer+(SINGLE_FACE_OUTPUT_SIZE*i));
                }
            }
        } else {//Is fluid, slow path :(

            if (!(state.getBlock() instanceof FlowingFluidBlock)) throw new IllegalStateException();
            for (int i = 0; i < VIEWS.length; i++) {
                this.opaqueVC.reset();
                this.translucentVC.reset();
                this.bakeFluidState(state, i);
                if (this.opaqueVC.isEmpty()&&this.translucentVC.isEmpty()) continue;
                isAnyShaded |= this.opaqueVC.anyShaded|this.translucentVC.anyShaded;
                isAnyDarkend |= this.opaqueVC.anyDarkendTex|this.translucentVC.anyDarkendTex;
                anyTranslucent |= !this.translucentVC.isEmpty();
                anyDiscard |= this.opaqueVC.anyDiscard;

                this.rasterizer.setFaceCull(i==1||i==2||i==4);

                //The projection matrix
                this.rasterizer.clear();
                this.rasterizer.setBlending(false);
                this.rasterizer.raster(VIEWS[i], this.opaqueVC);
                this.rasterizer.setBlending(true);
                this.rasterizer.raster(VIEWS[i], this.translucentVC);
                UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(), outputBuffer+(SINGLE_FACE_OUTPUT_SIZE*i));
            }
        }

        return (isAnyShaded?1:0)|(isAnyDarkend?2:0)|(anyTranslucent?4:0)|(anyDiscard?8:0);
    }




    static {
        //the face/direction is the face (e.g. down is the down face)
        addView(0, -90,0, 0, 0);//Direction.DOWN
        addView(1, 90,0, 0, 0b100);//Direction.UP

        addView(2, 0,180, 0, 0b001);//Direction.NORTH
        addView(3, 0,0, 0, 0);//Direction.SOUTH

        addView(4, 0,90, 270, 0b100);//Direction.WEST
        addView(5, 0,270, 270, 0);//Direction.EAST
    }

    private static void addView(int i, float pitch, float yaw, float rotation, int flip) {
        // Upstream accumulated this through MinecraftMatrixStack, but 1.16.5's stack takes
        // Minecraft's own Quaternion/Matrix4f rather than JOML's. The stack was only ever a
        // post-multiplying accumulator, which JOML does natively, so build it directly and
        // avoid converting types back and forth.
        var mat = new Matrix4f();
        mat.translate(0.5f, 0.5f, 0.5f);
        mat.rotate(makeQuatFromAxisExact(new Vector3f(0,0,1), rotation));
        mat.rotate(makeQuatFromAxisExact(new Vector3f(1,0,0), pitch));
        mat.rotate(makeQuatFromAxisExact(new Vector3f(0,1,0), yaw));
        mat.scale(1-2*(flip&1), 1-(flip&2), 1-((flip>>1)&2));
        mat.translate(-0.5f, -0.5f, -0.5f);

        mat = new Matrix4f().set(
                        2,0,0,0,
                        0,2,0,0,
                        0,0,-2,0,
                        -1,-1,1,1)
                .mul(mat);
        VIEWS[i] = mat;
    }

    private static Quaternionf makeQuatFromAxisExact(Vector3f vec, float angle) {
        angle = (float) Math.toRadians(angle);
        float hangle = angle / 2.0f;
        float sinAngle = (float) Math.sin(hangle);
        float invVLength = (float) (1/Math.sqrt(vec.lengthSquared()));
        return new Quaternionf(vec.x * invVLength * sinAngle,
                vec.y * invVLength * sinAngle,
                vec.z * invVLength * sinAngle,
                Math.cos(hangle));
    }

    /**
     * Picks the target vertex consumer for a fluid. The 26.x FluidRenderer asked for one per layer
     * as it emitted; 1.21.1 draws a fluid into a single consumer, so the layer is resolved up front
     * from {@link RenderTypeLookup#getRenderLayer(net.minecraft.fluid.FluidState)}.
     */
    private ReuseVertexConsumer fluidConsumerFor(BlockState state) {
        RenderType layer = RenderTypeLookup.getRenderLayer(state.getFluidState());
        if (layer == RenderType.translucent()) {
            return this.translucentVC;
        }
        if (layer == RenderType.cutout()) {
            this.opaqueVC.setDefaultMeta(this.opaqueVC.getDefaultMeta() | 1);//set discard
        } else {
            this.opaqueVC.setDefaultMeta(this.opaqueVC.getDefaultMeta() & ~1);//remove discard
        }
        return this.opaqueVC;
    }
}
