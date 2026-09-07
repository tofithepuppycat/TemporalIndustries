package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Remembers the controller position of whatever multiblock this frame is currently part of, so
 * clicks and item/fluid capability requests against the frame forward to that controller, letting
 * pipes/hoppers insert/extract from any face of the multiblock. Kept up to date by the controller
 * re-scanning its structure. */
public class MachineFrameBlockEntity extends BlockEntity {
    @Nullable
    private BlockPos controller;

    public MachineFrameBlockEntity(BlockPos pos, BlockState state) {
        super(Registration.MACHINE_FRAME_BLOCK_ENTITY.get(), pos, state);
    }

    @Nullable
    public BlockPos getController() {
        return controller;
    }

    public void setController(BlockPos controller) {
        if (!controller.equals(this.controller)) {
            this.controller = controller;
            setChanged();
            invalidateCapabilities();
        }
    }

    @Nullable
    private MachineFrameController getControllerBlockEntity() {
        if (controller == null || level == null) return null;
        return level.getBlockEntity(controller) instanceof MachineFrameController mfc ? mfc : null;
    }

    @Nullable
    public IItemHandler getItemHandler() {
        MachineFrameController controllerBe = getControllerBlockEntity();
        return controllerBe == null ? null : controllerBe.getItemHandler();
    }

    @Nullable
    public IFluidHandler getFluidHandler() {
        MachineFrameController controllerBe = getControllerBlockEntity();
        return controllerBe == null ? null : controllerBe.getFluidHandler();
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (controller != null) tag.putLong("Controller", controller.asLong());
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        controller = tag.contains("Controller") ? BlockPos.of(tag.getLong("Controller")) : null;
    }
}
