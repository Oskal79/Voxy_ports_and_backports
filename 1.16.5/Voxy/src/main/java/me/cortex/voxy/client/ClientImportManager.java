package me.cortex.voxy.client;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.ImportManager;
import me.cortex.voxy.commonImpl.importers.IDataImporter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ClientBossInfo;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.BossInfo;

import java.util.UUID;

public class ClientImportManager extends ImportManager {
    protected class ClientImportTask extends ImportTask {
        private final UUID bossbarUUID;
        private final ClientBossInfo bossBar;
        protected ClientImportTask(IDataImporter importer) {
            super(importer);

            this.bossbarUUID = UUID.randomUUID();
            // 1.16.5's ClientBossInfo has only a packet constructor, so round-trip a synthetic
            // ADD packet built from a ServerBossInfo carrying the title/colour/overlay.
            this.bossBar = new ClientBossInfo(new net.minecraft.network.play.server.SUpdateBossInfoPacket(
                    net.minecraft.network.play.server.SUpdateBossInfoPacket.Operation.ADD,
                    new net.minecraft.world.server.ServerBossInfo(
                            new net.minecraft.util.text.StringTextComponent("Voxy world importer"),
                            net.minecraft.world.BossInfo.Color.GREEN,
                            net.minecraft.world.BossInfo.Overlay.PROGRESS)));
            Minecraft.getInstance().execute(()->{
                Minecraft.getInstance().gui.getBossOverlay().events.put(bossBar.getId(), bossBar);
            });
        }

        @Override
        protected boolean onUpdate(int completed, int outOf) {
            if (!super.onUpdate(completed, outOf)) {
                return false;
            }
            Minecraft.getInstance().execute(()->{
                this.bossBar.setPercent((float) (((double)completed) / ((double) Math.max(1, outOf))));
                this.bossBar.setName(ITextComponent.nullToEmpty("Voxy import: " + completed + "/" + outOf + " chunks"));
            });
            return true;
        }

        @Override
        protected void onCompleted(int total) {
            super.onCompleted(total);
            Minecraft.getInstance().execute(()->{
                Minecraft.getInstance().gui.getBossOverlay().events.remove(this.bossbarUUID);
                long delta = Math.max(System.currentTimeMillis() - this.startTime, 1);

                String msg = "Voxy world import finished in " + (delta/1000) + " seconds, averaging " + (int)(total/(delta/1000f)) + " chunks per second";
                Minecraft.getInstance().gui.getChat().addMessage(new net.minecraft.util.text.StringTextComponent(msg));
                Logger.info(msg);
            });
        }
    }

    @Override
    protected synchronized ImportTask createImportTask(IDataImporter importer) {
        return new ClientImportTask(importer);
    }
}
