package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import com.mojang.blaze3d.systems.RenderSystem;

/**
 * Keeps vanilla fog from swallowing Voxy's LoD terrain.
 * <p>
 * The 26.x build mixed into {@code net.minecraft.client.renderer.fog.FogRenderer#setupFog} and edited
 * the returned {@code FogData}. Neither that package nor {@code FogData} exists in 1.21.1 -- fog is
 * plain {@code RenderSystem} state there -- but NeoForge exposes the same control point as
 * {@link ViewportEvent.RenderFog}, which is a cleaner hook than mixing in anyway.
 */
public class VoxyFog {
    /** Distance to push fog out to; mirrors the sentinel the 26.x mixin wrote. */
    private static final float PUSHED_OUT = 99999999.0f;

    /** Mirrors the 26.x mixin's renderDistanceStart/End sentinel. */
    private static final float RENDER_DISTANCE_PUSHED_OUT = 999999999.0f;

    /** Fog this tight is deliberate (lava, powder snow, blindness), so leave it alone. */
    private static final float DAMN_CLOSE = 10.0f;

    public static void onRenderFog(EntityViewRenderEvent.RenderFogEvent event) {
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) {
            return;
        }
        if (IVoxyRenderSystemHolder.getNullable() == null) {
            return;
        }

        // FOG_TERRAIN is 1.21.1's render-distance fog (start = far - f, end = far). The 26.x mixin
        // pushed renderDistanceStart/End out to 999999999 *unconditionally*, so that LoD terrain
        // drawn beyond the vanilla render distance is not fogged away. Without this the vanilla fog
        // wall stays and hides everything Voxy draws.
        if (event.getType() == FogRenderer.FogType.FOG_TERRAIN) {
            // RenderFogEvent is not cancelable on 1.16.5; it fires after FogRenderer has already
            // programmed the fixed-function fog, so simply overwriting the values here is both
            // sufficient and the only option.
            RenderSystem.fogStart(RENDER_DISTANCE_PUSHED_OUT);
            RenderSystem.fogEnd(RENDER_DISTANCE_PUSHED_OUT);
            return;
        }

        // FOG_SKY is the environmental fog, which stays configurable.
        boolean fogIsDamnClose = event.getFarPlaneDistance() < DAMN_CLOSE;
        if (!VoxyConfig.CONFIG.useEnvironmentalFog && !fogIsDamnClose) {
            RenderSystem.fogStart(PUSHED_OUT);
            RenderSystem.fogEnd(PUSHED_OUT);
        }
    }
}
