package me.cortex.voxy.commonImpl.mixin.minecraft;

import me.cortex.voxy.commonImpl.IWorldGetIdentifier;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.util.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.world.DimensionType;
import net.minecraft.profiler.IProfiler;
import net.minecraft.world.storage.ISpawnWorldInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

@Mixin(World.class)
public class MixinWorld implements IWorldGetIdentifier {
    @Unique
    private WorldIdentifier identifier;

    @Inject(method = "<init>", at = @At("RETURN"))
    // 1.16.5's World constructor is
    //   (ISpawnWorldInfo, RegistryKey<World>, DimensionType, Supplier<IProfiler>, boolean, boolean, long)
    // -- no DynamicRegistries, no Holder, no maxChainedNeighborUpdates. An injected handler has to
    // mirror the target's parameter list exactly or mixin rejects it at load time.
    private void voxy$injectIdentifier(ISpawnWorldInfo properties,
                                       RegistryKey<World> key,
                                       DimensionType dimensionType,
                                       Supplier<IProfiler> profiler,
                                       boolean isClient,
                                       boolean debugWorld,
                                       long seed,
                                       CallbackInfo ci) {
        if (key != null) {
            // 1.16.5 hands the constructor a bare DimensionType with no registry key attached, and
            // registryAccess() is not safe to call before the subclass has finished initialising.
            // The world's own RegistryKey plus the seed already identify the save, so the
            // dimension-type key is left null here rather than resolved unsafely.
            this.identifier = new WorldIdentifier(key, seed, null);
        } else {
            this.identifier = null;
        }
    }

    @Override
    public WorldIdentifier voxy$getIdentifier() {
        return this.identifier;
    }
}
