package me.cortex.voxy.platform;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModInfo;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Loader abstraction for the NeoForge port of Voxy.
 * <p>
 * Replaces the direct {@code net.fabricmc.loader.api.FabricLoader} calls the Fabric build used.
 * Everything here must stay safe to call from a mixin's static initialiser, which can run before
 * {@link ModList} has been populated -- so each lookup falls back onto FML's loading mod list,
 * which exists from very early in startup.
 */
public final class VoxyPlatform {
    public static final String MOD_ID = "voxy";

    private VoxyPlatform() {}

    /**
     * Equivalent of {@code FabricLoader.getInstance().isModLoaded(id)}.
     * <p>
     * Mixin static initialisers are merged into the target class, so this can be reached during very
     * early class loading while {@code ModList.get()} is still null. In that window NeoForge exposes
     * the mod set through the loading mod list instead.
     */
    public static boolean isModLoaded(String modId) {
        ModList list = ModList.get();
        if (list != null) {
            return list.isLoaded(modId);
        }
        var loader = FMLLoader.getCurrentOrNull();
        if (loader == null) {
            // Not running inside an FML environment at all (standalone tooling entrypoints).
            return false;
        }
        var loading = loader.getLoadingModList();
        return loading != null && loading.getModFileById(modId) != null;
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
        var loader = FMLLoader.getCurrentOrNull();
        return loader != null && loader.getDist().isDedicatedServer();
    }

    /**
     * Voxy's own {@link IModInfo}, or null when Voxy is not running as a mod (the Fabric build
     * treated that case as "running voxy without minecraft").
     */
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
        var loader = FMLLoader.getCurrentOrNull();
        if (loader == null) {
            return null;
        }
        var loading = loader.getLoadingModList();
        return loading == null ? null : loading.getModFileById(MOD_ID);
    }

    /** Voxy's declared version, equivalent of {@code getMetadata().getVersion().getFriendlyString()}. */
    public static String getModVersion() {
        IModInfo info = getModInfo();
        return info == null ? null : info.getVersion().toString();
    }

    /**
     * Reads one of Voxy's {@code [mods.modproperties]} entries from {@code neoforge.mods.toml}.
     * This is the NeoForge stand-in for {@code fabric.mod.json}'s {@code custom} block.
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
     * classes. This is NeoForge's equivalent of Fabric's {@code ModContainer#getRootPaths()}.
     * <p>
     * Unlike Fabric, NeoForge hands back the <em>jar file itself</em> for a packaged mod rather than a
     * path inside the jar's filesystem, so {@code root.resolve("me/cortex/voxy")} would never exist and
     * every config type would be silently missed. Regular files are therefore opened as a zip
     * filesystem and their root directories handed over instead; in a dev run the roots are already
     * exploded directories and are used as-is.
     * <p>
     * The zip filesystem is closed once {@code action} returns, so callers must not retain the Path.
     */
    public static void forEachModRoot(Consumer<Path> action) {
        var fileInfo = modFileInfo();
        if (fileInfo == null) {
            return;
        }
        for (Path root : fileInfo.getFile().getContents().getContentRoots()) {
            if (Files.isDirectory(root)) {
                action.accept(root);
            } else if (Files.isRegularFile(root)) {
                try (FileSystem fs = FileSystems.newFileSystem(root, (ClassLoader) null)) {
                    for (Path inner : fs.getRootDirectories()) {
                        action.accept(inner);
                    }
                } catch (IOException | RuntimeException e) {
                    me.cortex.voxy.common.Logger.error("Failed to open voxy mod file for class scanning: " + root, e);
                }
            }
        }
    }
}
