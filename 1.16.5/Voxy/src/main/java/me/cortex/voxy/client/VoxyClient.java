package me.cortex.voxy.client;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.channels.FileLock;
import java.nio.channels.NonWritableChannelException;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.Function;

public class VoxyClient {
    private static final HashSet<String> FREX = new HashSet<>();
    private static FileLock EXCLUSIVE_LOCK;
    public static void initVoxyClient() {
        Capabilities.init();//Ensure clinit is called

        if (Capabilities.INSTANCE.hasBrokenDepthSampler) {
            Logger.error("AMD broken depth sampler detected, voxy does not work correctly and has been disabled, this will hopefully be fixed in the future");
        }

        boolean systemSupported = Capabilities.INSTANCE.compute && Capabilities.INSTANCE.indirectParameters && !Capabilities.INSTANCE.hasBrokenDepthSampler;
        if (!systemSupported) {
             Logger.error("Voxy is unsupported on your system.");
        }

        if (systemSupported && System.getProperty("voxy.exclusiveLock", "false").equalsIgnoreCase("true")) {
            //Try acquire the lock file
            var vf = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy");
            if (!vf.toFile().isDirectory()) {
                vf.toFile().mkdir();
            }
            try {
                FileOutputStream fis = new FileOutputStream(vf.resolve("voxy.lock").toFile());
                EXCLUSIVE_LOCK = fis.getChannel().lock(0, Long.MAX_VALUE, false);
            } catch (NonWritableChannelException | IOException e) {
                //If some error write to log and unsupport
                Logger.error("Failed to acquire exclusive voxy lock file, mod will be disabled");
                systemSupported = false;
            }

        }

        if (systemSupported) {

            SharedIndexBuffer.INSTANCE.id();

            VoxyCommon.setInstanceFactory(VoxyClientInstance::new);

            if (!Capabilities.INSTANCE.subgroup) {
                Logger.warn("GPU does not support subgroup operations, expect some performance degradation");
            }

        }
    }

    /**
     * Client init. On Fabric this was {@code ClientModInitializer#onInitializeClient}; on NeoForge it
     * is driven from {@link me.cortex.voxy.neoforge.VoxyNeoForgeClient}.
     */
    public static void initClient() {
        // Debug screen entries are registered separately, from RegisterDebugEntriesEvent.
        registerFlawlessFramesHandlers();
    }

    /**
     * Hands Voxy's flawless-frames controller to every mod that asks for one.
     * <p>
     * Fabric discovers those mods through the {@code frex_flawless_frames} entrypoint. NeoForge has no
     * entrypoint list, so -- exactly as Sodium's own NeoForge module does -- requesters declare a
     * {@code frex:flawless_frames_handler} mod property naming a class with a static
     * {@code acceptController(Function)} method, which we invoke with our controller.
     */
    private static void registerFlawlessFramesHandlers() {
        Function<String, Consumer<Boolean>> controller = name -> active -> {
            if (active) {
                FREX.add(name);
            } else {
                FREX.remove(name);
            }
        };

        MethodHandles.Lookup lookup = MethodHandles.lookup();

        for (IModInfo mod : ModList.get().getMods()) {
            Object handler = mod.getModProperties().get("frex:flawless_frames_handler");
            if (!(handler instanceof String handlerClass)) {
                continue;
            }

            try {
                lookup.findStatic(Class.forName(handlerClass), "acceptController",
                                MethodType.methodType(void.class, Function.class))
                        .invoke(controller);
            } catch (Throwable e) {
                Logger.error("Failed to hand the flawless frames controller to mod " + mod.getModId(), e);
            }
        }
    }

    public static boolean isFrexActive() {
        return !FREX.isEmpty();
    }

    //Diagnostic: upstream stubs this to 0. Wired to -Dvoxy.occlusionDebug=<0|1|2|3> so the
    //occlusion-culling stages can be switched off from the launcher without a rebuild.
    //  0 = normal (cull raster runs, frameId advances, draw calls rebuilt every frame)
    //  1 = skip the cull raster and freeze frameId -- the LoD visible set stops changing
    //  2 = also stop rebuilding draw calls
    //Sodium's own chunk rendering is deliberately left on in every mode, so exactly one
    //variable changes between 0 and 1.
    private static final int OCCLUSION_DEBUG =
            Integer.getInteger("voxy.occlusionDebug", 0);

    public static int getOcclusionDebugState() {
        return OCCLUSION_DEBUG;
    }

    public static boolean disableSodiumChunkRender() {
        return false;// getOcclusionDebugState() != 0;
    }
}