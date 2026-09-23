package me.cortex.voxy.client;

import me.cortex.voxy.client.compat.FlashbackCompat;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.StorageConfigUtil;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.config.section.SectionStorageConfig;
import me.cortex.voxy.commonImpl.ImportManager;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.world.storage.FolderName;

import java.nio.file.Path;

public class VoxyClientInstance extends VoxyInstance {
    // Java 21 has no flexible constructor bodies (JEP 482), so these cannot be assigned before the
    // super() call the way the 26.x source does -- yet VoxyInstance's constructor calls
    // shouldCreateInstance(), which needs the config. Resolving them lazily from that override gets
    // the same ordering; the cost is that the fields can no longer be final.
    private Config config;
    private Path basePath;
    private boolean noIngestOverride;

    public VoxyClientInstance() {
        super();
        this.ensureConfigLoaded();
        this.updateDedicatedThreads();
    }

    private void ensureConfigLoaded() {
        if (this.config != null) {
            return;
        }
        var path = FlashbackCompat.getReplayStoragePath();
        this.noIngestOverride = path != null;
        if (path == null) {
            path = getBasePath();
        }
        var basePath = this.basePath = path.normalize();
        this.config = StorageConfigUtil.getCreateStorageConfig(Config.class, c->c.version==1&&c.sectionStorageConfig!=null, ()->DEFAULT_STORAGE_CONFIG, basePath);
    }

    @Override
    protected boolean shouldCreateInstance() {
        // Called from VoxyInstance's constructor, i.e. before our own constructor body runs.
        this.ensureConfigLoaded();
        return !this.config.disabled;
    }

    @Override
    public void updateDedicatedThreads() {
        int target = VoxyConfig.CONFIG.serviceThreads;
        // Upstream subtracts Sodium's chunk-builder threads from voxy's pool so the two do not
        // oversubscribe the CPU. Embeddium 0.3.18 reaches that count through a different type
        // (ChunkRenderManager, not RenderSectionManager), so wiring it up belongs with the rest
        // of the Embeddium layer in stage 2. Until then voxy sizes its pool on its own, which is
        // exactly what dontUseSodiumBuilderThreads=true already does today.
        this.setNumThreads(target);
    }

    @Override
    protected ImportManager createImportManager() {
        return new ClientImportManager();
    }

    @Override
    protected SectionStorage createStorage(WorldIdentifier identifier) {
        var ctx = new ConfigBuildCtx();
        ctx.setProperty(ConfigBuildCtx.BASE_SAVE_PATH, this.basePath.toString());
        ctx.setProperty(ConfigBuildCtx.WORLD_IDENTIFIER, identifier.getWorldId());
        ctx.setProperty(ConfigBuildCtx.PLAYER_UUID, Minecraft.getInstance().getUser().getUuid().replace(':','-'));
        ctx.pushPath(ConfigBuildCtx.DEFAULT_STORAGE_PATH);
        return this.config.sectionStorageConfig.build(ctx);
    }

    public Path getStorageBasePath() {
        return this.basePath;
    }

    @Override
    public boolean isIngestEnabled(WorldIdentifier worldId) {
        return (!this.noIngestOverride) && VoxyConfig.CONFIG.ingestEnabled;
    }

    @Override
    public void shutdown() {
        super.shutdown();
        //Free the render resources cache since the entire instance is freed
        RenderResourceReuse.clearResources();
    }

    private static class Config {
        public int version = 1;
        public boolean disabled = false;
        public SectionStorageConfig sectionStorageConfig;
    }

    private static final Config DEFAULT_STORAGE_CONFIG;
    static {
        var config = new Config();
        config.sectionStorageConfig = StorageConfigUtil.createDefaultSerializer();
        DEFAULT_STORAGE_CONFIG = config;
    }

    private static Path getBasePath() {
        Path basePath = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy").resolve("saves");
        var iserver = Minecraft.getInstance().getSingleplayerServer();
        if (iserver != null) {
            basePath = iserver.getWorldPath(FolderName.ROOT).resolve("voxy");
        } else {
            var netHandle = Minecraft.getInstance().gameMode;
            if (netHandle == null) {
                Logger.error("Network handle null");
                basePath = basePath.resolve("UNKNOWN");
            } else {
                var info = Minecraft.getInstance().getCurrentServer();
                if (info == null) {
                    Logger.error("Server info null");
                    basePath = basePath.resolve("UNKNOWN");
                } else {
                    // 1.16.5's ServerData has no isRealm(); realms are identified by their
                    // hostname, which is what the client itself keys on at this version.
                    if (info.ip != null && info.ip.contains("minecraftservices.com")) {
                        basePath = basePath.resolve("realms");
                    } else {
                        basePath = basePath.resolve(info.ip.replace(":", "_"));
                    }
                }
            }
        }
        return basePath.toAbsolutePath();
    }
}
