package io.github.tofithepuppycat.temporalindustries.mixin;

import io.github.tofithepuppycat.temporalindustries.entropy.EntropyEventListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BrewingStandBlockEntity.class)
public abstract class BrewingStandBlockEntityMixin {
    @Inject(method = "doBrew", at = @At("TAIL"))
    private static void temporalindustries$onBrewFinished(Level level, BlockPos pos, NonNullList<ItemStack> items, CallbackInfo ci) {
        EntropyEventListener.onBrewFinished(level, pos, items);
    }
}
