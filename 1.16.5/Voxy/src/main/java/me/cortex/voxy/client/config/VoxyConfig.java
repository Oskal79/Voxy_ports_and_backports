package me.cortex.voxy.client.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.platform.VoxyPlatform;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public class VoxyConfig {
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    public static VoxyConfig CONFIG = loadOrCreate();

    public boolean enabled = true;
    public boolean enableRendering = true;
    public boolean ingestEnabled = true;
    public float sectionRenderDistance = 16;
    public int serviceThreads = (int) Math.max(CpuLayout.getCoreCount()/1.5, 1);
    public float subDivisionSize = 64;
    public boolean useEnvironmentalFog = true;
    public boolean dontUseSodiumBuilderThreads = false;
    public String ssaoMode;
    /** Diagnostic: render LoD terrain without the lightmap multiply. */
    public boolean debugFullBright = false;
    /** Diagnostic: render the stored per-voxel light instead of the block colour. */
    public boolean debugLightView = false;
    /** Diagnostic: skip Hi-Z occlusion culling of LoD nodes. */
    public boolean debugNoHizCull = false;
    /**
     * Bisect switch for the LoD render pass (see MixinSodiumWorldRenderer / NormalRenderPipeline).
     * 0 = full pass, 1 = no final composite, 2 = composite colour but never write depth,
     * 3 = skip the pass entirely.
     */
    public int debugRenderStage = 0;
    /** GUI mirror of -Dvoxy.debugStopAfter. 0 = off (run the whole pipeline). */
    public int debugStopAfter = 0;
    /** Hard cap on voxy's geometry buffer, in MiB. Upstream asks for 4 GiB of VRAM outright. */
    public int geometryBufferMb = 1024;

    public SSAO.SSAOMode getSSAOMode() {
        if (this.ssaoMode == null) return SSAO.SSAOMode.AUTO;
        try {
            return SSAO.SSAOMode.valueOf(this.ssaoMode.toUpperCase(Locale.ROOT));
        } catch (Exception e) { return SSAO.SSAOMode.AUTO; }
    }

    public void setSSAOMode(SSAO.SSAOMode mode) {
        this.ssaoMode = mode.name().toLowerCase(Locale.ROOT);
    }


    private static VoxyConfig loadOrCreate() {
        Path path = getConfigPath();
        if (Files.exists(path)) {
            try (FileReader reader = new FileReader(path.toFile())) {
                var conf = GSON.fromJson(reader, VoxyConfig.class);
                if (conf != null) {
                    return conf;
                } else {
                    Logger.error("Failed to load voxy config, resetting");
                }
            } catch (IOException e) {
                Logger.error("Could not load config", e);
            } catch (JsonParseException e) {
                Logger.error("Could not parse config", e);
            }
            Logger.info("Error during config loading, creating new");
        } else {
            Logger.info("Config file doesnt exist, creating new");
        }
        var config = new VoxyConfig();
        config.save();
        return config;
    }

    public void save() {
        try {
            Path path = getConfigPath();
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.write(path, (GSON.toJson(this)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            Logger.error("Failed to write config file", e);
        }
    }

    private static Path getConfigPath() {
        return VoxyPlatform.getConfigDir()
                .resolve("voxy-config.json");
    }

    /**
     * Active bisect stage. {@code -Dvoxy.debugStage=N} overrides the GUI value, so the stage can be
     * forced from the launcher when the config screen is not cooperating.
     */
    public int getDebugRenderStage() {
        Integer override = Integer.getInteger("voxy.debugStage");
        int stage = override != null ? override : this.debugRenderStage;
        return Math.max(0, Math.min(3, stage));
    }

    /**
     * Truncates the LoD pipeline after step N, for bisecting which step corrupts vanilla rendering.
     * -1 (default) runs everything. Steps, in order: 3 = pipeline setup / initDepthStencil,
     * 4 = section renderOpaque, 5 = innerPrimaryWork (HiZ + traversal), 6 = buildDrawCalls,
     * 7 = renderTemporal, 8 = postOpaquePreperation, 9 = SSAO, 10 = renderTranslucent,
     * 11 = final composite. Values 0-2 skip the pipeline entirely.
     * Set with -Dvoxy.debugStopAfter=N.
     */
    public int getDebugStopAfter() {
        Integer v = Integer.getInteger("voxy.debugStopAfter");
        if (v != null) return v;
        return this.debugStopAfter == 0 ? -1 : this.debugStopAfter;
    }

    public boolean isRenderingEnabled() {
        return this.enabled && this.enableRendering;
    }
}
