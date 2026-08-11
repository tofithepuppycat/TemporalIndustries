package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.device.ChronoRecording;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Block entity for the Chrono Loop Projector: holds one Chrono Recorder and, while it has a
 * saved recording and enough stored energy, endlessly replays it via {@link #getCachedRecording()}
 * / {@link #computePlaybackProgress}, consumed client-side by the ghost renderer.
 *
 * Playback position is derived from a synced {@code loopEpoch} (the game time at which loop-tick 0
 * happened) rather than a per-tick counter, so nothing needs to be re-synced every tick — only
 * when the loop starts, stops, or is paused/resumed for lack of energy.
 */
@SuppressWarnings("null")
public class ChronoProjectorBlockEntity extends BlockEntity implements Container {
    private static final int ENERGY_CAPACITY = 32_000;
    private static final int ENERGY_MAX_RECEIVE = 800;
    private static final int ENERGY_PER_TICK = 10;
    private static final int INVENTORY_SIZE = 9;

    private final class ProjectorEnergyStorage extends EnergyStorage {
        ProjectorEnergyStorage() {
            super(ENERGY_CAPACITY, ENERGY_MAX_RECEIVE, 0);
        }

        @Override public int receiveEnergy(int max, boolean simulate) {
            int v = super.receiveEnergy(max, simulate);
            if (!simulate && v > 0) setChanged();
            return v;
        }

        void consumeInternal(int amount) {
            if (amount <= 0) return;
            energy = Math.max(0, energy - amount);
            setChanged();
        }
    }

    private final ProjectorEnergyStorage energyStorage = new ProjectorEnergyStorage();

    /** Holds whatever the loop's BREAK actions harvest and whatever its PLACE actions consume.
     * Implementing {@link Container} directly (rather than only exposing the NeoForge IItemHandler
     * capability) is what lets vanilla hoppers actually push/pull items here — hoppers look for a
     * Container, not a capability. {@link #inventory} wraps this same backing list as an
     * IItemHandler for modded item pipes, so both interop paths share one source of truth. */
    private final NonNullList<ItemStack> items = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
    private final IItemHandler inventory = new InvWrapper(this);

    private ItemStack recorderStack = ItemStack.EMPTY;
    @Nullable private ChronoRecording cachedRecording = null;
    private boolean active = false;
    private long loopEpoch = 0L;
    private int pausedTick = 0;
    /** Not persisted: on reload, playback simply resumes from wherever loopEpoch/gameTime puts it,
     * re-running at most one tick's worth of actions it would otherwise have missed. */
    private int lastExecutedTick = -1;

    public ChronoProjectorBlockEntity(BlockPos pos, BlockState state) {
        super(Registration.CHRONO_PROJECTOR_BLOCK_ENTITY.get(), pos, state);
    }

    public IEnergyStorage getEnergyStorage() {
        return energyStorage;
    }

    public IItemHandler getItemHandler() {
        return inventory;
    }

    // -------------------------------------------------------------------------
    // Container (see the `items`/`inventory` field doc for why this exists alongside IItemHandler)

    @Override public int getContainerSize() { return items.size(); }
    @Override public boolean isEmpty() { return items.stream().allMatch(ItemStack::isEmpty); }
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

    // -------------------------------------------------------------------------
    // Client-side ghost renderer registry (see ChronoGhostRenderer) — guarded on isClientSide so
    // the client-only renderer class is never touched, let alone loaded, on a dedicated server.

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide) {
            io.github.tofithepuppycat.temporalindustries.client.ChronoGhostRenderer.register(this);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && level.isClientSide) {
            io.github.tofithepuppycat.temporalindustries.client.ChronoGhostRenderer.unregister(this);
        }
    }

    // -------------------------------------------------------------------------
    // Tick

    public static void tick(Level level, BlockPos pos, BlockState state, ChronoProjectorBlockEntity be) {
        if (level.isClientSide) return;

        ChronoRecording recording = be.cachedRecording;
        if (recording == null || recording.frameCount() < 2) {
            if (be.active) {
                be.active = false;
                be.syncToClients();
            }
            return;
        }

        if (be.energyStorage.getEnergyStored() >= ENERGY_PER_TICK) {
            be.energyStorage.consumeInternal(ENERGY_PER_TICK);
            if (!be.active) {
                be.loopEpoch = level.getGameTime() - be.pausedTick;
                be.active = true;
                be.lastExecutedTick = -1;
                be.syncToClients();
            }

            int frameCount = recording.frameCount();
            long elapsed = level.getGameTime() - be.loopEpoch;
            int currentTick = (int) (((elapsed % frameCount) + frameCount) % frameCount);
            if (be.lastExecutedTick != currentTick && level instanceof ServerLevel serverLevel) {
                be.executeActions(serverLevel, recording, currentTick);
                be.lastExecutedTick = currentTick;
            }
        } else if (be.active) {
            int frameCount = recording.frameCount();
            long elapsed = level.getGameTime() - be.loopEpoch;
            be.pausedTick = (int) (((elapsed % frameCount) + frameCount) % frameCount);
            be.active = false;
            be.syncToClients();
        }
    }

    // -------------------------------------------------------------------------
    // Action execution (break/place) — see ChronoActionRecorder for how these get recorded

    /** Actions replay at the recording's own absolute coordinates (where the player actually broke/
     * placed things), not relative to the projector — the projector is just the engine holding the
     * recorder, energy and inventory; it may sit far from the plot it's replaying. */
    private void executeActions(ServerLevel level, ChronoRecording recording, int tick) {
        ResourceLocation recordingDimension = recording.getDimension();
        if (recordingDimension != null && !recordingDimension.equals(level.dimension().location())) {
            return;
        }

        int anchorX = (int) Math.floor(recording.getStartX());
        int anchorY = (int) Math.floor(recording.getStartY());
        int anchorZ = (int) Math.floor(recording.getStartZ());

        for (ChronoRecording.Action action : recording.actionsAt(tick)) {
            BlockPos pos = new BlockPos(anchorX + action.dx(), anchorY + action.dy(), anchorZ + action.dz());
            switch (action.type()) {
                case BREAK -> executeBreak(level, pos, action.tool());
                case PLACE -> executePlace(level, pos, action.item(), action.blockState(), action.blockEntity());
                case MODIFY -> executeModify(level, pos, action.blockState(), action.blockEntity());
                case INSERT -> executeInsert(level, pos, action.item(), action.count());
                case EXTRACT -> executeExtract(level, pos, action.item(), action.count());
            }
        }
    }

    /** {@code toolId}, if recorded, is replayed as a phantom tool purely so blocks that gate their
     * loot table on the harvesting tool ({@code requiresCorrectToolForDrops}) drop correctly —
     * modded ores/machines commonly do this. It's a fresh, undamaged, unenchanted stack of that
     * item and is never pulled from (or returned to) the projector's own inventory. */
    private void executeBreak(ServerLevel level, BlockPos pos, @Nullable ResourceLocation toolId) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return;

        ItemStack tool = toolId != null ? new ItemStack(BuiltInRegistries.ITEM.get(toolId)) : ItemStack.EMPTY;
        List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos), null, tool);
        level.removeBlock(pos, false);

        for (ItemStack drop : drops) {
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(inventory, drop, false);
            if (!remainder.isEmpty()) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), remainder);
            }
        }
    }

    /** Reproduces the exact recorded BlockState (orientation, connections, ...) and block entity
     * data, not just the item's default block — important for modded machines/pipes/oriented
     * blocks where "some instance of this block" isn't good enough. Falls back to the block's
     * default state for older recordings made before this was captured. */
    private void executePlace(ServerLevel level, BlockPos pos, @Nullable ResourceLocation itemId,
                               @Nullable CompoundTag blockStateTag, @Nullable CompoundTag blockEntityTag) {
        if (itemId == null || !level.getBlockState(pos).isAir()) return;

        Item item = BuiltInRegistries.ITEM.get(itemId);
        if (!(item instanceof BlockItem blockItem)) return;
        if (extractUpToMatching(item, 1) <= 0) return;

        BlockState placeState = blockStateTag != null
                ? NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), blockStateTag)
                : blockItem.getBlock().defaultBlockState();
        level.setBlock(pos, placeState, 3);

        restoreBlockEntity(level, pos, blockEntityTag);
    }

    /** Reproduces a right-click block transform recorded generically as a before/after state diff
     * (axe-stripping, hoe-tilling, honeycomb-waxing, a modded machine applying a casing to itself,
     * ...) — see ChronoActionRecorder. Unlike PLACE, nothing is consumed from the projector's own
     * inventory: whatever item caused this was only ever incidental to the transform, not something
     * the ghost needs to physically hold. */
    private void executeModify(ServerLevel level, BlockPos pos, @Nullable CompoundTag blockStateTag,
                                @Nullable CompoundTag blockEntityTag) {
        if (blockStateTag == null || level.getBlockState(pos).isAir()) return;

        BlockState newState = NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), blockStateTag);
        level.setBlock(pos, newState, 3);

        restoreBlockEntity(level, pos, blockEntityTag);
    }

    private void restoreBlockEntity(ServerLevel level, BlockPos pos, @Nullable CompoundTag blockEntityTag) {
        if (blockEntityTag == null) return;

        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            CompoundTag restored = blockEntityTag.copy();
            restored.putInt("x", pos.getX());
            restored.putInt("y", pos.getY());
            restored.putInt("z", pos.getZ());
            be.loadWithComponents(restored, level.registryAccess());
            be.setChanged();
        }
    }

    /** Hands `count` of `itemId` from this projector's own inventory to whatever container sits at
     * pos (furnace fuel/input, hopper, chest, ...). Silently does nothing if the projector doesn't
     * have any of that item — per design, a missing ingredient just skips this cycle rather than
     * stalling or erroring. */
    private void executeInsert(ServerLevel level, BlockPos pos, @Nullable ResourceLocation itemId, int count) {
        if (itemId == null || count <= 0) return;
        IItemHandler target = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (target == null) return;

        Item item = BuiltInRegistries.ITEM.get(itemId);
        int available = extractUpToMatching(item, count);
        if (available <= 0) return;

        ItemStack leftover = ItemHandlerHelper.insertItemStacked(target, new ItemStack(item, available), false);
        if (!leftover.isEmpty()) {
            ItemStack notReinserted = ItemHandlerHelper.insertItemStacked(inventory, leftover, false);
            if (!notReinserted.isEmpty()) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), notReinserted);
            }
        }
    }

    /** Takes up to `count` of `itemId` out of whatever container sits at pos (e.g. a furnace's
     * finished output) and into this projector's own inventory. */
    private void executeExtract(ServerLevel level, BlockPos pos, @Nullable ResourceLocation itemId, int count) {
        if (itemId == null || count <= 0) return;
        IItemHandler target = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (target == null) return;

        Item item = BuiltInRegistries.ITEM.get(itemId);
        int remaining = count;
        for (int slot = 0; slot < target.getSlots() && remaining > 0; slot++) {
            if (!target.getStackInSlot(slot).is(item)) continue;

            ItemStack extracted = target.extractItem(slot, remaining, false);
            if (extracted.isEmpty()) continue;
            remaining -= extracted.getCount();

            ItemStack leftover = ItemHandlerHelper.insertItemStacked(inventory, extracted, false);
            if (!leftover.isEmpty()) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), leftover);
            }
        }
    }

    /** Removes up to `max` of `item` from the internal inventory, returning how many were actually
     * removed. */
    private int extractUpToMatching(Item item, int max) {
        int remaining = max;
        for (int slot = 0; slot < inventory.getSlots() && remaining > 0; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty() || !stack.is(item)) continue;
            ItemStack extracted = inventory.extractItem(slot, remaining, false);
            remaining -= extracted.getCount();
        }
        return max - remaining;
    }

    // -------------------------------------------------------------------------
    // Insert / remove (jukebox-style interaction, see ChronoProjector)

    public ItemStack getStoredRecorder() {
        return recorderStack;
    }

    public void insertRecorder(ItemStack stack) {
        this.recorderStack = stack;
        this.cachedRecording = ChronoRecording.fromStack(stack).orElse(null);
        this.pausedTick = 0;
        this.active = false;
        setChanged();
        syncToClients();
    }

    public ItemStack removeRecorder() {
        ItemStack result = recorderStack;
        recorderStack = ItemStack.EMPTY;
        cachedRecording = null;
        active = false;
        pausedTick = 0;
        setChanged();
        syncToClients();
        return result;
    }

    // -------------------------------------------------------------------------
    // Playback query (used by the client-side ghost renderer)

    @Nullable
    public ChronoRecording getCachedRecording() {
        return cachedRecording;
    }

    public boolean isLoopActive() {
        return active;
    }

    /** Fractional position within the loop ([0, frameCount)) at gameTime + partialTick, or a
     * negative number if there's nothing to play back right now. */
    public double computePlaybackProgress(long gameTime, float partialTick) {
        if (!active || cachedRecording == null) return -1.0;
        int frameCount = cachedRecording.frameCount();
        if (frameCount < 2) return -1.0;

        double ticks = (gameTime - loopEpoch) + partialTick;
        double mod = ticks % frameCount;
        if (mod < 0) mod += frameCount;
        return mod;
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
        loadWithComponents(tag, registries);
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // -------------------------------------------------------------------------
    // NBT

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!recorderStack.isEmpty()) {
            tag.put("RecorderItem", recorderStack.save(registries));
        }
        tag.putBoolean("Active", active);
        tag.putLong("LoopEpoch", loopEpoch);
        tag.putInt("PausedTick", pausedTick);
        tag.put("Energy", energyStorage.serializeNBT(registries));
        ContainerHelper.saveAllItems(tag, items, registries);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("RecorderItem")) {
            recorderStack = ItemStack.parseOptional(registries, tag.getCompound("RecorderItem"));
            cachedRecording = ChronoRecording.fromStack(recorderStack).orElse(null);
        }
        active = tag.getBoolean("Active");
        loopEpoch = tag.getLong("LoopEpoch");
        pausedTick = tag.getInt("PausedTick");
        if (tag.contains("Energy")) energyStorage.deserializeNBT(registries, tag.get("Energy"));
        items.clear();
        ContainerHelper.loadAllItems(tag, items, registries);
    }
}
