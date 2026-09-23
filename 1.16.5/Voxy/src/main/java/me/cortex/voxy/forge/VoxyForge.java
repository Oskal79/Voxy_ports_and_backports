package me.cortex.voxy.forge;

import me.cortex.voxy.client.DebugEntries;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.VoxyCommands;
import me.cortex.voxy.client.VoxyFog;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Forge entrypoint for the 1.16.5 port.
 * <p>
 * Forge 36.x constructs a single {@code @Mod} class with a no-arg constructor and hands out the
 * mod event bus through {@link FMLJavaModLoadingContext}, rather than NeoForge's
 * {@code (IEventBus, ModContainer)} constructor -- so the split into separate common and client
 * entrypoints collapses into one class with the client half guarded by {@link DistExecutor}.
 * <p>
 * The config-screen extension point is not wired here: on this line it would hang off Embeddium's
 * video-settings screen, which is part of the deferred Embeddium GUI layer.
 */
@Mod("voxy")
public class VoxyForge {
    public VoxyForge() {
        // Fabric ran VoxyCommon's <clinit> by virtue of it being the entrypoint; do it explicitly.
        VoxyCommon.bootstrap();

        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> VoxyForge::initClientSide);
    }

    private static void initClientSide() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(VoxyForge::onClientSetup);

        // Fabric used ClientCommandRegistrationCallback; 1.16.5 Forge has no client-command event,
        // so voxy's commands are registered against the normal command dispatcher instead.
        MinecraftForge.EVENT_BUS.addListener(VoxyForge::onRegisterCommands);
        // F3 debug text.
        MinecraftForge.EVENT_BUS.addListener(DebugEntries::onDebugText);
        // Fog: 1.16.5 is still fixed-function, driven through this Forge event.
        //
        // LOWEST priority so this runs *last*. Other fog mods (Dynamic Surroundings and friends)
        // listen to the same event, and whoever writes last wins -- at default priority they were
        // overwriting voxy's push-out with a ~79 block fog end, which made the LoD composite decide
        // fog hid everything and throw the whole frame away.
        MinecraftForge.EVENT_BUS.addListener(
                net.minecraftforge.eventbus.api.EventPriority.LOWEST, VoxyFog::onRenderFog);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(VoxyClient::initClient);
    }

    private static void onRegisterCommands(net.minecraftforge.event.RegisterCommandsEvent event) {
        if (VoxyCommon.isAvailable()) {
            event.getDispatcher().register(VoxyCommands.register());
        }
    }
}
