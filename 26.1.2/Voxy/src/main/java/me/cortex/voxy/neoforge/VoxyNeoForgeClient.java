package me.cortex.voxy.neoforge;

import me.cortex.voxy.client.DebugEntries;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.VoxyCommands;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.OptionPage;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterDebugEntriesEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-side NeoForge entrypoint, replacing Fabric's {@code client} entrypoint
 * ({@code VoxyClient implements ClientModInitializer}) and the ModMenu integration.
 */
@Mod(value = "voxy", dist = Dist.CLIENT)
public class VoxyNeoForgeClient {
    public VoxyNeoForgeClient(IEventBus modBus, ModContainer container) {
        // Replaces ModMenuIntegration: makes the "Config" button on NeoForge's mod list open Voxy's
        // page inside Sodium's video settings screen, exactly as the Fabric/ModMenu version did.
        container.registerExtensionPoint(IConfigScreenFactory.class, (minecraft, parent) -> {
            if (!VoxyCommon.isAvailable()) {
                return null;
            }
            OptionPage page = (OptionPage) ConfigManager.CONFIG.getModOptions().stream()
                    .filter(a -> a.configId().equals("voxy"))
                    .findFirst()
                    .orElseThrow()
                    .pages()
                    .get(0);
            return VideoSettingsScreen.createScreen(parent, page);
        });

        modBus.addListener(this::onClientSetup);
        modBus.addListener(this::onRegisterDebugEntries);
        NeoForge.EVENT_BUS.addListener(this::onRegisterClientCommands);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(VoxyClient::initClient);
    }

    private void onRegisterDebugEntries(RegisterDebugEntriesEvent event) {
        DebugEntries.register(event);
    }

    // Fabric used ClientCommandRegistrationCallback; NeoForge fires this on the game bus instead.
    private void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        if (VoxyCommon.isAvailable()) {
            event.getDispatcher().register(VoxyCommands.register());
        }
    }
}
