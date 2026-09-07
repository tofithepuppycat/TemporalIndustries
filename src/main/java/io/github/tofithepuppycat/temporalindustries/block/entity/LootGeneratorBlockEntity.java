package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.BoxEdgeParticles;
import io.github.tofithepuppycat.temporalindustries.block.LootGenerator;
import io.github.tofithepuppycat.temporalindustries.block.LootGeneratorStructure;
import io.github.tofithepuppycat.temporalindustries.block.MachineFrame;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyInfoProvider;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.menu.LootGeneratorMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Spends liquid Chaos to roll a player-chosen loot table (vanilla or modded) into its own
 * chest-sized inventory, one item at a time on a tick-driven progress bar - a fixed cost to start
 * the roll, plus a further cost per item actually placed. See {@link EntropyManipulatorBlockEntity}
 * for the ticked-consumption idiom this mirrors. A luck slider (0-{@link #MAX_LUCK}) feeds into the
 * roll as the loot table's luck parameter for better results, at a Chaos surcharge on both costs.
 * A play/stop toggle ({@link #beginGeneration()}/{@link #stopGeneration()}) starts and halts
 * generation, and a single/repeat toggle ({@link #setRepeatMode}) picks whether one press produces
 * one roll or keeps rolling for as long as Chaos holds out.
 */
@SuppressWarnings("null")
public class LootGeneratorBlockEntity extends BlockEntity implements Container, MenuProvider, EntropyInfoProvider, MachineFrameController {
    public static final int TANK_CAPACITY = 8_000;
    public static final int ROLL_COST = 500;
    public static final int ITEM_COST = 50;
    public static final int PROCESS_TICKS = 40;

    // Luck slider: 0 (default, no extra cost) up to MAX_LUCK, fed straight into the loot table roll
    // as LootContextParams.LUCK so tables with quality-weighted pools/functions skew toward better
    // results, at a Chaos surcharge that scales with how far the slider is pushed.
    public static final int MAX_LUCK = 10;
    public static final int LUCK_ROLL_COST = 100;
    public static final int LUCK_ITEM_COST = 20;

    private static final int SLOT_COUNT = 27;
    private static final int STRUCTURE_RECHECK_INTERVAL = 20;
    private static final int POSSIBLE_ITEMS_SAMPLES = 12;
    private static final int MAX_POSSIBLE_ITEMS = 16;
    private static final double FORMED_EDGE_PARTICLE_SPACING = 0.3;

    private final FluidTank chaosTank = new FluidTank(TANK_CAPACITY) {
        @Override public boolean isFluidValid(FluidStack stack) {
            return stack.getFluid().isSame(Registration.CHAOS_FLUID.get());
        }
    };

    // Implementing Container directly (not only IItemHandler) is what lets vanilla hoppers push/pull
    // items here; inventory wraps this same backing list as an IItemHandler for modded item pipes.
    private final NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
    private final IItemHandler inventory = new InvWrapper(this);

    @Nullable
    private ResourceLocation selectedLootTable;
    private boolean lastSelectionValid = false;
    private int luck = 0;
    // Whether the GUI's play/stop icon shows "stop" - true from pressing play until either the
    // player presses stop or (single-shot mode) the in-flight roll finishes placing everything.
    private boolean running = false;
    // false: one full roll (which may itself contain several items) per press of play, then stops.
    // true: presses play once and keeps starting a fresh roll every time the previous one finishes,
    // until stopped or Chaos runs out - resuming on its own once the tank refills, same as the
    // existing mid-roll pause behavior.
    private boolean repeatMode = false;
    private List<ItemStack> pendingRoll = new ArrayList<>();
    // Sampled by re-rolling the table a handful of extra times when a roll starts, purely so the
    // client can spin through icons of things this table could plausibly produce (see
    // LootGeneratorScreen) - not itself consumed or placed anywhere.
    private List<ItemStack> possibleItems = new ArrayList<>();
    private int progress = 0;
    private int maxProgress = PROCESS_TICKS;
    private boolean formed = false;
    private int ticksSinceStructureCheck = 0;

    private static final DustParticleOptions FORMED_PARTICLE = buildFormedParticle();

    private static DustParticleOptions buildFormedParticle() {
        int color = EntropyType.ORDER.color();
        float r = ((color >> 16) & 0xFF) / 255F;
        float g = ((color >> 8) & 0xFF) / 255F;
        float b = (color & 0xFF) / 255F;
        return new DustParticleOptions(new Vector3f(r, g, b), 2.0F);
    }

    public LootGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(Registration.LOOT_GENERATOR_BLOCK_ENTITY.get(), pos, state);
    }

    public IItemHandler getItemHandler() {
        return inventory;
    }

    public IFluidHandler getFluidHandler() {
        return chaosTank;
    }

    @Override
    public InteractionResult onFrameInteract(Level level, BlockPos controllerPos, ServerPlayer player) {
        return LootGenerator.interact(level, controllerPos, this, player);
    }

    public FluidTank getChaosTank() {
        return chaosTank;
    }

    @Nullable
    public ResourceLocation getSelectedLootTable() {
        return selectedLootTable;
    }

    public boolean isSelectionValid() {
        return lastSelectionValid;
    }

    public int getLuck() {
        return luck;
    }

    /** Clamps to [0, {@link #MAX_LUCK}] rather than trusting the client's slider position, since the
     * packet that calls this comes straight from the GUI. */
    public void setLuck(int luck) {
        this.luck = Math.clamp(luck, 0, MAX_LUCK);
        setChanged();
        syncToClients();
    }

    /** Chaos needed to start a roll at the current luck setting. */
    public int getRollCost() {
        return ROLL_COST + luck * LUCK_ROLL_COST;
    }

    /** Chaos needed to place the next item at the current luck setting. */
    public int getItemCost() {
        return ITEM_COST + luck * LUCK_ITEM_COST;
    }

    /** Whether the GUI's play/stop icon should currently show "stop". */
    public boolean isRunning() {
        return running;
    }

    public boolean isRepeatMode() {
        return repeatMode;
    }

    public void setRepeatMode(boolean repeatMode) {
        this.repeatMode = repeatMode;
        setChanged();
        syncToClients();
    }

    /** Presses "play": no-ops if already running, otherwise marks the generator running and kicks
     * off the first roll (which itself no-ops harmlessly if there isn't enough Chaos yet or the
     * selection is invalid - {@link #processTick()} keeps retrying every tick while running). */
    public void beginGeneration() {
        if (running) return;
        running = true;
        setChanged();
        syncToClients();
        startRoll();
    }

    /** Presses "stop": halts immediately, discarding any roll that hasn't finished placing its items
     * yet (the Chaos already spent to start that roll is not refunded). */
    public void stopGeneration() {
        if (!running && pendingRoll.isEmpty()) return;
        running = false;
        pendingRoll.clear();
        progress = 0;
        setChanged();
        syncToClients();
    }

    public int getProgress() {
        return progress;
    }

    /** Sampled items this table could plausibly produce, for the client's roll animation - not the
     * actual queued results. */
    public List<ItemStack> getPossibleItems() {
        return possibleItems;
    }

    /** The item that will actually be placed when the current roll's progress bar fills, or empty
     * if no roll is in progress. */
    public ItemStack getNextRollItem() {
        return pendingRoll.isEmpty() ? ItemStack.EMPTY : pendingRoll.get(0);
    }

    public int getMaxProgress() {
        return maxProgress;
    }

    public boolean isFormed() {
        return formed;
    }

    /** Positions around this controller that still need a {@link io.github.tofithepuppycat.temporalindustries.block.MachineFrame} block.
     * Also re-points the block entity of any frame already present back at this controller, so
     * right-clicking that frame block can forward the interaction here - see {@link MachineFrameBlockEntity}. */
    public List<BlockPos> findMissing() {
        List<BlockPos> missing = new ArrayList<>();
        if (level == null) return missing;
        for (BlockPos pos : LootGeneratorStructure.framePositions(worldPosition, getBlockState().getValue(LootGenerator.FACING))) {
            if (level.getBlockEntity(pos) instanceof MachineFrameBlockEntity frameBe) {
                frameBe.setController(worldPosition);
            } else if (!level.getBlockState(pos).is(Registration.MACHINE_FRAME_BLOCK.get())) {
                missing.add(pos);
            }
        }
        return missing;
    }

    /** Re-scans the frame positions and updates {@link #formed}, syncing to clients if it changed -
     * including pushing {@link LootGenerator#FORMED} into the actual block state so the front-face
     * model swaps between its animated and frozen-last-frame textures. */
    public boolean checkStructure() {
        boolean wasFormed = formed;
        formed = findMissing().isEmpty();
        if (formed != wasFormed) {
            setChanged();
            syncToClients();
            if (level != null && !level.isClientSide) {
                BlockState state = getBlockState();
                if (state.hasProperty(LootGenerator.FORMED)) {
                    level.setBlock(worldPosition, state.setValue(LootGenerator.FORMED, formed), Block.UPDATE_CLIENTS);
                }
                updateFrameConnectivity(formed);
                if (formed && level instanceof ServerLevel serverLevel) {
                    spawnFormedParticles(serverLevel);
                }
            }
        }
        return formed;
    }

    /** Pushes {@link MachineFrame#CONNECTED} to every present frame position, so the Fusion
     * connected-textures casing only shows on frames confirmed part of *this* formed structure -
     * rather than any frame block happening to sit next to one. */
    private void updateFrameConnectivity(boolean connected) {
        Direction facing = getBlockState().getValue(LootGenerator.FACING);
        for (BlockPos pos : LootGeneratorStructure.framePositions(worldPosition, facing)) {
            MachineFrame.setConnected(level, pos, connected);
        }
    }

    /** ORD-colored (see {@link EntropyType#ORDER}) outline traced along the edges of the whole
     * formed structure's bounding box - not just at the controller - fired once when the structure
     * transitions from unformed to formed. */
    private void spawnFormedParticles(ServerLevel serverLevel) {
        Direction facing = getBlockState().getValue(LootGenerator.FACING);
        List<BlockPos> positions = new ArrayList<>(LootGeneratorStructure.framePositions(worldPosition, facing));
        positions.add(worldPosition);

        int minX = positions.stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int minY = positions.stream().mapToInt(BlockPos::getY).min().orElseThrow();
        int minZ = positions.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        int maxX = positions.stream().mapToInt(BlockPos::getX).max().orElseThrow();
        int maxY = positions.stream().mapToInt(BlockPos::getY).max().orElseThrow();
        int maxZ = positions.stream().mapToInt(BlockPos::getZ).max().orElseThrow();

        for (Vector3f point : BoxEdgeParticles.outline(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1, FORMED_EDGE_PARTICLE_SPACING)) {
            serverLevel.sendParticles(FORMED_PARTICLE, point.x(), point.y(), point.z(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    @Override
    public List<Component> getEntropyTooltip() {
        List<Component> lines = new ArrayList<>();
        lines.add(getDisplayName().copy().withStyle(ChatFormatting.WHITE));
        lines.add(Component.translatable("overlay.temporalindustries.entropy_glasses.liquid",
                        EntropyDisplay.formatFluid(chaosTank.getFluidAmount()), EntropyDisplay.formatFluid(chaosTank.getCapacity()))
                .withStyle(ChatFormatting.GRAY).append(EntropyDisplay.unit(EntropyType.CHAOS)));

        if (selectedLootTable == null) {
            lines.add(Component.translatable("overlay.temporalindustries.entropy_glasses.loot_generator.no_table")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            String key = lastSelectionValid
                    ? "overlay.temporalindustries.entropy_glasses.loot_generator.table"
                    : "overlay.temporalindustries.entropy_glasses.loot_generator.invalid_table";
            ChatFormatting color = lastSelectionValid ? ChatFormatting.GRAY : ChatFormatting.RED;
            lines.add(Component.translatable(key, selectedLootTable.toString()).withStyle(color));
        }

        if (luck > 0) {
            lines.add(Component.translatable("overlay.temporalindustries.entropy_glasses.loot_generator.luck", luck, MAX_LUCK)
                    .withStyle(ChatFormatting.GRAY));
        }

        lines.add(running
                ? Component.translatable("overlay.temporalindustries.entropy_glasses.loot_generator.running").withStyle(ChatFormatting.YELLOW)
                : Component.translatable("overlay.temporalindustries.entropy_glasses.loot_generator.stopped").withStyle(ChatFormatting.GRAY));

        return lines;
    }

    /** Sets the selected loot table id (or clears it, if {@code id} is null) and re-validates it
     * against the server's actually-registered loot tables, so a stale/typoed id is flagged rather
     * than silently accepted. */
    public void setSelectedLootTable(@Nullable ResourceLocation id) {
        this.selectedLootTable = id;
        this.lastSelectionValid = id != null && resolvesToRealTable(id);
        setChanged();
        syncToClients();
    }

    /** Drains {@link #ROLL_COST} (plus luck surcharge) and rolls the selected loot table, queuing the
     * results to be placed one at a time by {@link #processTick()}. No-ops if a roll is already in
     * progress, the selection doesn't resolve to a real loot table, or there isn't enough Chaos to
     * start one - called from {@link #beginGeneration()} and, in repeat mode, again by
     * {@link #processTick()} every time the previous roll finishes. */
    private void startRoll() {
        if (!(level instanceof ServerLevel serverLevel) || selectedLootTable == null || !pendingRoll.isEmpty()) return;

        LootTable table = isChestLootTable(selectedLootTable) ? resolveLootTable(serverLevel, selectedLootTable) : LootTable.EMPTY;
        if (table == LootTable.EMPTY) {
            lastSelectionValid = false;
            setChanged();
            syncToClients();
            return;
        }
        int rollCost = getRollCost();
        if (chaosTank.getFluidAmount() < rollCost) return;

        chaosTank.drain(rollCost, IFluidHandler.FluidAction.EXECUTE);
        LootParams params = new LootParams.Builder(serverLevel)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(worldPosition))
                .withLuck(luck)
                .create(LootContextParamSets.CHEST);
        List<ItemStack> firstRoll = table.getRandomItems(params);
        pendingRoll = new ArrayList<>(firstRoll);
        possibleItems = samplePossibleItems(table, params, firstRoll);
        progress = 0;
        setChanged();
        syncToClients();
    }

    /** Re-rolls {@code table} a few extra times and dedupes the results into a small pool of items
     * this table could plausibly produce, so the client has something to spin through while a roll
     * is in progress instead of just the one outcome that actually got queued. */
    private static List<ItemStack> samplePossibleItems(LootTable table, LootParams params, List<ItemStack> firstRoll) {
        List<ItemStack> pool = new ArrayList<>();
        addDistinct(pool, firstRoll);
        for (int i = 0; i < POSSIBLE_ITEMS_SAMPLES && pool.size() < MAX_POSSIBLE_ITEMS; i++) {
            addDistinct(pool, table.getRandomItems(params));
        }
        return pool;
    }

    private static void addDistinct(List<ItemStack> pool, List<ItemStack> items) {
        for (ItemStack stack : items) {
            if (stack.isEmpty() || pool.size() >= MAX_POSSIBLE_ITEMS) continue;
            boolean exists = false;
            for (ItemStack existing : pool) {
                if (ItemStack.isSameItemSameComponents(existing, stack)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) pool.add(stack.copyWithCount(1));
        }
    }

    private boolean resolvesToRealTable(ResourceLocation id) {
        return isChestLootTable(id) && level instanceof ServerLevel serverLevel && resolveLootTable(serverLevel, id) != LootTable.EMPTY;
    }

    /** Restricts selectable tables to the {@code chests/} folder (vanilla convention for
     * dungeon/structure/village loot) so players can't roll a block's block-drop table - e.g.
     * {@code minecraft:blocks/chest} - for infinite free blocks. */
    public static boolean isChestLootTable(ResourceLocation id) {
        return id.getPath().startsWith("chests/");
    }

    private static LootTable resolveLootTable(ServerLevel serverLevel, ResourceLocation id) {
        ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, id);
        return serverLevel.getServer().reloadableRegistries().getLootTable(key);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, LootGeneratorBlockEntity be) {
        if (level.isClientSide) return;
        be.processTick();
    }

    private void processTick() {
        if (++ticksSinceStructureCheck >= STRUCTURE_RECHECK_INTERVAL) {
            ticksSinceStructureCheck = 0;
            checkStructure();
        }
        if (!formed) return;

        if (pendingRoll.isEmpty()) {
            if (progress != 0) {
                progress = 0;
                setChanged();
                syncToClients();
            }
            if (running) {
                if (repeatMode) {
                    // No-ops harmlessly (insufficient Chaos, invalid selection, ...) - just retried
                    // again next tick, same as the mid-roll pause below.
                    startRoll();
                } else {
                    running = false;
                    setChanged();
                    syncToClients();
                }
            }
            return;
        }

        // Paused (not aborted) when short on Chaos or inventory space - resumes on its own once
        // either frees up, rather than dropping loot on the ground or losing the roll.
        int itemCost = getItemCost();
        if (chaosTank.getFluidAmount() < itemCost || !canPlace(pendingRoll.get(0))) {
            return;
        }

        progress++;
        if (progress >= maxProgress) {
            chaosTank.drain(itemCost, IFluidHandler.FluidAction.EXECUTE);
            placeStack(pendingRoll.remove(0));
            progress = 0;
        }
        setChanged();
        syncToClients();
    }

    private boolean canPlace(ItemStack stack) {
        for (ItemStack existing : items) {
            if (existing.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(existing, stack) && existing.getCount() < existing.getMaxStackSize()) return true;
        }
        return false;
    }

    private void placeStack(ItemStack stack) {
        for (ItemStack existing : items) {
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, stack)) {
                int move = Math.min(existing.getMaxStackSize() - existing.getCount(), stack.getCount());
                if (move > 0) {
                    existing.grow(move);
                    stack.shrink(move);
                    if (stack.isEmpty()) return;
                }
            }
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).isEmpty()) {
                items.set(i, stack);
                return;
            }
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

    // Container (27-slot chest inventory; see ChronoProjectorBlockEntity for why both this and
    // IItemHandler exist)

    @Override public int getContainerSize() { return items.size(); }
    @Override public boolean isEmpty() {
        for (ItemStack stack : items) if (!stack.isEmpty()) return false;
        return true;
    }
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
        return Component.translatable("block.temporalindustries.loot_generator");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, @NotNull Inventory inventory, @NotNull Player player) {
        return new LootGeneratorMenu(id, inventory, this);
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("ChaosTank", chaosTank.writeToNBT(registries, new CompoundTag()));
        ContainerHelper.saveAllItems(tag, items, registries);
        if (selectedLootTable != null) tag.putString("SelectedLootTable", selectedLootTable.toString());
        tag.putBoolean("SelectionValid", lastSelectionValid);
        tag.putBoolean("Formed", formed);
        tag.putInt("Progress", progress);
        tag.putInt("Luck", luck);
        tag.putBoolean("Running", running);
        tag.putBoolean("RepeatMode", repeatMode);

        NonNullList<ItemStack> pendingList = NonNullList.withSize(pendingRoll.size(), ItemStack.EMPTY);
        for (int i = 0; i < pendingRoll.size(); i++) pendingList.set(i, pendingRoll.get(i));
        CompoundTag pendingTag = new CompoundTag();
        ContainerHelper.saveAllItems(pendingTag, pendingList, registries);
        pendingTag.putInt("Count", pendingList.size());
        tag.put("PendingRoll", pendingTag);

        NonNullList<ItemStack> possibleList = NonNullList.withSize(possibleItems.size(), ItemStack.EMPTY);
        for (int i = 0; i < possibleItems.size(); i++) possibleList.set(i, possibleItems.get(i));
        CompoundTag possibleTag = new CompoundTag();
        ContainerHelper.saveAllItems(possibleTag, possibleList, registries);
        possibleTag.putInt("Count", possibleList.size());
        tag.put("PossibleItems", possibleTag);
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("ChaosTank")) chaosTank.readFromNBT(registries, tag.getCompound("ChaosTank"));
        items.clear();
        ContainerHelper.loadAllItems(tag, items, registries);
        selectedLootTable = tag.contains("SelectedLootTable") ? ResourceLocation.tryParse(tag.getString("SelectedLootTable")) : null;
        lastSelectionValid = tag.getBoolean("SelectionValid");
        formed = tag.getBoolean("Formed");
        progress = tag.getInt("Progress");
        luck = Math.clamp(tag.getInt("Luck"), 0, MAX_LUCK);
        running = tag.getBoolean("Running");
        repeatMode = tag.getBoolean("RepeatMode");

        pendingRoll = new ArrayList<>();
        if (tag.contains("PendingRoll")) {
            CompoundTag pendingTag = tag.getCompound("PendingRoll");
            NonNullList<ItemStack> pendingList = NonNullList.withSize(pendingTag.getInt("Count"), ItemStack.EMPTY);
            ContainerHelper.loadAllItems(pendingTag, pendingList, registries);
            for (ItemStack stack : pendingList) {
                if (!stack.isEmpty()) pendingRoll.add(stack);
            }
        }

        possibleItems = new ArrayList<>();
        if (tag.contains("PossibleItems")) {
            CompoundTag possibleTag = tag.getCompound("PossibleItems");
            NonNullList<ItemStack> possibleList = NonNullList.withSize(possibleTag.getInt("Count"), ItemStack.EMPTY);
            ContainerHelper.loadAllItems(possibleTag, possibleList, registries);
            for (ItemStack stack : possibleList) {
                if (!stack.isEmpty()) possibleItems.add(stack);
            }
        }
    }
}
