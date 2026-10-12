package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyInfoProvider;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.menu.EntropyManipulatorMenu;
import io.github.tofithepuppycat.temporalindustries.recipe.EntropyManipulatorRecipe;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spends liquid Order or Chaos to transmute whatever sits in its input slot, or the fluid in its
 * liquid tank, into the output of a matching {@link EntropyManipulatorRecipe} loaded from {@code
 * data/temporalindustries/recipe/}.
 */
@SuppressWarnings("null")
public class EntropyManipulatorBlockEntity extends BlockEntity implements Container, MenuProvider, EntropyInfoProvider {
    public static final int TANK_CAPACITY = 8_000;
    public static final int LIQUID_TANK_CAPACITY = 4_000;
    /** Default progress-bar denominator before any recipe has been resolved. */
    public static final int PROCESS_TICKS = 100;

    private static final int SLOT_COUNT = 2;
    public static final int INPUT_SLOT = 0;
    public static final int OUTPUT_SLOT = 1;

    private final class ManipulatorFluidHandler implements IFluidHandler {
        @Override public int getTanks() { return 3; }

        @Override public @NotNull FluidStack getFluidInTank(int tank) {
            return switch (tank) {
                case 0 -> chaosTank.getFluid();
                case 1 -> orderTank.getFluid();
                default -> liquidTank.getFluid();
            };
        }

        @Override public int getTankCapacity(int tank) {
            return switch (tank) {
                case 0 -> chaosTank.getCapacity();
                case 1 -> orderTank.getCapacity();
                default -> liquidTank.getCapacity();
            };
        }

        @Override public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
            return switch (tank) {
                case 0 -> chaosTank.isFluidValid(stack);
                case 1 -> orderTank.isFluidValid(stack);
                default -> liquidTank.isFluidValid(stack);
            };
        }

        @Override public int fill(FluidStack resource, FluidAction action) {
            if (chaosTank.isFluidValid(resource)) return chaosTank.fill(resource, action);
            if (orderTank.isFluidValid(resource)) return orderTank.fill(resource, action);
            return liquidTank.fill(resource, action);
        }

        @Override public @NotNull FluidStack drain(FluidStack resource, FluidAction action) {
            if (!chaosTank.getFluid().isEmpty() && chaosTank.getFluid().getFluid().isSame(resource.getFluid())) {
                return chaosTank.drain(resource, action);
            }
            if (!orderTank.getFluid().isEmpty() && orderTank.getFluid().getFluid().isSame(resource.getFluid())) {
                return orderTank.drain(resource, action);
            }
            if (!liquidTank.getFluid().isEmpty() && liquidTank.getFluid().getFluid().isSame(resource.getFluid())) {
                return liquidTank.drain(resource, action);
            }
            return FluidStack.EMPTY;
        }

        @Override public @NotNull FluidStack drain(int maxDrain, FluidAction action) {
            if (!liquidTank.getFluid().isEmpty()) return liquidTank.drain(maxDrain, action);
            if (!chaosTank.getFluid().isEmpty()) return chaosTank.drain(maxDrain, action);
            if (!orderTank.getFluid().isEmpty()) return orderTank.drain(maxDrain, action);
            return FluidStack.EMPTY;
        }
    }

    private final FluidTank chaosTank = new FluidTank(TANK_CAPACITY) {
        @Override public boolean isFluidValid(FluidStack stack) {
            return stack.getFluid().isSame(Registration.CHAOS_FLUID.get());
        }
    };
    private final FluidTank orderTank = new FluidTank(TANK_CAPACITY) {
        @Override public boolean isFluidValid(FluidStack stack) {
            return stack.getFluid().isSame(Registration.ORDER_FLUID.get());
        }
    };
    private final FluidTank liquidTank = new FluidTank(LIQUID_TANK_CAPACITY);
    private final ManipulatorFluidHandler fluidHandler = new ManipulatorFluidHandler();

    private final NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
    private final IItemHandler inventory = new InvWrapper(this);

    private int progress = 0;
    private int maxProgress = PROCESS_TICKS;
    @Nullable
    private EntropyType activeType = null;
    /**
     * Player's chosen output per input item, for inputs with more than one matching recipe (e.g.
     * Order+sand could yield glass, sandstone, or gravel); cycled via the GUI.
     */
    private final Map<Item, ResourceLocation> preferredRecipes = new HashMap<>();

    public EntropyManipulatorBlockEntity(BlockPos pos, BlockState state) {
        super(Registration.ENTROPY_MANIPULATOR_BLOCK_ENTITY.get(), pos, state);
    }

    public IItemHandler getItemHandler() {
        return inventory;
    }

    public IFluidHandler getFluidHandler() {
        return fluidHandler;
    }

    public FluidTank getChaosTank() {
        return chaosTank;
    }

    public FluidTank getOrderTank() {
        return orderTank;
    }

    public FluidTank getLiquidTank() {
        return liquidTank;
    }

    public int getProgress() {
        return progress;
    }

    public int getMaxProgress() {
        return maxProgress;
    }

    @Nullable
    public EntropyType getActiveType() {
        return activeType;
    }

    @Override
    public List<Component> getEntropyTooltip() {
        return List.of(
                getDisplayName().copy().withStyle(ChatFormatting.WHITE),
                Component.translatable("overlay.temporalindustries.entropy_glasses.liquid",
                        EntropyDisplay.formatFluid(orderTank.getFluidAmount()), EntropyDisplay.formatFluid(orderTank.getCapacity()))
                        .withStyle(ChatFormatting.GRAY).append(EntropyDisplay.unit(EntropyType.ORDER)),
                Component.translatable("overlay.temporalindustries.entropy_glasses.liquid",
                        EntropyDisplay.formatFluid(chaosTank.getFluidAmount()), EntropyDisplay.formatFluid(chaosTank.getCapacity()))
                        .withStyle(ChatFormatting.GRAY).append(EntropyDisplay.unit(EntropyType.CHAOS)));
    }

    public static void tick(Level level, BlockPos pos, BlockState state, EntropyManipulatorBlockEntity be) {
        if (level.isClientSide) return;
        be.processTick();
    }

    private void processTick() {
        EntropyManipulatorRecipe active = resolveActiveRecipe();
        if (active == null) {
            if (progress != 0 || activeType != null) {
                progress = 0;
                activeType = null;
                setChanged();
                syncToClients();
            }
            return;
        }

        activeType = active.entropyType();
        maxProgress = active.processTicks();
        progress++;
        if (progress >= maxProgress) {
            completeRecipe(active);
            progress = 0;
        }
        setChanged();
        syncToClients();
    }

    private EntropyManipulatorRecipe.Input currentInput() {
        ItemStack input = items.get(INPUT_SLOT);
        return !input.isEmpty()
                ? new EntropyManipulatorRecipe.Input(input, FluidStack.EMPTY)
                : new EntropyManipulatorRecipe.Input(ItemStack.EMPTY, liquidTank.getFluid());
    }

    /** Every recipe whose input matches, sorted by id for a stable pick/cycle order. */
    private List<RecipeHolder<EntropyManipulatorRecipe>> getMatchingRecipes(EntropyManipulatorRecipe.Input recipeInput) {
        if (level == null) return List.of();
        var recipeManager = level.getRecipeManager();
        var recipeType = Registration.ENTROPY_MANIPULATOR_RECIPE_TYPE.get();
        return recipeManager.getAllRecipesFor(recipeType).stream()
                .filter(holder -> holder.value().matches(recipeInput, level))
                .sorted(Comparator.comparing(holder -> holder.id().toString()))
                .toList();
    }

    /**
     * Among ambiguous matches, prefers the player's saved choice for this input item, falling back
     * to the first (stably ordered) candidate.
     */
    @Nullable
    private RecipeHolder<EntropyManipulatorRecipe> pickPreferred(List<RecipeHolder<EntropyManipulatorRecipe>> matches, ItemStack input) {
        if (matches.isEmpty()) return null;
        if (!input.isEmpty()) {
            ResourceLocation preferred = preferredRecipes.get(input.getItem());
            if (preferred != null) {
                for (RecipeHolder<EntropyManipulatorRecipe> holder : matches) {
                    if (holder.id().equals(preferred)) return holder;
                }
            }
        }
        return matches.get(0);
    }

    @Nullable
    private EntropyManipulatorRecipe resolveActiveRecipe() {
        if (level == null) return null;
        EntropyManipulatorRecipe.Input recipeInput = currentInput();
        List<RecipeHolder<EntropyManipulatorRecipe>> processable = getMatchingRecipes(recipeInput).stream()
                .filter(holder -> canProcess(holder.value()))
                .toList();
        RecipeHolder<EntropyManipulatorRecipe> chosen = pickPreferred(processable, recipeInput.item());
        return chosen == null ? null : chosen.value();
    }

    /**
     * How many recipes currently match the input slot/tank, regardless of whether entropy is
     * available to run them; the GUI only offers cycling when this is more than one.
     */
    public int getMatchingRecipeCount() {
        return getMatchingRecipes(currentInput()).size();
    }

    /**
     * The output item the manipulator will currently produce for the input, ignoring entropy
     * availability, for GUI preview purposes.
     */
    public ItemStack getSelectedRecipePreview(HolderLookup.Provider registries) {
        EntropyManipulatorRecipe.Input recipeInput = currentInput();
        RecipeHolder<EntropyManipulatorRecipe> chosen = pickPreferred(getMatchingRecipes(recipeInput), recipeInput.item());
        return chosen == null ? ItemStack.EMPTY : chosen.value().getResultItem(registries);
    }

    /**
     * Advances the player's chosen output for the current input item to the next matching recipe,
     * wrapping around; a no-op if there's no ambiguity to resolve.
     */
    public void cyclePreferredRecipe() {
        EntropyManipulatorRecipe.Input recipeInput = currentInput();
        ItemStack input = recipeInput.item();
        if (input.isEmpty()) return;

        List<RecipeHolder<EntropyManipulatorRecipe>> matches = getMatchingRecipes(recipeInput);
        if (matches.size() < 2) return;

        RecipeHolder<EntropyManipulatorRecipe> active = pickPreferred(matches, input);
        int index = (matches.indexOf(active) + 1) % matches.size();
        preferredRecipes.put(input.getItem(), matches.get(index).id());
        setChanged();
        syncToClients();
    }

    private boolean canProcess(EntropyManipulatorRecipe recipe) {
        return canOutput(recipe) && hasEntropy(recipe.entropyType(), recipe.entropyCost());
    }

    private boolean canOutput(EntropyManipulatorRecipe recipe) {
        ItemStack current = items.get(OUTPUT_SLOT);
        if (current.isEmpty()) return true;
        // A tag-result recipe rolls a random item on completion, so only let it start into an empty slot.
        if (recipe.isTagResult()) return false;
        Item output = recipe.getResultItem(level.registryAccess()).getItem();
        return current.getItem() == output && current.getCount() < current.getMaxStackSize();
    }

    private boolean hasEntropy(EntropyType type, int cost) {
        FluidTank tank = type == EntropyType.CHAOS ? chaosTank : orderTank;
        return tank.getFluidAmount() >= cost;
    }

    private void completeRecipe(EntropyManipulatorRecipe active) {
        FluidTank entropyTank = active.entropyType() == EntropyType.CHAOS ? chaosTank : orderTank;
        var entropyFluid = active.entropyType() == EntropyType.CHAOS ? Registration.CHAOS_FLUID.get() : Registration.ORDER_FLUID.get();
        entropyTank.drain(new FluidStack(entropyFluid, active.entropyCost()), IFluidHandler.FluidAction.EXECUTE);

        if (active.isFluidRecipe()) {
            liquidTank.drain(active.fluidAmount(), IFluidHandler.FluidAction.EXECUTE);
        } else {
            items.get(INPUT_SLOT).shrink(1);
        }

        ItemStack result = active.rollResult(level.registryAccess(), level.random);
        ItemStack output = items.get(OUTPUT_SLOT);
        if (output.isEmpty()) {
            items.set(OUTPUT_SLOT, result.copy());
        } else {
            output.grow(result.getCount());
        }
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
    @Override public boolean isEmpty() { return items.get(INPUT_SLOT).isEmpty() && items.get(OUTPUT_SLOT).isEmpty(); }
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
    public Component getDisplayName() {
        return Component.translatable("block.temporalindustries.entropy_manipulator");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, @NotNull Inventory inventory, @NotNull Player player) {
        return new EntropyManipulatorMenu(id, inventory, this);
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("ChaosTank", chaosTank.writeToNBT(registries, new CompoundTag()));
        tag.put("OrderTank", orderTank.writeToNBT(registries, new CompoundTag()));
        tag.put("LiquidTank", liquidTank.writeToNBT(registries, new CompoundTag()));
        tag.putInt("Progress", progress);
        if (activeType != null) tag.putString("ActiveType", activeType.name());
        ContainerHelper.saveAllItems(tag, items, registries);

        ListTag preferredTag = new ListTag();
        preferredRecipes.forEach((item, recipeId) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("Item", BuiltInRegistries.ITEM.getKey(item).toString());
            entry.putString("Recipe", recipeId.toString());
            preferredTag.add(entry);
        });
        tag.put("PreferredRecipes", preferredTag);
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("ChaosTank")) chaosTank.readFromNBT(registries, tag.getCompound("ChaosTank"));
        if (tag.contains("OrderTank")) orderTank.readFromNBT(registries, tag.getCompound("OrderTank"));
        if (tag.contains("LiquidTank")) liquidTank.readFromNBT(registries, tag.getCompound("LiquidTank"));
        progress = tag.getInt("Progress");
        activeType = tag.contains("ActiveType") ? EntropyType.valueOf(tag.getString("ActiveType")) : null;
        items.clear();
        ContainerHelper.loadAllItems(tag, items, registries);

        preferredRecipes.clear();
        if (tag.contains("PreferredRecipes")) {
            for (Tag entryTag : tag.getList("PreferredRecipes", Tag.TAG_COMPOUND)) {
                CompoundTag entry = (CompoundTag) entryTag;
                Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(entry.getString("Item")));
                preferredRecipes.put(item, ResourceLocation.parse(entry.getString("Recipe")));
            }
        }
    }
}
