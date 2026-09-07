package io.github.tofithepuppycat.temporalindustries.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

/** Implemented by any multiblock controller block entity a {@link io.github.tofithepuppycat.temporalindustries.block.MachineFrame}
 * satellite can be pointed at, so the frame can forward clicks and capability requests to whichever controller owns it. */
public interface MachineFrameController {
    @Nullable IItemHandler getItemHandler();

    @Nullable IFluidHandler getFluidHandler();

    InteractionResult onFrameInteract(Level level, BlockPos controllerPos, ServerPlayer player);
}
