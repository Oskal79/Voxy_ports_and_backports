package me.cortex.voxy.client.mixin.minecraft.session;

import me.cortex.voxy.client.ClientSessionEvents;
import net.minecraft.client.network.play.ClientPlayNetHandler;
import net.minecraft.network.play.server.SJoinGamePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetHandler.class)
public class MixinClientPacketListener {
    // Upstream injects after the join packet's CommonPlayerSpawnInfo is read, but that type (and
    // SJoinGamePacket#commonPlayerSpawnInfo) does not exist before 1.20.2 -- the 1.16.5 packet
    // carries those fields directly. handleLogin has finished setting up the ClientWorld by the
    // time it returns, which is the state the session start actually depends on.
    @Inject(method = "handleLogin", at = @At("RETURN"))
    private void voxy$init(SJoinGamePacket packet, CallbackInfo ci) {
        if (!ClientSessionEvents.inSession) {
            ClientSessionEvents.sessionStart();
        }
    }
}
