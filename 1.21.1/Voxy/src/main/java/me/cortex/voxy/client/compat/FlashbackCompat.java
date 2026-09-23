package me.cortex.voxy.client.compat;

import java.nio.file.Path;

/**
 * Flashback integration stub for the NeoForge port.
 * <p>
 * Flashback ships for Fabric only, so it can never be present on NeoForge. The Fabric build mixed
 * into {@code com.moulberry.flashback.*} to pull the replay's voxy storage path out of the replay
 * metadata; with no Flashback on this loader there is nothing to read, so this always reports
 * "not installed" and hands back no replay storage path.
 * <p>
 * The shape of the class is kept identical to the Fabric version so {@code VoxyClientInstance}
 * needs no changes.
 */
public class FlashbackCompat {
    public static final boolean FLASHBACK_INSTALLED = false;

    public static Path getReplayStoragePath() {
        return null;
    }
}
