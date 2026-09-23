package me.cortex.voxy.platform;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Loader abstraction for the NeoForge port of Voxy -- Minecraft 1.21.1 / NeoForge 21.1 flavour.
 * <p>
 * Replaces the direct {@code net.fabricmc.loader.api.FabricLoader} calls the Fabric build used.
 * NeoForge 21.1 exposes all of this through statics on {@link FMLLoader}; the 26.x line later moved
 * to an {@code FMLLoader.getCurrent()} instance, which is why this file differs between targets.
 * <p>
 * Everything here must stay safe to call from a mixin's static initialiser, which can run before
 * {@link ModList} has been populated -- hence the fallbacks onto FML's loading mod list.
 */
public final class VoxyPlatform {
    public static final String MOD_ID = "voxy";

    private VoxyPlatform() {}

    /** Equivalent of {@code FabricLoader.getInstance().isModLoaded(id)}. */
    public static boolean isModLoaded(String modId) {
        ModList list = ModList.get();
        if (list != null) {
            return list.isLoaded(modId);
        }
        try {
            var loading = FMLLoader.getLoadingModList();
            return loading != null && loading.getModFileById(modId) != null;
        } catch (Throwable ignored) {
            // Not running inside an FML environment at all (standalone tooling entrypoints).
            return false;
        }
    }

    /** Equivalent of {@code FabricLoader.getInstance().getGameDir()}. */
    public static Path getGameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    /** Equivalent of {@code FabricLoader.getInstance().getConfigDir()}. */
    public static Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    /** Equivalent of {@code FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER}. */
    public static boolean isDedicatedServer() {
        try {
            return FMLLoader.getDist().isDedicatedServer();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Voxy's own {@link IModInfo}, or null when Voxy is not running as a mod. */
    public static IModInfo getModInfo() {
        ModList list = ModList.get();
        if (list != null) {
            var container = list.getModContainerById(MOD_ID);
            if (container.isPresent()) {
                return container.get().getModInfo();
            }
        }
        var fileInfo = modFileInfo();
        if (fileInfo == null) {
            return null;
        }
        for (IModInfo info : fileInfo.getMods()) {
            if (MOD_ID.equals(info.getModId())) {
                return info;
            }
        }
        return null;
    }

    private static net.neoforged.fml.loading.moddiscovery.ModFileInfo modFileInfo() {
        try {
            var loading = FMLLoader.getLoadingModList();
            return loading == null ? null : loading.getModFileById(MOD_ID);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Voxy's declared version. */
    public static String getModVersion() {
        IModInfo info = getModInfo();
        return info == null ? null : info.getVersion().toString();
    }

    /**
     * Reads one of Voxy's {@code [mods.modproperties]} entries from {@code neoforge.mods.toml}.
     * NeoForge stand-in for {@code fabric.mod.json}'s {@code custom} block.
     */
    public static String getModProperty(String key) {
        IModInfo info = getModInfo();
        if (info == null) {
            return null;
        }
        Object value = info.getModProperties().get(key);
        return value == null ? null : value.toString();
    }

    /**
     * Walks the content roots of Voxy's own mod file, used by the config serialiser to enumerate its
     * classes. Equivalent of Fabric's {@code ModContainer#getRootPaths()}.
     * <p>
     * On NeoForge 21.1 {@code SecureJar#getRootPath()} already hands back a path *inside* the jar's
     * filesystem, so it can be walked directly. (The 26.x line replaced SecureJar with JarContents,
     * which returns the jar file itself and needs opening as a zip filesystem first.)
     */
    public static void forEachModRoot(Consumer<Path> action) {
        var fileInfo = modFileInfo();
        if (fileInfo == null) {
            return;
        }
        Path root;
        try {
            root = fileInfo.getFile().getSecureJar().getRootPath();
        } catch (Throwable t) {
            me.cortex.voxy.common.Logger.error("Could not resolve voxy's mod root for class scanning", t);
            return;
        }
        if (root != null && Files.exists(root)) {
            action.accept(root);
        }
    }
}
