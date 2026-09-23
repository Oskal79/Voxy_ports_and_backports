package me.cortex.voxy.client.mixin.minecraft.util;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.voxy.client.LoadException;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Makes Voxy's {@link LoadException} crash the game instead of being swallowed by the client's
 * task loop, so load failures are actually visible.
 * <p>
 * 1.21.1's {@code doRunTask} simply wraps the task in {@code try { task.run() } catch (Exception e)
 * { LOGGER.error(...) }} -- there is no {@code isNonRecoverable(Throwable)} to redirect the way the
 * 26.x build did. Wrapping the {@code run()} call gives the same effect and is independent of how
 * the surrounding catch block is written.
 */
@Mixin(BlockableEventLoop.class)
public abstract class MixinBlockableEventLoop {

    @WrapOperation(method = "doRunTask", at = @At(value = "INVOKE", target = "Ljava/lang/Runnable;run()V"))
    private void voxy$forceCrashOnError(Runnable task, Operation<Void> original) {
        try {
            original.call(task);
        } catch (LoadException le) {
            if (le.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw le;
        }
    }
}
