package me.cortex.voxy.common;

import me.cortex.voxy.common.config.Serialization;
import me.cortex.voxy.common.config.compressors.LZ4Compressor;
import me.cortex.voxy.common.config.compressors.ZSTDCompressor;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.other.CompressionStorageAdaptor;
import me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class StorageConfigUtil {

    public static <T> T getCreateStorageConfig(Class<T> clz, Predicate<T> verifier, Supplier<T> defaultConfig, Path path) {
        try {
            Files.createDirectories(path);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        var json = path.resolve("config.json");
        T config = null;
        if (Files.exists(json)) {
            try {
                config = Serialization.GSON.fromJson(new String(Files.readAllBytes(json), java.nio.charset.StandardCharsets.UTF_8), clz);
                if (config == null) {
                    Logger.error("Config deserialization null, reverting to default");
                } else {
                    if (!verifier.test(config)) {
                        Logger.error("Config section storage null, reverting to default");
                        config = null;
                    }
                }
            } catch (Exception e) {
                Logger.error("Failed to load the storage configuration file, resetting it to default, this will probably break your save if you used a custom storage config", e);
            }
        }

        if (config == null) {
            config = defaultConfig.get();
        }
        try {
            Files.write(json, (Serialization.GSON.toJson(config)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Failed write the config, aborting!", e);
        }
        if (config == null) {
            throw new IllegalStateException("Config is still null\n");
        }
        return config;
    }

    public static SectionSerializationStorage.Config createDefaultSerializer() {
        //Create the default config
        var baseDB = new RocksDBStorageBackend.Config();

        // LZ4 rather than ZSTD by default on this target. Forge's ModLauncher delegates the
        // whole org.lwjgl.* package to the parent classloader, which only has the LWJGL modules
        // Minecraft ships (core, glfw, jemalloc, ...). lwjgl-zstd and lwjgl-lmdb are not among
        // them, so a shaded copy inside the mod jar is never reached and every storage job dies
        // with NoClassDefFoundError on org/lwjgl/util/zstd/Zstd. LZ4 is net.jpountz, outside that
        // delegated package, so it loads normally.
        var compressor = new LZ4Compressor.Config();

        var compression = new CompressionStorageAdaptor.Config();
        compression.delegate = baseDB;
        compression.compressor = compressor;

        var serializer = new SectionSerializationStorage.Config();
        serializer.storage = compression;

        return serializer;
    }
}
