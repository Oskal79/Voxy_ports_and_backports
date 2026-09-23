package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.commonImpl.VoxyCommon;

public class ClientSessionEvents {
    public static boolean inSession = false;

    public static void sessionStart() {
        if (inSession) throw new IllegalStateException("Cannot start new session while in a session");
        inSession = true;

        //Should never try creating multiple instances via session start
        if (VoxyCommon.getInstance() != null) throw new IllegalStateException();

        if (VoxyCommon.isAvailable()) {
            if (VoxyConfig.CONFIG.enabled) {
                VoxyCommon.createInstance();

                // On 1.16.5 the world renderer is set up *before* the session starts:
                // WorldRenderer#setLevel (and the allChanged it triggers) run inside handleLogin,
                // while sessionStart only fires when handleLogin returns. Both earlier attempts to
                // build the render system therefore bail out -- first with no world identifier,
                // then with no instance -- and nothing tried again once the instance existed.
                // Newer versions get a second chance because Sodium reloads its renderer later.
                var holder = me.cortex.voxy.client.core.IVoxyRenderSystemHolder.getNullableHolder();
                if (holder != null && holder.voxy$getRenderSystem() == null) {
                    me.cortex.voxy.common.Logger.info(
                            "[voxy-diag] session started, creating render system now");
                    holder.voxy$createRenderer();
                }
            }
        }
    }

    public static void sessionEnd() {
        if (!inSession) throw new IllegalStateException("Cannot end a session while not in a session");
        inSession = false;

        VoxyCommon.shutdownInstance();
    }
}
