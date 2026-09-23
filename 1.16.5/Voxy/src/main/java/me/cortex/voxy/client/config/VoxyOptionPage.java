package me.cortex.voxy.client.config;

import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.jellysquid.mods.sodium.client.gui.options.Option;
import me.jellysquid.mods.sodium.client.gui.options.OptionGroup;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpact;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpl;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import me.jellysquid.mods.sodium.client.gui.options.control.ControlValueFormatter;
import me.jellysquid.mods.sodium.client.gui.options.control.SliderControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * Voxy's page inside Embeddium's video-settings screen.
 * <p>
 * The 1.21.1 build registers options through modern Sodium's {@code ConfigManager} /
 * {@code structure.OptionPage} API, which Embeddium 0.3.18 predates entirely -- this line still
 * uses the classic {@code OptionImpl.createBuilder(...).setBinding(...)} form and has no
 * registration hook at all, so the page is appended by mixin (see MixinSodiumOptionsGUI).
 * <p>
 * Options that change what the renderer builds recreate it on apply, since Embeddium's own
 * REQUIRES_RENDERER_RELOAD flag only reloads Embeddium's terrain, not voxy's.
 */
public class VoxyOptionPage {
    /** Adapts VoxyConfig to Embeddium's option storage contract. */
    private static final OptionStorage<VoxyConfig> STORAGE = new OptionStorage<VoxyConfig>() {
        @Override
        public VoxyConfig getData() {
            return VoxyConfig.CONFIG;
        }

        @Override
        public void save() {
            VoxyConfig.CONFIG.save();
        }
    };

    /** Push a changed LoD distance into the live renderer; it was previously constructor-only. */
    private static void applyRenderDistance() {
        Minecraft.getInstance().execute(() -> {
            var rs = IVoxyRenderSystemHolder.getNullable();
            if (rs != null) {
                rs.setRenderDistance(VoxyConfig.CONFIG.sectionRenderDistance);
            }
        });
    }

    private static void recreateRenderer() {
        Minecraft.getInstance().execute(() -> {
            var holder = IVoxyRenderSystemHolder.getNullableHolder();
            if (holder != null) {
                holder.voxy$shutdownRenderer();
                holder.voxy$createRenderer();
            }
        });
    }

    public static OptionPage create() {
        List<OptionGroup> groups = new ArrayList<>();

        // First on the page deliberately: Embeddium 0.3.18 clips an over-long option page instead of
        // scrolling it, so anything added at the bottom can end up unreachable.
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, STORAGE)
                        .setName(new StringTextComponent("Debug: render stage"))
                        .setTooltip(new StringTextComponent(
                                "Bisects the LoD render pass. 0 = normal. 1 = render LoDs but never "
                                        + "composite them into Minecraft's buffers. 2 = composite colour "
                                        + "but never write depth. 3 = skip the pass entirely. Can also be "
                                        + "forced with -Dvoxy.debugStage=N. Leave at 0 unless asked."))
                        .setControl(o -> new SliderControl((Option<Integer>) o, 0, 3, 1,
                                ControlValueFormatter.number()))
                        .setBinding((cfg, v) -> cfg.debugRenderStage = v, cfg -> cfg.debugRenderStage)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, STORAGE)
                        .setName(new StringTextComponent("Debug: no occlusion cull"))
                        .setTooltip(new StringTextComponent(
                                "Stops voxy hiding LoD nodes it believes are behind other geometry. "
                                        + "If a dark area is a section that was wrongly culled, it "
                                        + "comes back as normal terrain with this on."))
                        .setControl(TickBoxControl::new)
                        .setBinding((cfg, v) -> { cfg.debugNoHizCull = v; recreateRenderer(); },
                                cfg -> cfg.debugNoHizCull)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, STORAGE)
                        .setName(new StringTextComponent("Debug: LoD light view"))
                        .setTooltip(new StringTextComponent(
                                "Renders the stored per-voxel light instead of the block texture. "
                                        + "Red = sky light, green = block light, both scaled 0-15. "
                                        + "Correctly lit ground is bright red day or night; anything "
                                        + "stored unlit shows black."))
                        .setControl(TickBoxControl::new)
                        .setBinding((cfg, v) -> { cfg.debugLightView = v; recreateRenderer(); },
                                cfg -> cfg.debugLightView)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, STORAGE)
                        .setName(new StringTextComponent("Debug: LoD full bright"))
                        .setTooltip(new StringTextComponent(
                                "Renders LoD terrain without the lightmap multiply. If a dark patch "
                                        + "lights up with this on, the fault is the stored per-voxel "
                                        + "light; if it stays dark, it is the block colour or tint."))
                        .setControl(TickBoxControl::new)
                        .setBinding((cfg, v) -> { cfg.debugFullBright = v; recreateRenderer(); },
                                cfg -> cfg.debugFullBright)
                        .build())
                .add(OptionImpl.createBuilder(int.class, STORAGE)
                        .setName(new StringTextComponent("Ambient occlusion (SSAO)"))
                        .setTooltip(new StringTextComponent(
                                "Screen-space ambient occlusion over the LoD terrain. 0 = off, "
                                        + "1 = auto (default), 2 = basic, 3 = better, 4 = best. "
                                        + "Basic reconstructs from voxy's own matrices only; better "
                                        + "and best also read Minecraft's depth buffer."))
                        .setControl(o -> new SliderControl((Option<Integer>) o, 0, 4, 1,
                                v -> new String[]{"off", "auto", "basic", "better", "best"}[v]))
                        .setBinding((cfg, v) -> {
                            cfg.setSSAOMode(new me.cortex.voxy.client.core.SSAO.SSAOMode[]{
                                    me.cortex.voxy.client.core.SSAO.SSAOMode.OFF,
                                    me.cortex.voxy.client.core.SSAO.SSAOMode.AUTO,
                                    me.cortex.voxy.client.core.SSAO.SSAOMode.BASIC,
                                    me.cortex.voxy.client.core.SSAO.SSAOMode.BETTER,
                                    me.cortex.voxy.client.core.SSAO.SSAOMode.BEST}[v]);
                            recreateRenderer();
                        }, cfg -> {
                            switch (cfg.getSSAOMode()) {
                                case OFF: return 0;
                                case BASIC: return 2;
                                case BETTER: return 3;
                                case BEST: return 4;
                                default: return 1;
                            }
                        })
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(int.class, STORAGE)
                        .setName(new StringTextComponent("Debug: stop after step"))
                        .setTooltip(new StringTextComponent(
                                "Truncates the LoD pipeline after step N so the step that corrupts "
                                        + "vanilla rendering can be found live. 0 = off. 1-2 = skip the "
                                        + "pipeline. 3 = setup, 4 = renderOpaque, 5 = HiZ/traversal, "
                                        + "6 = buildDrawCalls, 7 = temporal, 8 = postOpaque, 9 = SSAO, "
                                        + "10 = translucent, 11 = composite. 12 = run everything but "
                                        + "skip voxy's own terrain draw. -Dvoxy.debugStopAfter overrides this."))
                        .setControl(o -> new SliderControl((Option<Integer>) o, 0, 12, 1,
                                ControlValueFormatter.number()))
                        .setBinding((cfg, v) -> cfg.debugStopAfter = v, cfg -> cfg.debugStopAfter)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, STORAGE)
                        .setName(new StringTextComponent("Enable Voxy"))
                        .setTooltip(new StringTextComponent(
                                "Master switch. Turning this off stops both world ingest and LoD rendering."))
                        .setControl(TickBoxControl::new)
                        .setBinding((cfg, v) -> { cfg.enabled = v; recreateRenderer(); },
                                cfg -> cfg.enabled)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, STORAGE)
                        .setName(new StringTextComponent("Render LoDs"))
                        .setTooltip(new StringTextComponent(
                                "Draw the level-of-detail terrain. Turning this off keeps ingesting the "
                                        + "world but renders nothing, which is useful for building the LoD "
                                        + "store without the rendering cost."))
                        .setControl(TickBoxControl::new)
                        .setBinding((cfg, v) -> { cfg.enableRendering = v; recreateRenderer(); },
                                cfg -> cfg.enableRendering)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, STORAGE)
                        .setName(new StringTextComponent("Ingest world data"))
                        .setTooltip(new StringTextComponent(
                                "Capture chunks into the LoD store as they load. Off means nothing new is "
                                        + "recorded; already-stored terrain still renders."))
                        .setControl(TickBoxControl::new)
                        .setBinding((cfg, v) -> cfg.ingestEnabled = v, cfg -> cfg.ingestEnabled)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, STORAGE)
                        .setName(new StringTextComponent("LoD render distance"))
                        .setTooltip(new StringTextComponent(
                                "How far LoD terrain is drawn, in sections. Higher values show more distant "
                                        + "terrain at the cost of memory and frame time."))
                        .setControl(o -> new SliderControl((Option<Integer>) o, 2, 128, 2,
                                v -> v + " sections"))
                        .setBinding((cfg, v) -> { cfg.sectionRenderDistance = v; applyRenderDistance(); },
                                cfg -> (int) cfg.sectionRenderDistance)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .add(OptionImpl.createBuilder(int.class, STORAGE)
                        .setName(new StringTextComponent("Subdivision size"))
                        .setTooltip(new StringTextComponent(
                                "Controls how aggressively distant terrain is simplified. Lower values give "
                                        + "more detail further out and cost more."))
                        .setControl(o -> new SliderControl((Option<Integer>) o, 16, 256, 4,
                                ControlValueFormatter.number()))
                        .setBinding((cfg, v) -> cfg.subDivisionSize = v,
                                cfg -> (int) cfg.subDivisionSize)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, STORAGE)
                        .setName(new StringTextComponent("Environmental fog"))
                        .setTooltip(new StringTextComponent(
                                "Apply the world's fog to LoD terrain. Off pushes fog out so distant terrain "
                                        + "stays visible."))
                        .setControl(TickBoxControl::new)
                        .setBinding((cfg, v) -> cfg.useEnvironmentalFog = v, cfg -> cfg.useEnvironmentalFog)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, STORAGE)
                        .setName(new StringTextComponent("Worker threads"))
                        .setTooltip(new StringTextComponent(
                                "Threads used for meshing and storage. Takes effect on the next world load."))
                        .setControl(o -> new SliderControl((Option<Integer>) o, 1, 32, 1,
                                ControlValueFormatter.number()))
                        .setBinding((cfg, v) -> cfg.serviceThreads = v, cfg -> cfg.serviceThreads)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(int.class, STORAGE)
                        .setName(new StringTextComponent("VRAM budget"))
                        .setTooltip(new StringTextComponent(
                                "Cap on voxy's geometry buffer. Upstream reserves 4096 MiB of VRAM "
                                        + "outright, which is far more than most cards can spare. Lower "
                                        + "this if VRAM is tight; raise it if distant terrain stops "
                                        + "loading. Takes effect on the next world load."))
                        .setControl(o -> new SliderControl((Option<Integer>) o, 512, 4096, 256,
                                v -> v + " MiB"))
                        .setBinding((cfg, v) -> cfg.geometryBufferMb = v, cfg -> cfg.geometryBufferMb)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .build());

        return new OptionPage((ITextComponent) new StringTextComponent("Voxy"),
                com.google.common.collect.ImmutableList.copyOf(groups));
    }
}
