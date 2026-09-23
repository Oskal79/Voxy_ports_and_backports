package me.cortex.voxy.client.mixin.minecraft;

import net.minecraft.block.BlockState;
import net.minecraft.block.StairsBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads {@code StairsBlock.baseState}, the block a stair was derived from, for model baking.
 * <p>
 * This was an access transformer entry originally, but widening the field to public breaks other
 * mods' coremods that assert it is still private -- ATM8-era packs ship a
 * {@code fieldtomethodtransformers.js} coremod that fails with
 * "Field field_150151_M is not private and an instance field" as soon as voxy widens it.
 * A mixin accessor reads the same field without changing its access modifier.
 */
@Mixin(StairsBlock.class)
public interface AccessorStairsBlock {
    @Accessor("baseState")
    BlockState voxy$getBaseState();
}
