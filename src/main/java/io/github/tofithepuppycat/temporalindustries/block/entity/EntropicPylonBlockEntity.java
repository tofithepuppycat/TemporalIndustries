package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.EntropicPylon;
import io.github.tofithepuppycat.temporalindustries.block.MachineFrame;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Transfers liquid Order/Chaos between marked blocks. Which blocks to pull from and push into is
 * decided entirely by {@link io.github.tofithepuppycat.temporalindustries.item.EntropicPylonItem}
 * before this block even exists - the item records up to
 * {@link io.github.tofithepuppycat.temporalindustries.item.EntropicPylonItem#MAX_MARKS} input
 * positions (plain right-click) and output positions (shift right-click) on itself, and hands
 * whatever's still in range off to {@link #setMarks} the moment it's placed. Requires a single
 * {@link MachineFrame} directly above to actually run - see {@link #checkStructure()}, mirroring
 * the controller/frame idiom {@link LootGeneratorBlockEntity} established.
 */
@SuppressWarnings("null")
public class EntropicPylonBlockEntity extends BlockEntity implements MachineFrameController {
    private static final int STRUCTURE_RECHECK_INTERVAL = 20;
    /** mB of whichever fluid is present, moved per input->output pair each tick. */
    private static final int TRANSFER_RATE_MB = 50;

    private List<BlockPos> inputs = new ArrayList<>();
    private List<BlockPos> outputs = new ArrayList<>();
    private boolean formed = false;
    private int ticksSinceStructureCheck = 0;
    private int outputCursor = 0;

    public EntropicPylonBlockEntity(BlockPos pos, BlockState state) {
        super(Registration.ENTROPIC_PYLON_BLOCK_ENTITY.get(), pos, state);
    }

    public List<BlockPos> getInputs() {
        return inputs;
    }

    public List<BlockPos> getOutputs() {
        return outputs;
    }

    public boolean isFormed() {
        return formed;
    }

    /** Called once, right as {@link io.github.tofithepuppycat.temporalindustries.item.EntropicPylonItem}
     * places this block, with whatever marks from the item stack are still in range. */
    public void setMarks(List<BlockPos> inputs, List<BlockPos> outputs) {
        this.inputs = new ArrayList<>(inputs);
        this.outputs = new ArrayList<>(outputs);
        setChanged();
        syncToClients();
    }

    // -------------------------------------------------------------------------
    // Structure

    private BlockPos framePos() {
        return worldPosition.above();
    }

    /** Re-checks for a {@link MachineFrame} directly above and updates {@link #formed}, syncing to
     * clients and pushing {@link EntropicPylon#FORMED} into the block state if it changed - same
     * idiom as {@link LootGeneratorBlockEntity#checkStructure()}, just for a single fixed position
     * instead of a whole ring. */
    public boolean checkStructure() {
        if (level == null) return formed;
        boolean wasFormed = formed;
        BlockPos framePos = framePos();
        boolean present;
        if (level.getBlockEntity(framePos) instanceof MachineFrameBlockEntity frameBe) {
            frameBe.setController(worldPosition);
            present = true;
        } else {
            present = level.getBlockState(framePos).is(Registration.MACHINE_FRAME_BLOCK.get());
        }
        formed = present;
        if (formed != wasFormed) {
            setChanged();
            syncToClients();
            if (!level.isClientSide) {
                BlockState state = getBlockState();
                if (state.hasProperty(EntropicPylon.FORMED)) {
                    level.setBlock(worldPosition, state.setValue(EntropicPylon.FORMED, formed), Block.UPDATE_CLIENTS);
                }
                MachineFrame.setConnected(level, framePos, formed);
            }
        }
        return formed;
    }

    // -------------------------------------------------------------------------
    // Tick / transfer

    public static void tick(Level level, BlockPos pos, BlockState state, EntropicPylonBlockEntity be) {
        if (level.isClientSide) return;
        be.processTick();
    }

    private void processTick() {
        if (++ticksSinceStructureCheck >= STRUCTURE_RECHECK_INTERVAL) {
            ticksSinceStructureCheck = 0;
            checkStructure();
        }
        if (!formed || inputs.isEmpty() || outputs.isEmpty()) return;
        transferOnce();
    }

    /** One transfer attempt per marked input, against the next marked output in rotation - so with
     * equal counts every input/output pair gets visited over time instead of always favoring the
     * first output. Moves whatever fluid is actually present (Order or Chaos);
     * {@link FluidUtil#tryFluidTransfer} already refuses anything the destination tank rejects. */
    private void transferOnce() {
        for (BlockPos inPos : inputs) {
            IFluidHandler source = level.getCapability(Capabilities.FluidHandler.BLOCK, inPos, null);
            if (source == null) continue;

            BlockPos outPos = outputs.get(outputCursor % outputs.size());
            outputCursor++;
            IFluidHandler dest = level.getCapability(Capabilities.FluidHandler.BLOCK, outPos, null);
            if (dest == null) continue;

            FluidUtil.tryFluidTransfer(dest, source, TRANSFER_RATE_MB, true);
        }
    }

    // -------------------------------------------------------------------------
    // MachineFrameController - the pylon isn't itself an item/fluid container, only a router, so a
    // frame sitting on top of it exposes nothing to pipes/hoppers; a click just reports status.

    @Override
    @Nullable
    public IItemHandler getItemHandler() {
        return null;
    }

    @Override
    @Nullable
    public IFluidHandler getFluidHandler() {
        return null;
    }

    @Override
    public InteractionResult onFrameInteract(Level level, BlockPos controllerPos, ServerPlayer player) {
        checkStructure();
        Component formedComponent = formed
                ? Component.translatable("block.temporalindustries.entropic_pylon.formed").withStyle(ChatFormatting.GREEN)
                : Component.translatable("block.temporalindustries.entropic_pylon.unformed").withStyle(ChatFormatting.RED);
        player.displayClientMessage(Component.translatable("block.temporalindustries.entropic_pylon.status",
                inputs.size(), outputs.size(), formedComponent), true);
        return InteractionResult.CONSUME;
    }

    // -------------------------------------------------------------------------
    // Sync

    private void syncToClients() {
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        loadAdditional(tag, registries);
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Inputs", posListTag(inputs));
        tag.put("Outputs", posListTag(outputs));
        tag.putBoolean("Formed", formed);
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        inputs = readPosList(tag, "Inputs");
        outputs = readPosList(tag, "Outputs");
        formed = tag.getBoolean("Formed");
    }

    private static LongArrayTag posListTag(List<BlockPos> list) {
        long[] array = new long[list.size()];
        for (int i = 0; i < list.size(); i++) array[i] = list.get(i).asLong();
        return new LongArrayTag(array);
    }

    private static List<BlockPos> readPosList(CompoundTag tag, String key) {
        List<BlockPos> list = new ArrayList<>();
        if (!tag.contains(key)) return list;
        for (long packed : tag.getLongArray(key)) list.add(BlockPos.of(packed));
        return list;
    }
}
