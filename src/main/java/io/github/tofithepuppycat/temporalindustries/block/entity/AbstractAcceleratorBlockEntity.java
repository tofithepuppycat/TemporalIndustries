package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.entropy.EntropyInfoProvider;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyOrbEntity;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared skeleton for the Decay/Despawn Accelerator generators: single input slot, tick-driven
 * progress counter, consumes the item and spawns one {@link EntropyOrbEntity} on completion.
 */
@SuppressWarnings("null")
public abstract class AbstractAcceleratorBlockEntity extends BlockEntity implements Container, MenuProvider, EntropyInfoProvider {
    public static final int INPUT_SLOT = 0;
    private static final int SLOT_COUNT = 1;
    public static final int PROCESS_TIME_TICKS = 100;

    private final NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
    private final IItemHandler inventory = new InvWrapper(this);
    private int progress;

    protected AbstractAcceleratorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    protected abstract boolean isValidInput(ItemStack stack);

    protected abstract int entropyValue(ItemStack stack);

    protected abstract EntropyType entropyType();

    public IItemHandler getItemHandler() {
        return inventory;
    }

    public int getProgress() {
        return progress;
    }

    @Override
    public List<Component> getEntropyTooltip() {
        List<Component> lines = new ArrayList<>();
        lines.add(getDisplayName().copy().withStyle(ChatFormatting.WHITE));
        boolean processing = progress > 0;
        lines.add((processing
                ? Component.translatable("overlay.temporalindustries.entropy_glasses.accelerator.progress", progress, PROCESS_TIME_TICKS)
                : Component.translatable("overlay.temporalindustries.entropy_glasses.accelerator.idle"))
                .withStyle(processing ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
        return lines;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, AbstractAcceleratorBlockEntity be) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        be.processTick(serverLevel, pos);
    }

    private void processTick(ServerLevel level, BlockPos pos) {
        ItemStack stack = items.get(INPUT_SLOT);
        if (stack.isEmpty() || !isValidInput(stack)) {
            if (progress != 0) {
                progress = 0;
                setChanged();
                syncToClients();
            }
            return;
        }

        progress++;
        if (progress < PROCESS_TIME_TICKS) {
            setChanged();
            syncToClients();
            return;
        }

        progress = 0;
        int value = entropyValue(stack);
        stack.shrink(1);
        EntropyOrbEntity.spawn(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, entropyType(), value);
        setChanged();
        syncToClients();
    }

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

    @Override public int getContainerSize() { return items.size(); }
    @Override public boolean isEmpty() { return items.get(INPUT_SLOT).isEmpty(); }
    @Override public ItemStack getItem(int slot) { return items.get(slot); }
    @Override public ItemStack removeItem(int slot, int amount) {
        ItemStack result = ContainerHelper.removeItem(items, slot, amount);
        if (!result.isEmpty()) setChanged();
        return result;
    }
    @Override public ItemStack removeItemNoUpdate(int slot) { return ContainerHelper.takeItem(items, slot); }
    @Override public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        if (stack.getCount() > getMaxStackSize()) stack.setCount(getMaxStackSize());
        setChanged();
    }
    @Override public boolean stillValid(Player player) { return Container.stillValidBlockEntity(this, player); }
    @Override public void clearContent() { items.clear(); }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Progress", progress);
        ContainerHelper.saveAllItems(tag, items, registries);
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        progress = tag.getInt("Progress");
        items.clear();
        ContainerHelper.loadAllItems(tag, items, registries);
    }
}
