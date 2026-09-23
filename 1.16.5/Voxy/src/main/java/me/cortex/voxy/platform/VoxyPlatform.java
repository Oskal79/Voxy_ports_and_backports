package me.cortex.voxy.platform;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.function.Consumer;

/**
 * Loader abstraction for the Forge port of Voxy -- Minecraft 1.16.5 / Forge 36.2 flavour.
 * <p>
 * Same contract as the NeoForge build's VoxyPlatform, against the older {@code net.minecraftforge}
 * package and API. Everything must stay safe to call from a mixin's static initialiser, which can
 * run before {@link ModList} exists, hence the fallbacks onto FML's loading mod list.
 */
public final class VoxyPlatform {
    public static final String MOD_ID = "voxy";

    private VoxyPlatform() {}

    public static boolean isModLoaded(String modId) {
        ModList list = ModList.get();
        if (list != null) {
            return list.isLoaded(modId);
        }
        try {
            return FMLLoader.getLoadingModList() != null
                    && FMLLoader.getLoadingModList().getModFileById(modId) != null;
        } catch (Throwable ignored) {
            // Not inside an FML environment at all (standalone tooling entrypoints).
            return false;
        }
    }

    public static Path getGameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    public static Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    public static boolean isDedicatedServer() {
        try {
            return FMLLoader.getDist().isDedicatedServer();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static IModInfo getModInfo() {
        ModList list = ModList.get();
        if (list != null && list.getModContainerById(MOD_ID).isPresent()) {
            return list.getModContainerById(MOD_ID).get().getModInfo();
        }
        ModFileInfo fileInfo = modFileInfo();
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

    private static ModFileInfo modFileInfo() {
        try {
            return FMLLoader.getLoadingModList() == null
                    ? null : FMLLoader.getLoadingModList().getModFileById(MOD_ID);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String getModVersion() {
        IModInfo info = getModInfo();
        return info == null ? null : info.getVersion().toString();
    }

    /** Reads one of Voxy's {@code [modproperties.voxy]} entries from {@code mods.toml}. */
    public static String getModProperty(String key) {
        IModInfo info = getModInfo();
        if (info == null) {
            return null;
        }
        Object value = info.getModProperties().get(key);
        return value == null ? null : value.toString();
    }

    /**
     * Walks the content root of Voxy's own mod file, used by the config serialiser to enumerate
     * its classes.
     * <p>
     * 1.16.5's Forge predates SecureJar, so {@code ModFile#getFilePath} hands back the jar file
     * itself rather than a path inside it. It therefore has to be opened as a zip filesystem
     * first -- walking the returned path directly would just see a single regular file and find
     * no classes at all. In a dev/exploded run the path is already a directory and is walked
     * as-is.
     */
    public static void forEachModRoot(Consumer<Path> action) {
        ModFileInfo fileInfo = modFileInfo();
        Path file;
        if (fileInfo != null) {
            file = fileInfo.getFile().getFilePath();
        } else {
            ModList list = ModList.get();
            if (list == null) {
                return;
            }
            ModFileInfo byId = list.getModFileById(MOD_ID);
            if (byId == null) {
                return;
            }
            file = byId.getFile().getFilePath();
        }
        if (file == null || !Files.exists(file)) {
            return;
        }
        if (Files.isDirectory(file)) {
            action.accept(file);
            return;
        }
        try (FileSystem fs = FileSystems.newFileSystem(
                URI.create("jar:" + file.toUri()), Collections.<String, Object>emptyMap())) {
            for (Path root : fs.getRootDirectories()) {
                action.accept(root);
            }
        } catch (Throwable t) {
            me.cortex.voxy.common.Logger.error("Could not open voxy's mod jar for class scanning", t);
        }
    }
}
