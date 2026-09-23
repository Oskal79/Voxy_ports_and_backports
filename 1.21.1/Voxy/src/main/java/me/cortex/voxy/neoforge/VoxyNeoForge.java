package me.cortex.voxy.neoforge;

import me.cortex.voxy.commonImpl.VoxyCommon;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * Common (both-dist) NeoForge entrypoint, replacing Fabric's {@code main} entrypoint
 * which pointed straight at {@link VoxyCommon}.
 */
@Mod("voxy")
public class VoxyNeoForge {
    public VoxyNeoForge(IEventBus modBus, ModContainer container) {
        // Fabric ran VoxyCommon's <clinit> by virtue of it being the entrypoint class; do it explicitly here.
        VoxyCommon.bootstrap();
    }
}
