package io.github.tofithepuppycat.temporalindustries.menu;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.LootGeneratorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Container menu for the Loot Generator GUI: Chaos tank fill/capacity, roll progress, and a
 * 27-slot inventory. The selected loot table id and validity flag are read straight off the block
 * entity, since strings don't fit in {@code ContainerData}'s int slots. */
@SuppressWarnings("null")
public class LootGeneratorMenu extends AbstractContainerMenu {
    private static final int SLOT_COUNT = 27;
    private static final int SLOT_X = 8;
    private static final int SLOT_Y = 68;
    private static final int INVENTORY_X = 8;
    private static final int INVENTORY_Y = 134;
    private static final int HOTBAR_Y = 192;

    private final LootGeneratorBlockEntity blockEntity;
    private final BlockPos blockPos;
    private final ContainerLevelAccess access;
    private final ContainerData data;

    public LootGeneratorMenu(int id, Inventory playerInventory, RegistryFriendlyByteBuf dataBuffer) {
        this(id, playerInventory, getBlockEntity(playerInventory, dataBuffer.readBlockPos()));
    }

    public LootGeneratorMenu(int id, Inventory playerInventory, LootGeneratorBlockEntity blockEntity) {
        this(id, playerInventory, blockEntity, ContainerLevelAccess.create(playerInventory.player.level(), blockEntity.getBlockPos()), new SimpleContainerData(7));
    }

    public LootGeneratorMenu(int id, Inventory playerInventory, LootGeneratorBlockEntity blockEntity, ContainerLevelAccess access, ContainerData data) {
        super(Registration.LOOT_GENERATOR_MENU.get(), id);
        this.blockEntity = blockEntity;
        this.blockPos = blockEntity.getBlockPos();
        this.access = access;
        this.data = data;

        checkContainerDataCount(data, 7);
        addDataSlots(data);

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(blockEntity, col + row * 9, SLOT_X + col * 18, SLOT_Y + row * 18));
            }
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, INVENTORY_X + col * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, INVENTORY_X + col * 18, HOTBAR_Y));
        }
    }

    private static LootGeneratorBlockEntity getBlockEntity(Inventory playerInventory, BlockPos blockPos) {
        if (playerInventory.player.level().getBlockEntity(blockPos) instanceof LootGeneratorBlockEntity be) {
            return be;
        }
        throw new IllegalStateException("Expected LootGeneratorBlockEntity at " + blockPos);
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        data.set(0, blockEntity.getChaosTank().getFluidAmount());
        data.set(1, blockEntity.getChaosTank().getCapacity());
        data.set(2, blockEntity.getProgress());
        data.set(3, blockEntity.getMaxProgress());
        data.set(4, blockEntity.getLuck());
        data.set(5, blockEntity.isRunning() ? 1 : 0);
        data.set(6, blockEntity.isRepeatMode() ? 1 : 0);
    }

    public int getChaosFluidAmount() {
        return data.get(0);
    }

    public int getChaosTankCapacity() {
        return data.get(1);
    }

    public int getProgress() {
        return data.get(2);
    }

    public int getMaxProgress() {
        return data.get(3);
    }

    public int getLuck() {
        return data.get(4);
    }

    public int getRollCost() {
        return LootGeneratorBlockEntity.ROLL_COST + getLuck() * LootGeneratorBlockEntity.LUCK_ROLL_COST;
    }

    public int getItemCost() {
        return LootGeneratorBlockEntity.ITEM_COST + getLuck() * LootGeneratorBlockEntity.LUCK_ITEM_COST;
    }

    public boolean isRunning() {
        return data.get(5) != 0;
    }

    public boolean isRepeatMode() {
        return data.get(6) != 0;
    }

    @Nullable
    public ResourceLocation getSelectedLootTable() {
        return blockEntity.getSelectedLootTable();
    }

    public boolean isSelectionValid() {
        return blockEntity.isSelectionValid();
    }

    /** Items this table could plausibly produce, sampled server-side when the current roll started. */
    public List<ItemStack> getPossibleItems() {
        return blockEntity.getPossibleItems();
    }

    /** The item that will actually be placed when the current roll's progress bar fills. */
    public ItemStack getNextRollItem() {
        return blockEntity.getNextRollItem();
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stackInSlot = slot.getItem();
        ItemStack result = stackInSlot.copy();

        int inventoryStart = SLOT_COUNT;
        int inventoryEnd = inventoryStart + 36;

        if (index < SLOT_COUNT) {
            if (!moveItemStackTo(stackInSlot, inventoryStart, inventoryEnd, true)) return ItemStack.EMPTY;
        } else {
            if (!moveItemStackTo(stackInSlot, 0, SLOT_COUNT, false)) return ItemStack.EMPTY;
        }

        if (stackInSlot.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return result;
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        return stillValid(access, player, Registration.LOOT_GENERATOR_BLOCK.get());
    }

    public BlockPos getBlockPos() {
        return blockPos;
    }
}
