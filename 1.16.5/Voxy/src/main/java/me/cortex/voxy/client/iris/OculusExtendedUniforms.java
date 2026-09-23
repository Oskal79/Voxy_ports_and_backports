package me.cortex.voxy.client.iris;

import net.coderbot.iris.gl.uniform.UniformHolder;
import net.coderbot.iris.gl.uniform.UniformUpdateFrequency;
import net.coderbot.iris.shaderpack.PackDirectives;
import net.coderbot.iris.uniforms.CapturedRenderingState;
import net.coderbot.iris.vendored.joml.Vector2f;
import net.coderbot.iris.vendored.joml.Vector3f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraft.util.math.vector.Vector4f;
import net.minecraft.world.GameRules;
import net.minecraft.world.LightType;
import net.minecraft.world.biome.Biome;

public class OculusExtendedUniforms {
    public static float sunPathRotation = 0.0f;

    public static Vector3f getSunDir(float rot) {
        Minecraft mc = Minecraft.getInstance();
        float skyAngle = (mc.level != null)
                ? mc.level.getTimeOfDay(CapturedRenderingState.INSTANCE.getTickDelta())
                : 0f;
        Vector4f v = new Vector4f(0.0F, 1.0F, 0.0F, 0.0F);
        Matrix4f mat = new Matrix4f();
        mat.setIdentity();
        mat.multiply(net.minecraft.util.math.vector.Vector3f.XP.rotationDegrees(-90.0F));
        mat.multiply(net.minecraft.util.math.vector.Vector3f.ZP.rotationDegrees(rot));
        mat.multiply(net.minecraft.util.math.vector.Vector3f.YP.rotationDegrees(skyAngle * 360.0F));
        v.transform(mat);
        net.minecraft.util.math.vector.Vector3f dir = new net.minecraft.util.math.vector.Vector3f(v.x(), v.y(), v.z());
        dir.normalize();
        return new Vector3f(dir.x(), dir.y(), dir.z());
    }

    public static Vector3f getMoonDir(float rot) {
        Vector3f s = getSunDir(rot);
        return new Vector3f(-s.x, -s.y, -s.z);
    }

    public static Vector3f getLightDir(float rot) {
        Vector3f s = getSunDir(rot);
        return s.y > 0.0f ? s : new Vector3f(-s.x, -s.y, -s.z);
    }

    public static Vector3f getViewSunDir(float rot) {
        Vector3f s = getSunDir(rot);
        Matrix4f gmv = CapturedRenderingState.INSTANCE.getGbufferModelView();
        if (gmv != null) {
            Vector4f v = new Vector4f(s.x, s.y, s.z, 0.0f);
            v.transform(gmv);
            net.minecraft.util.math.vector.Vector3f dir = new net.minecraft.util.math.vector.Vector3f(v.x(), v.y(), v.z());
            dir.normalize();
            return new Vector3f(dir.x(), dir.y(), dir.z());
        }
        return s;
    }

    public static Vector3f getViewMoonDir(float rot) {
        Vector3f vs = getViewSunDir(rot);
        return new Vector3f(-vs.x, -vs.y, -vs.z);
    }

    public static Vector3f getViewLightDir(float rot) {
        Vector3f s = getSunDir(rot);
        Vector3f vs = getViewSunDir(rot);
        return s.y > 0.0f ? vs : new Vector3f(-vs.x, -vs.y, -vs.z);
    }

    public static Vector3f getViewUpDir() {
        Matrix4f gmv = CapturedRenderingState.INSTANCE.getGbufferModelView();
        if (gmv != null) {
            Vector4f v = new Vector4f(0.0f, 1.0f, 0.0f, 0.0f);
            v.transform(gmv);
            net.minecraft.util.math.vector.Vector3f dir = new net.minecraft.util.math.vector.Vector3f(v.x(), v.y(), v.z());
            dir.normalize();
            return new Vector3f(dir.x(), dir.y(), dir.z());
        }
        return new Vector3f(0f, 1f, 0f);
    }

    public static float getTimeSunrise(float rot) {
        Vector3f s = getSunDir(rot);
        float me_fade = s.y < 0.18f ? 0.37f + 1.2f * Math.max(0.0f, -s.y) : 1.7f;
        float me_weight = (float) Math.pow(Math.max(0.0f, Math.min(1.0f, 1.0f - me_fade * Math.abs(s.y - 0.18f))), 2.0);
        return (s.x > 0.0f ? 1.0f : 0.0f) * me_weight;
    }

    public static float getTimeNoon(float rot) {
        Vector3f s = getSunDir(rot);
        float me_fade = s.y < 0.18f ? 0.37f + 1.2f * Math.max(0.0f, -s.y) : 1.7f;
        float me_weight = (float) Math.pow(Math.max(0.0f, Math.min(1.0f, 1.0f - me_fade * Math.abs(s.y - 0.18f))), 2.0);
        return (s.y > 0.0f ? 1.0f : 0.0f) * (1.0f - me_weight);
    }

    public static float getTimeMidnight(float rot) {
        Vector3f s = getSunDir(rot);
        float me_fade = s.y < 0.18f ? 0.37f + 1.2f * Math.max(0.0f, -s.y) : 1.7f;
        float me_weight = (float) Math.pow(Math.max(0.0f, Math.min(1.0f, 1.0f - me_fade * Math.abs(s.y - 0.18f))), 2.0);
        return (s.y < 0.0f ? 1.0f : 0.0f) * (1.0f - me_weight);
    }

    public static float getTimeSunset(float rot) {
        Vector3f s = getSunDir(rot);
        float me_fade = s.y < 0.18f ? 0.37f + 1.2f * Math.max(0.0f, -s.y) : 1.7f;
        float me_weight = (float) Math.pow(Math.max(0.0f, Math.min(1.0f, 1.0f - me_fade * Math.abs(s.y - 0.18f))), 2.0);
        return (s.x < 0.0f ? 1.0f : 0.0f) * me_weight;
    }

    public static float getDayFactor(float rot) {
        Vector3f s = getSunDir(rot);
        float u = Math.max(0.0f, Math.min(1.0f, s.y * 10.0f));
        return u * u * (3.0f - 2.0f * u);
    }

    public static float getWorldAge() {
        Minecraft mc = Minecraft.getInstance();
        long dayTime = mc.level != null ? mc.level.getDayTime() : 0L;
        long worldDay = dayTime / 24000L;
        long worldTime = dayTime % 24000L;
        return (float) (((worldDay % 128L) * 24000.0 + worldTime) / 20.0);
    }

    public static boolean isDaylightCycleEnabled() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
    }

    public static float getEyeSkylight() {
        Minecraft mc = Minecraft.getInstance();
        ActiveRenderInfo cam = mc.gameRenderer != null ? mc.gameRenderer.getMainCamera() : null;
        if (mc.level != null && cam != null) {
            return mc.level.getBrightness(LightType.SKY, cam.getBlockPosition()) / 15.0f;
        }
        return 1.0f;
    }

    public static float getEyeBlocklight() {
        Minecraft mc = Minecraft.getInstance();
        ActiveRenderInfo cam = mc.gameRenderer != null ? mc.gameRenderer.getMainCamera() : null;
        if (mc.level != null && cam != null) {
            return mc.level.getBrightness(LightType.BLOCK, cam.getBlockPosition()) / 15.0f;
        }
        return 0.0f;
    }

    public static Biome getCurrentBiome() {
        Minecraft mc = Minecraft.getInstance();
        PlayerEntity p = mc.player;
        if (mc.level != null && p != null) {
            return mc.level.getBiome(p.blockPosition());
        }
        return null;
    }

    public static float getBiomeTemperature() {
        Minecraft mc = Minecraft.getInstance();
        PlayerEntity p = mc.player;
        Biome b = getCurrentBiome();
        if (b != null && p != null) {
            return b.getTemperature(p.blockPosition());
        }
        return 0.5f;
    }

    public static float getBiomeHumidity() {
        Biome b = getCurrentBiome();
        return b != null ? b.getDownfall() : 0.5f;
    }

    public static float getBiomeMayRain() {
        Biome b = getCurrentBiome();
        return (b != null && b.getPrecipitation() == Biome.RainType.RAIN) ? 1.0f : 0.0f;
    }

    public static float getBiomeMaySnow() {
        Biome b = getCurrentBiome();
        return (b != null && b.getPrecipitation() == Biome.RainType.SNOW) ? 1.0f : 0.0f;
    }

    public static float getBiomeArid() {
        Biome b = getCurrentBiome();
        if (b == null) return 0.0f;
        Biome.Category c = b.getBiomeCategory();
        return (c == Biome.Category.DESERT || c == Biome.Category.MESA || c == Biome.Category.SAVANNA) ? 1.0f : 0.0f;
    }

    public static float getBiomeSnowy() {
        Biome b = getCurrentBiome();
        if (b == null) return 0.0f;
        return b.getBiomeCategory() == Biome.Category.ICY ? 1.0f : 0.0f;
    }

    public static float getBiomeTaiga() {
        Biome b = getCurrentBiome();
        if (b == null) return 0.0f;
        return b.getBiomeCategory() == Biome.Category.TAIGA ? 1.0f : 0.0f;
    }

    public static float getBiomeJungle() {
        Biome b = getCurrentBiome();
        if (b == null) return 0.0f;
        return b.getBiomeCategory() == Biome.Category.JUNGLE ? 1.0f : 0.0f;
    }

    public static float getBiomeSwamp() {
        Biome b = getCurrentBiome();
        if (b == null) return 0.0f;
        return b.getBiomeCategory() == Biome.Category.SWAMP ? 1.0f : 0.0f;
    }

    public static float getBiomeTemperate() {
        if (getBiomeArid() > 0f || getBiomeSnowy() > 0f || getBiomeTaiga() > 0f || getBiomeJungle() > 0f || getBiomeSwamp() > 0f) {
            return 0.0f;
        }
        return 1.0f;
    }

    public static float getBiomeCave() {
        Minecraft mc = Minecraft.getInstance();
        ActiveRenderInfo cam = mc.gameRenderer != null ? mc.gameRenderer.getMainCamera() : null;
        float camY = cam != null ? (float) cam.getPosition().y : 64f;
        return Math.max(0.0f, Math.min(1.0f, (63.0f - camY) / 13.0f)) * (1.0f - getEyeSkylight());
    }

    public static void addExtendedUniforms(UniformHolder uniforms, PackDirectives directives) {
        if (directives != null) {
            sunPathRotation = directives.getSunPathRotation();
        }
        final float rot = sunPathRotation;

        uniforms.uniform2f(UniformUpdateFrequency.PER_FRAME, "view_res", () -> {
            Minecraft mc = Minecraft.getInstance();
            float w = mc.getWindow() != null ? (float) mc.getWindow().getWidth() : 1920f;
            float h = mc.getWindow() != null ? (float) mc.getWindow().getHeight() : 1080f;
            return new Vector2f(w, h);
        });

        uniforms.uniform2f(UniformUpdateFrequency.PER_FRAME, "view_pixel_size", () -> {
            Minecraft mc = Minecraft.getInstance();
            float w = mc.getWindow() != null ? (float) mc.getWindow().getWidth() : 1920f;
            float h = mc.getWindow() != null ? (float) mc.getWindow().getHeight() : 1080f;
            return new Vector2f(1.0f / Math.max(1f, w), 1.0f / Math.max(1f, h));
        });

        uniforms.uniform2f(UniformUpdateFrequency.PER_FRAME, "taa_offset", () -> new Vector2f(0f, 0f));
        uniforms.uniform2f(UniformUpdateFrequency.PER_FRAME, "clouds_offset", () -> new Vector2f(0f, 0f));

        uniforms.uniform3f(UniformUpdateFrequency.PER_FRAME, "sun_dir", () -> getSunDir(rot));
        uniforms.uniform3f(UniformUpdateFrequency.PER_FRAME, "moon_dir", () -> getMoonDir(rot));
        uniforms.uniform3f(UniformUpdateFrequency.PER_FRAME, "light_dir", () -> getLightDir(rot));
        uniforms.uniform3f(UniformUpdateFrequency.PER_FRAME, "view_sun_dir", () -> getViewSunDir(rot));
        uniforms.uniform3f(UniformUpdateFrequency.PER_FRAME, "view_moon_dir", () -> getViewMoonDir(rot));
        uniforms.uniform3f(UniformUpdateFrequency.PER_FRAME, "view_light_dir", () -> getViewLightDir(rot));
        uniforms.uniform3f(UniformUpdateFrequency.PER_FRAME, "view_up_dir", OculusExtendedUniforms::getViewUpDir);

        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "time_sunrise", () -> getTimeSunrise(rot));
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "time_noon", () -> getTimeNoon(rot));
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "time_sunset", () -> getTimeSunset(rot));
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "time_midnight", () -> getTimeMidnight(rot));
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "day_factor", () -> getDayFactor(rot));

        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "world_age", OculusExtendedUniforms::getWorldAge);
        uniforms.uniform1b(UniformUpdateFrequency.PER_FRAME, "world_age_changed", () -> true);
        uniforms.uniform1b(UniformUpdateFrequency.PER_FRAME, "daylight_cycle_enabled", OculusExtendedUniforms::isDaylightCycleEnabled);

        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "eye_skylight", OculusExtendedUniforms::getEyeSkylight);
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "eye_blocklight", OculusExtendedUniforms::getEyeBlocklight);
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "moon_phase_brightness", () -> 1.0f);
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "atmosphere_saturation_boost_amount", () -> 1.10f);
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "desert_sandstorm", () -> 0.0f);
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "lightning_flash_of", () -> 0.0f);
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "lightning_flash_iris", () -> 0.0f);

        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "biome_cave", OculusExtendedUniforms::getBiomeCave);
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "biome_temperate", OculusExtendedUniforms::getBiomeTemperate);
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "biome_arid", OculusExtendedUniforms::getBiomeArid);
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "biome_snowy", OculusExtendedUniforms::getBiomeSnowy);
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "biome_taiga", OculusExtendedUniforms::getBiomeTaiga);
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "biome_jungle", OculusExtendedUniforms::getBiomeJungle);
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "biome_swamp", OculusExtendedUniforms::getBiomeSwamp);
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "biome_may_rain", OculusExtendedUniforms::getBiomeMayRain);
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "biome_may_snow", OculusExtendedUniforms::getBiomeMaySnow);
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "biome_may_sandstorm", () -> 0.0f);
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "biome_temperature", OculusExtendedUniforms::getBiomeTemperature);
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "biome_humidity", OculusExtendedUniforms::getBiomeHumidity);
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "biome_pale_garden", () -> 0.0f);

        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "combined_near", () -> 0.05f);
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "combined_far", () -> {
            Minecraft mc = Minecraft.getInstance();
            return (mc.options != null ? mc.options.renderDistance * 16.0f : 256.0f);
        });
    }
}
