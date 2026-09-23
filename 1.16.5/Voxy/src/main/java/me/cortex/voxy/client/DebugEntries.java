package me.cortex.voxy.client;

import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Voxy's F3 debug output.
 * <p>
 * 1.21.1 has no {@code DebugScreenEntries} registry -- that whole API (entries, groups, per-entry
 * enable state, profiles) arrived in the 26.x line. NeoForge's equivalent here is
 * {@link CustomizeGuiOverlayEvent.DebugText}, which just hands you the F3 text columns.
 * <p>
 * Consequences of that, versus the 26.1.2 build:
 * <ul>
 *   <li>The short version line is always shown while F3 is open (26.x force-enabled it too).</li>
 *   <li>The detailed instance/renderer dump has no per-entry toggle to hang off, so it is gated on
 *       {@code -Dvoxy.debugHud=true} instead of a debug-screen entry.</li>
 *   <li>GPU timing (the old {@code voxy:gpu_debug} entry) is gated on {@code -Dvoxy.gpuDebug=true},
 *       applied lazily here since there is no rebuild callback to hook.</li>
 * </ul>
 */
public class DebugEntries {
    private static final boolean DETAILED = Boolean.getBoolean("voxy.debugHud");
    private static boolean previousGpuDebugEnabled = false;

    /** Wired from {@link me.cortex.voxy.neoforge.VoxyNeoForgeClient} on the game event bus. */
    public static void onDebugText(RenderGameOverlayEvent.Text event) {
        syncGpuDebugState();

        List<String> left = event.getLeft();

        if (!VoxyCommon.isAvailable()) {
            left.add(TextFormatting.RED + "voxy-" + VoxyCommon.MOD_VERSION);// installed, not available
            return;
        }

        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            left.add(TextFormatting.YELLOW + "voxy-" + VoxyCommon.MOD_VERSION);// available, no instance
            return;
        }

        VoxyRenderSystem vrs = IVoxyRenderSystemHolder.getNullable();
        left.add((vrs == null ? TextFormatting.DARK_GREEN : TextFormatting.GREEN) + "voxy-" + VoxyCommon.MOD_VERSION);

        if (!DETAILED) {
            return;
        }

        List<String> lines = new ArrayList<>();
        instance.addDebug(lines);
        if (vrs != null) {
            vrs.addDebugInfo(lines);
        }
        left.addAll(lines);
    }

    private static void syncGpuDebugState() {
        boolean want = Boolean.getBoolean("voxy.gpuDebug");
        if (want == previousGpuDebugEnabled) {
            return;
        }
        previousGpuDebugEnabled = want;

        GPUTiming.INSTANCE.setEnabled(want);
        RenderStatistics.enabled = want;
        var renderer = Minecraft.getInstance().levelRenderer;
        if (renderer != null) {
            renderer.allChanged();
        }
    }
}
