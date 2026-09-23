package me.cortex.voxy.client.iris;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.Logger;
import net.coderbot.iris.pipeline.ShadowRenderer;
import net.coderbot.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.potion.Effects;
import net.minecraft.tags.FluidTags;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongConsumer;

public class OculusUniformProvider {

    public enum UniformType {
        INT, FLOAT, MAT4, VEC2, VEC2I, VEC3, VEC3I, VEC4, VEC4I
    }

    public static Matrix4f currentModelView = new Matrix4f();
    public static Matrix4f currentProjection = new Matrix4f();
    public static Matrix4f currentModelViewInv = new Matrix4f();
    public static Matrix4f currentProjectionInv = new Matrix4f();
    public static Matrix4f prevModelView = new Matrix4f();
    public static Matrix4f prevProjection = new Matrix4f();

    private static int frameCounter = 0;

    private static String convertToGlslType(UniformType type) {
        switch (type) {
            case INT: return "int";
            case FLOAT: return "float";
            case MAT4: return "mat4";
            case VEC2: return "vec2";
            case VEC2I: return "ivec2";
            case VEC3: return "vec3";
            case VEC3I: return "ivec3";
            case VEC4: return "vec4";
            case VEC4I: return "ivec4";
            default: return "float";
        }
    }

    private static int P(int size, int align) {
        return size << 5 | align;
    }

    private static int getSizeAndAlignment(UniformType type) {
        switch (type) {
            case INT:
            case FLOAT:
                return P(1, 1);
            case MAT4:
                return P(16, 4);
            case VEC2:
            case VEC2I:
                return P(2, 2);
            case VEC3:
            case VEC3I:
                return P(3, 4);
            case VEC4:
            case VEC4I:
                return P(4, 4);
            default:
                return P(1, 1);
        }
    }

    private static int getUniformOrdering(UniformType type) {
        switch (type) {
            case MAT4:
            case VEC4:
            case VEC4I:
                return 0;
            case VEC2:
            case VEC2I:
                return 1;
            case VEC3:
            case VEC3I:
                return 2;
            case INT:
            case FLOAT:
            default:
                return 3;
        }
    }

    private static UniformType getUniformType(String name) {
        if (name.contains("ModelView") || name.contains("Projection") || name.contains("Proj") || name.contains("ViewProj")) {
            return UniformType.MAT4;
        }
        if (name.endsWith("Position") || name.endsWith("PositionFract") ||
            name.equals("relativeEyePosition") || name.equals("fogColor") ||
            name.equals("skyColor") || name.equals("sunVec") || name.equals("upVec") ||
            name.equals("light_dir") || name.equals("sun_dir") || name.equals("moon_dir")) {
            return UniformType.VEC3;
        }
        if (name.endsWith("PositionInt")) {
            return UniformType.VEC3I;
        }
        if (name.equals("eyeBrightness")) {
            return UniformType.VEC2I;
        }
        if (name.equals("view_res") || name.equals("view_pixel_size") || name.equals("taa_offset")) {
            return UniformType.VEC2;
        }
        if (name.equals("worldDay") || name.equals("worldTime") || name.equals("moonPhase") ||
            name.equals("frameCounter") || name.equals("isEyeInWater") || name.equals("vxRenderDistance") ||
            name.startsWith("heldItemId") || name.startsWith("heldBlockLightValue") ||
            name.startsWith("inNether") || name.startsWith("inCrimson") || name.startsWith("inWarped") ||
            name.startsWith("inBasalt") || name.startsWith("inSoul") || name.startsWith("inSnowy") ||
            name.startsWith("inDry") || name.startsWith("inRainy")) {
            return UniformType.INT;
        }
        return UniformType.FLOAT;
    }

    private static final class UniformHolderEntry {
        public final String name;
        public final UniformType type;

        public UniformHolderEntry(String name, UniformType type) {
            this.name = name;
            this.type = type;
        }
    }

    public static IrisVoxyRenderPipelineData.StructLayout buildUniformLayout(String[] requestedUniforms) {
        if (requestedUniforms == null || requestedUniforms.length == 0) {
            return null;
        }

        Set<String> uniformNames = new LinkedHashSet<>();
        for (String name : requestedUniforms) {
            if (name != null && !name.trim().isEmpty()) {
                uniformNames.add(name.trim());
            }
        }

        List<UniformHolderEntry> entries = new ArrayList<>();
        for (String name : uniformNames) {
            entries.add(new UniformHolderEntry(name, getUniformType(name)));
        }

        @SuppressWarnings("unchecked")
        List<UniformHolderEntry>[] ordering = new List[]{
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>()
        };

        for (UniformHolderEntry entry : entries) {
            ordering[getUniformOrdering(entry.type)].add(entry);
        }

        int pos = 0;
        Int2ObjectLinkedOpenHashMap<UniformHolderEntry> layout = new Int2ObjectLinkedOpenHashMap<>();

        for (UniformHolderEntry entry : ordering[0]) {
            layout.put(pos, entry);
            pos += getSizeAndAlignment(entry.type) >> 5;
        }
        if (!ordering[1].isEmpty() && (ordering[1].size() & 1) == 0) {
            for (UniformHolderEntry entry : ordering[1]) {
                layout.put(pos, entry);
                pos += getSizeAndAlignment(entry.type) >> 5;
            }
            ordering[1].clear();
        }
        for (UniformHolderEntry entry : ordering[2]) {
            layout.put(pos, entry);
            pos += getSizeAndAlignment(entry.type) >> 5;
            if (!ordering[3].isEmpty()) {
                UniformHolderEntry small = ordering[3].remove(0);
                layout.put(pos, small);
                pos += getSizeAndAlignment(small.type) >> 5;
            } else {
                pos += 1; // 4-byte padding for vec3
            }
        }
        for (UniformHolderEntry entry : ordering[1]) {
            layout.put(pos, entry);
            pos += getSizeAndAlignment(entry.type) >> 5;
        }
        for (UniformHolderEntry entry : ordering[3]) {
            layout.put(pos, entry);
            pos += getSizeAndAlignment(entry.type) >> 5;
        }

        StringBuilder struct = new StringBuilder("{\n");
        for (Int2ObjectMap.Entry<UniformHolderEntry> pair : layout.int2ObjectEntrySet()) {
            struct.append("\t")
                    .append(convertToGlslType(pair.getValue().type))
                    .append(" ")
                    .append(pair.getValue().name)
                    .append(";\n");
        }
        struct.append("}");
        String structLayout = struct.toString();

        List<LongConsumer> writers = new ArrayList<>();
        for (Int2ObjectMap.Entry<UniformHolderEntry> pair : layout.int2ObjectEntrySet()) {
            long offset = pair.getIntKey() * 4L;
            String name = pair.getValue().name;
            UniformType type = pair.getValue().type;
            writers.add(createWriter(name, type, offset));
        }

        LongConsumer updater = ptr -> {
            frameCounter++;
            for (LongConsumer w : writers) {
                w.accept(ptr);
            }
        };

        return new IrisVoxyRenderPipelineData.StructLayout(pos * 4, structLayout, updater);
    }

    private static final java.nio.FloatBuffer matrixBuf = org.lwjgl.BufferUtils.createFloatBuffer(16);

    private static synchronized Matrix4f toJoml(net.minecraft.util.math.vector.Matrix4f mc) {
        if (mc == null) return new Matrix4f();
        try {
            ((java.nio.Buffer) matrixBuf).clear();
            mc.store(matrixBuf);
            ((java.nio.Buffer) matrixBuf).rewind();
            Matrix4f res = new Matrix4f();
            res.set(matrixBuf);
            return res;
        } catch (Exception ignored) {}
        return new Matrix4f();
    }

    private static LongConsumer createWriter(String name, UniformType type, long offset) {
        return ptr -> {
            long addr = ptr + offset;
            Minecraft mc = Minecraft.getInstance();
            ClientWorld world = mc.level;
            PlayerEntity player = mc.player;
            ActiveRenderInfo cam = mc.gameRenderer != null ? mc.gameRenderer.getMainCamera() : null;

            double camX = cam != null ? cam.getPosition().x : 0;
            double camY = cam != null ? cam.getPosition().y : 0;
            double camZ = cam != null ? cam.getPosition().z : 0;

            if ("vxModelView".equals(name) || "gbufferModelView".equals(name)) {
                currentModelView.getToAddress(addr);
                return;
            }
            if ("vxModelViewInv".equals(name) || "gbufferModelViewInverse".equals(name)) {
                currentModelViewInv.getToAddress(addr);
                return;
            }
            if ("vxModelViewPrev".equals(name) || "gbufferPreviousModelView".equals(name)) {
                prevModelView.getToAddress(addr);
                return;
            }
            if ("vxProj".equals(name) || "gbufferProjection".equals(name)) {
                currentProjection.getToAddress(addr);
                return;
            }
            if ("vxProjInv".equals(name) || "gbufferProjectionInverse".equals(name)) {
                currentProjectionInv.getToAddress(addr);
                return;
            }
            if ("vxProjPrev".equals(name) || "gbufferPreviousProjection".equals(name)) {
                prevProjection.getToAddress(addr);
                return;
            }
            if ("shadowModelView".equals(name)) {
                toJoml(ShadowRenderer.MODELVIEW).getToAddress(addr);
                return;
            }
            if ("shadowProjection".equals(name)) {
                toJoml(ShadowRenderer.PROJECTION).getToAddress(addr);
                return;
            }
            if ("shadowModelViewInverse".equals(name)) {
                new Matrix4f(toJoml(ShadowRenderer.MODELVIEW)).invert().getToAddress(addr);
                return;
            }
            if ("shadowProjectionInverse".equals(name)) {
                new Matrix4f(toJoml(ShadowRenderer.PROJECTION)).invert().getToAddress(addr);
                return;
            }

            if ("cameraPosition".equals(name)) {
                MemoryUtil.memPutFloat(addr, (float) camX);
                MemoryUtil.memPutFloat(addr + 4, (float) camY);
                MemoryUtil.memPutFloat(addr + 8, (float) camZ);
                return;
            }
            if ("previousCameraPosition".equals(name)) {
                MemoryUtil.memPutFloat(addr, (float) camX);
                MemoryUtil.memPutFloat(addr + 4, (float) camY);
                MemoryUtil.memPutFloat(addr + 8, (float) camZ);
                return;
            }
            if ("cameraPositionInt".equals(name)) {
                MemoryUtil.memPutInt(addr, (int) camX);
                MemoryUtil.memPutInt(addr + 4, (int) camY);
                MemoryUtil.memPutInt(addr + 8, (int) camZ);
                return;
            }
            if ("cameraPositionFract".equals(name) || "previousCameraPositionFract".equals(name)) {
                MemoryUtil.memPutFloat(addr, (float) (camX - (int) camX));
                MemoryUtil.memPutFloat(addr + 4, (float) (camY - (int) camY));
                MemoryUtil.memPutFloat(addr + 8, (float) (camZ - (int) camZ));
                return;
            }
            if ("relativeEyePosition".equals(name)) {
                MemoryUtil.memPutFloat(addr, 0f);
                MemoryUtil.memPutFloat(addr + 4, 0f);
                MemoryUtil.memPutFloat(addr + 8, 0f);
                return;
            }
            if ("fogColor".equals(name)) {
                net.coderbot.iris.vendored.joml.Vector3d fc = CapturedRenderingState.INSTANCE.getFogColor();
                if (fc != null) {
                    MemoryUtil.memPutFloat(addr, (float) fc.x);
                    MemoryUtil.memPutFloat(addr + 4, (float) fc.y);
                    MemoryUtil.memPutFloat(addr + 8, (float) fc.z);
                } else {
                    MemoryUtil.memPutFloat(addr, 0.7f);
                    MemoryUtil.memPutFloat(addr + 4, 0.8f);
                    MemoryUtil.memPutFloat(addr + 8, 1.0f);
                }
                return;
            }
            if ("skyColor".equals(name)) {
                if (mc.level != null && mc.getCameraEntity() != null) {
                    net.minecraft.util.math.vector.Vector3d sc = mc.level.getSkyColor(
                            mc.getCameraEntity().blockPosition(),
                            CapturedRenderingState.INSTANCE.getTickDelta()
                    );
                    MemoryUtil.memPutFloat(addr, (float) sc.x);
                    MemoryUtil.memPutFloat(addr + 4, (float) sc.y);
                    MemoryUtil.memPutFloat(addr + 8, (float) sc.z);
                    return;
                }
                MemoryUtil.memPutFloat(addr, 0.5f);
                MemoryUtil.memPutFloat(addr + 4, 0.7f);
                MemoryUtil.memPutFloat(addr + 8, 1.0f);
                return;
            }
            if ("sun_dir".equals(name) || "sunVec".equals(name)) {
                net.coderbot.iris.vendored.joml.Vector3f sd = OculusExtendedUniforms.getSunDir(OculusExtendedUniforms.sunPathRotation);
                MemoryUtil.memPutFloat(addr, sd.x);
                MemoryUtil.memPutFloat(addr + 4, sd.y);
                MemoryUtil.memPutFloat(addr + 8, sd.z);
                return;
            }
            if ("moon_dir".equals(name)) {
                net.coderbot.iris.vendored.joml.Vector3f md = OculusExtendedUniforms.getMoonDir(OculusExtendedUniforms.sunPathRotation);
                MemoryUtil.memPutFloat(addr, md.x);
                MemoryUtil.memPutFloat(addr + 4, md.y);
                MemoryUtil.memPutFloat(addr + 8, md.z);
                return;
            }
            if ("light_dir".equals(name)) {
                net.coderbot.iris.vendored.joml.Vector3f ld = OculusExtendedUniforms.getLightDir(OculusExtendedUniforms.sunPathRotation);
                MemoryUtil.memPutFloat(addr, ld.x);
                MemoryUtil.memPutFloat(addr + 4, ld.y);
                MemoryUtil.memPutFloat(addr + 8, ld.z);
                return;
            }
            if ("view_sun_dir".equals(name)) {
                net.coderbot.iris.vendored.joml.Vector3f vsd = OculusExtendedUniforms.getViewSunDir(OculusExtendedUniforms.sunPathRotation);
                MemoryUtil.memPutFloat(addr, vsd.x);
                MemoryUtil.memPutFloat(addr + 4, vsd.y);
                MemoryUtil.memPutFloat(addr + 8, vsd.z);
                return;
            }
            if ("view_moon_dir".equals(name)) {
                net.coderbot.iris.vendored.joml.Vector3f vmd = OculusExtendedUniforms.getViewMoonDir(OculusExtendedUniforms.sunPathRotation);
                MemoryUtil.memPutFloat(addr, vmd.x);
                MemoryUtil.memPutFloat(addr + 4, vmd.y);
                MemoryUtil.memPutFloat(addr + 8, vmd.z);
                return;
            }
            if ("view_light_dir".equals(name)) {
                net.coderbot.iris.vendored.joml.Vector3f vld = OculusExtendedUniforms.getViewLightDir(OculusExtendedUniforms.sunPathRotation);
                MemoryUtil.memPutFloat(addr, vld.x);
                MemoryUtil.memPutFloat(addr + 4, vld.y);
                MemoryUtil.memPutFloat(addr + 8, vld.z);
                return;
            }
            if ("view_up_dir".equals(name)) {
                net.coderbot.iris.vendored.joml.Vector3f vud = OculusExtendedUniforms.getViewUpDir();
                MemoryUtil.memPutFloat(addr, vud.x);
                MemoryUtil.memPutFloat(addr + 4, vud.y);
                MemoryUtil.memPutFloat(addr + 8, vud.z);
                return;
            }
            if ("view_res".equals(name)) {
                float w = mc.getWindow() != null ? (float) mc.getWindow().getWidth() : 1920f;
                float h = mc.getWindow() != null ? (float) mc.getWindow().getHeight() : 1080f;
                MemoryUtil.memPutFloat(addr, w);
                MemoryUtil.memPutFloat(addr + 4, h);
                return;
            }
            if ("view_pixel_size".equals(name)) {
                float w = mc.getWindow() != null ? (float) mc.getWindow().getWidth() : 1920f;
                float h = mc.getWindow() != null ? (float) mc.getWindow().getHeight() : 1080f;
                MemoryUtil.memPutFloat(addr, 1.0f / Math.max(1f, w));
                MemoryUtil.memPutFloat(addr + 4, 1.0f / Math.max(1f, h));
                return;
            }
            if ("taa_offset".equals(name) || "clouds_offset".equals(name)) {
                MemoryUtil.memPutFloat(addr, 0f);
                MemoryUtil.memPutFloat(addr + 4, 0f);
                return;
            }

            if ("sunAngle".equals(name)) {
                float sa = net.coderbot.iris.uniforms.CelestialUniforms.getSunAngle();
                MemoryUtil.memPutFloat(addr, sa);
                return;
            }
            if ("far".equals(name) || "combined_far".equals(name)) {
                float f = mc.options != null ? mc.options.renderDistance * 16.0f : 256.0f;
                MemoryUtil.memPutFloat(addr, f);
                return;
            }
            if ("near".equals(name) || "combined_near".equals(name)) {
                MemoryUtil.memPutFloat(addr, 0.05f);
                return;
            }
            if ("vxRenderDistance".equals(name)) {
                int rd = (int) (VoxyConfig.CONFIG.sectionRenderDistance * 32);
                MemoryUtil.memPutInt(addr, rd);
                return;
            }
            if ("rainStrength".equals(name) || "rainFactor".equals(name) || "wetness".equals(name)) {
                float r = world != null ? world.getRainLevel(1.0f) : 0f;
                MemoryUtil.memPutFloat(addr, r);
                return;
            }
            if ("screenBrightness".equals(name)) {
                float g = mc.options != null ? (float) mc.options.gamma : 1.0f;
                MemoryUtil.memPutFloat(addr, g);
                return;
            }
            if ("nightVision".equals(name)) {
                float nv = (player != null && player.hasEffect(Effects.NIGHT_VISION)) ? 1.0f : 0.0f;
                MemoryUtil.memPutFloat(addr, nv);
                return;
            }
            if ("blindness".equals(name)) {
                float b = (player != null && player.hasEffect(Effects.BLINDNESS)) ? 1.0f : 0.0f;
                MemoryUtil.memPutFloat(addr, b);
                return;
            }
            if ("frameTimeCounter".equals(name)) {
                float ftc = (float) ((System.currentTimeMillis() % 1000000L) / 1000.0);
                MemoryUtil.memPutFloat(addr, ftc);
                return;
            }
            if ("framemod8".equals(name)) {
                MemoryUtil.memPutFloat(addr, (float) (frameCounter % 8));
                return;
            }
            if ("viewWidth".equals(name)) {
                MemoryUtil.memPutFloat(addr, (float) (mc.getWindow() != null ? mc.getWindow().getWidth() : 1920));
                return;
            }
            if ("viewHeight".equals(name)) {
                MemoryUtil.memPutFloat(addr, (float) (mc.getWindow() != null ? mc.getWindow().getHeight() : 1080));
                return;
            }
            if ("cloudHeight".equals(name)) {
                MemoryUtil.memPutFloat(addr, 192.0f);
                return;
            }
            if ("eyeAltitude".equals(name)) {
                MemoryUtil.memPutFloat(addr, (float) camY);
                return;
            }
            if ("eyeBrightnessM".equals(name) || "eyeBrightnessM2".equals(name) || "eye_skylight".equals(name)) {
                MemoryUtil.memPutFloat(addr, OculusExtendedUniforms.getEyeSkylight());
                return;
            }
            if ("eye_blocklight".equals(name)) {
                MemoryUtil.memPutFloat(addr, OculusExtendedUniforms.getEyeBlocklight());
                return;
            }
            if ("biome_cave".equals(name)) {
                MemoryUtil.memPutFloat(addr, OculusExtendedUniforms.getBiomeCave());
                return;
            }
            if ("biome_may_rain".equals(name)) {
                MemoryUtil.memPutFloat(addr, OculusExtendedUniforms.getBiomeMayRain());
                return;
            }
            if ("biome_may_snow".equals(name)) {
                MemoryUtil.memPutFloat(addr, OculusExtendedUniforms.getBiomeMaySnow());
                return;
            }
            if ("biome_temperature".equals(name)) {
                MemoryUtil.memPutFloat(addr, OculusExtendedUniforms.getBiomeTemperature());
                return;
            }
            if ("biome_humidity".equals(name)) {
                MemoryUtil.memPutFloat(addr, OculusExtendedUniforms.getBiomeHumidity());
                return;
            }
            if ("biome_arid".equals(name)) {
                MemoryUtil.memPutFloat(addr, OculusExtendedUniforms.getBiomeArid());
                return;
            }
            if ("biome_snowy".equals(name)) {
                MemoryUtil.memPutFloat(addr, OculusExtendedUniforms.getBiomeSnowy());
                return;
            }
            if ("biome_taiga".equals(name)) {
                MemoryUtil.memPutFloat(addr, OculusExtendedUniforms.getBiomeTaiga());
                return;
            }
            if ("biome_jungle".equals(name)) {
                MemoryUtil.memPutFloat(addr, OculusExtendedUniforms.getBiomeJungle());
                return;
            }
            if ("biome_swamp".equals(name)) {
                MemoryUtil.memPutFloat(addr, OculusExtendedUniforms.getBiomeSwamp());
                return;
            }
            if ("biome_temperate".equals(name)) {
                MemoryUtil.memPutFloat(addr, OculusExtendedUniforms.getBiomeTemperate());
                return;
            }
            if ("time_noon".equals(name)) {
                MemoryUtil.memPutFloat(addr, OculusExtendedUniforms.getTimeNoon(OculusExtendedUniforms.sunPathRotation));
                return;
            }
            if ("time_sunrise".equals(name)) {
                MemoryUtil.memPutFloat(addr, OculusExtendedUniforms.getTimeSunrise(OculusExtendedUniforms.sunPathRotation));
                return;
            }
            if ("time_sunset".equals(name)) {
                MemoryUtil.memPutFloat(addr, OculusExtendedUniforms.getTimeSunset(OculusExtendedUniforms.sunPathRotation));
                return;
            }
            if ("time_midnight".equals(name)) {
                MemoryUtil.memPutFloat(addr, OculusExtendedUniforms.getTimeMidnight(OculusExtendedUniforms.sunPathRotation));
                return;
            }
            if ("day_factor".equals(name)) {
                MemoryUtil.memPutFloat(addr, OculusExtendedUniforms.getDayFactor(OculusExtendedUniforms.sunPathRotation));
                return;
            }
            if ("world_age".equals(name)) {
                MemoryUtil.memPutFloat(addr, OculusExtendedUniforms.getWorldAge());
                return;
            }
            if ("daylight_cycle_enabled".equals(name)) {
                MemoryUtil.memPutInt(addr, OculusExtendedUniforms.isDaylightCycleEnabled() ? 1 : 0);
                return;
            }
            if ("darknessFactor".equals(name) || "darknessLightFactor".equals(name) || "maxBlindnessDarkness".equals(name)) {
                MemoryUtil.memPutFloat(addr, 0f);
                return;
            }

            if ("worldTime".equals(name)) {
                int wt = world != null ? (int) (world.getDayTime() % 24000L) : 0;
                MemoryUtil.memPutInt(addr, wt);
                return;
            }
            if ("worldDay".equals(name)) {
                int wd = world != null ? (int) (world.getDayTime() / 24000L) : 0;
                MemoryUtil.memPutInt(addr, wd);
                return;
            }
            if ("moonPhase".equals(name)) {
                int mp = world != null ? world.getMoonPhase() : 0;
                MemoryUtil.memPutInt(addr, mp);
                return;
            }
            if ("frameCounter".equals(name)) {
                MemoryUtil.memPutInt(addr, frameCounter);
                return;
            }
            if ("isEyeInWater".equals(name)) {
                int iei = (cam != null && cam.getFluidInCamera().is(FluidTags.WATER)) ? 1 : 0;
                MemoryUtil.memPutInt(addr, iei);
                return;
            }
            if ("eyeBrightness".equals(name)) {
                int block = 15;
                int sky = 15;
                if (mc.level != null && cam != null) {
                    block = mc.level.getBrightness(net.minecraft.world.LightType.BLOCK, cam.getBlockPosition());
                    sky = mc.level.getBrightness(net.minecraft.world.LightType.SKY, cam.getBlockPosition());
                }
                MemoryUtil.memPutInt(addr, block * 16);
                MemoryUtil.memPutInt(addr + 4, sky * 16);
                return;
            }

            // Fallback for remaining int / float
            if (type == UniformType.INT) {
                MemoryUtil.memPutInt(addr, 0);
            } else if (type == UniformType.FLOAT) {
                MemoryUtil.memPutFloat(addr, 0f);
            }
        };
    }
}
