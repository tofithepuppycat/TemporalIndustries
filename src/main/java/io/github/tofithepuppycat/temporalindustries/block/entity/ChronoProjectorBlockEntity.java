package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.device.ChronoActionReplayer;
import io.github.tofithepuppycat.temporalindustries.device.ChronoRecording;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyInfoProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.util.FastColor;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Block entity for the Chrono Loop Projector: holds one Chrono Record and, while it has a saved
 * recording and enough stored energy, endlessly replays it: the ghost is rendered client-side,
 * while a server-side fake player ({@link ChronoActionReplayer}) repeats the recorded interactions
 * using items from this block's own inventory. Playback position derives from a synced {@code loopEpoch} rather than a per-tick
 * counter, so nothing needs re-syncing except on start/stop/pause.
 */
@SuppressWarnings("null")
public class ChronoProjectorBlockEntity extends BlockEntity implements Container, EntropyInfoProvider {
    private static final int ENERGY_CAPACITY = 32_000;
    private static final int ENERGY_MAX_RECEIVE = 800;
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

    /** Holds whatever the loop's BREAK actions harvest and whatever items the fake player needs to
     * use (blocks to place, tools, food, potions...). Implements
     * {@link Container} directly so vanilla hoppers can push/pull (they look for a Container, not
     * a capability); {@link #inventory} wraps the same list as an IItemHandler for modded pipes. */
    private final NonNullList<ItemStack> items = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
    private final IItemHandler inventory = new InvWrapper(this);

    /** ARGB tint for this projector's ghost render; recolorable via dye (RGB only, alpha fixed for
     * translucency). Channels are lightened toward white before use since the render multiplies
     * this directly onto the skin texture rather than overlaying it, which would otherwise mostly darken it. */
    private static final int GHOST_ALPHA = 150;
    public static final int DEFAULT_GHOST_COLOR = ghostTint(70, 235, 255);

    private static int ghostTint(int r, int g, int b) {
        return FastColor.ARGB32.color(GHOST_ALPHA, lightenChannel(r), lightenChannel(g), lightenChannel(b));
    }

    private static int lightenChannel(int channel) {
        return channel + Math.round((255 - channel) * 0.55F);
    }

    private int ghostColor = DEFAULT_GHOST_COLOR;

    private ItemStack recorderStack = ItemStack.EMPTY;
    @Nullable private ChronoRecording cachedRecording = null;
    private boolean active = false;
    private long loopEpoch = 0L;
    private int pausedTick = 0;
    /** Not persisted: on reload, playback resumes from wherever loopEpoch/gameTime puts it. */
    private int lastExecutedTick = -1;
    /** The server-side fake player that performs the recording's interactions. Not persisted:
     * rebuilt on demand, and dropped whenever the loop is torn down. */
    @Nullable private ChronoActionReplayer replayer = null;

    public ChronoProjectorBlockEntity(BlockPos pos, BlockState state) {
        super(Registration.CHRONO_PROJECTOR_BLOCK_ENTITY.get(), pos, state);
    }

    public IEnergyStorage getEnergyStorage() {
        return energyStorage;
    }

    public IItemHandler getItemHandler() {
        return inventory;
    }

    public int getGhostColor() {
        return ghostColor;
    }

    /** Recolors this projector's ghost render from a dye's RGB, lightened the same way as {@link #DEFAULT_GHOST_COLOR}. */
    public void setGhostTint(int rgb) {
        ghostColor = ghostTint(FastColor.ARGB32.red(rgb), FastColor.ARGB32.green(rgb), FastColor.ARGB32.blue(rgb));
        setChanged();
        syncToClients();
    }

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

    // Guarded on isClientSide so the client-only renderer class is never loaded on a dedicated server.
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
        shutdownReplayer();
        if (level != null && level.isClientSide) {
            io.github.tofithepuppycat.temporalindustries.client.ChronoGhostRenderer.unregister(this);
        }
    }

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

        long epoch = be.active ? be.loopEpoch : level.getGameTime() - be.pausedTick;
        int frameCount = recording.frameCount();
        long elapsed = level.getGameTime() - epoch;
        int currentTick = (int) (((elapsed % frameCount) + frameCount) % frameCount);

        int cost = be.lastExecutedTick != currentTick
                ? recording.energyCostAt(currentTick)
                : ChronoRecording.ENERGY_PER_TICK;

        if (be.energyStorage.getEnergyStored() >= cost) {
            be.energyStorage.consumeInternal(cost);
            if (!be.active) {
                be.loopEpoch = epoch;
                be.active = true;
                be.lastExecutedTick = -1;
                be.syncToClients();
            }

            if (be.lastExecutedTick != currentTick && level instanceof ServerLevel serverLevel) {
                be.executeActions(serverLevel, recording, currentTick);
                be.lastExecutedTick = currentTick;
            }
        } else if (be.active) {
            be.pausedTick = currentTick;
            be.active = false;
            be.syncToClients();
        }
    }

    /** Actions replay at the recording's own absolute coordinates, not relative to the projector,
     * which may sit far from the plot it's replaying. Interactions are performed by a fake player
     * standing wherever the original player was at this point of the loop. */
    private void executeActions(ServerLevel level, ChronoRecording recording, int tick) {
        ResourceLocation recordingDimension = recording.getDimension();
        if (recordingDimension != null && !recordingDimension.equals(level.dimension().location())) {
            return;
        }

        int anchorX = (int) Math.floor(recording.getStartX());
        int anchorY = (int) Math.floor(recording.getStartY());
        int anchorZ = (int) Math.floor(recording.getStartZ());

        if (replayer == null) {
            replayer = new ChronoActionReplayer(level, inventory, worldPosition, recording);
        }
        ChronoActionReplayer active = replayer;
        // The loop wrapped: nothing from the previous pass (riding, a half-drawn bow) should leak into this one.
        if (tick < lastExecutedTick) active.reset();
        active.beginTick(recording, tick);

        for (ChronoRecording.Action action : recording.actionsAt(tick)) {
            BlockPos pos = new BlockPos(anchorX + action.dx(), anchorY + action.dy(), anchorZ + action.dz());
            switch (action.type()) {
                case INSERT -> executeInsert(level, pos, action.item(), action.count());
                case EXTRACT -> executeExtract(level, pos, action.item(), action.count());
                default -> active.execute(action, pos);
            }
            // An action can tear the loop down mid-tick, e.g. the echo using this very projector to eject its record.
            if (replayer != active) return;
        }

        active.endTick();
    }

    private void shutdownReplayer() {
        if (replayer != null) {
            replayer.reset();
            replayer = null;
        }
    }

    /** Hands `count` of `itemId` from this projector's own inventory to whatever container sits at
     * pos. Silently skips this cycle if the projector doesn't have any of that item. */
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

    /** Takes up to `count` of `itemId` out of whatever container sits at pos into this projector's own inventory. */
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

    /** Removes up to `max` of `item` from the internal inventory, returning how many were actually removed. */
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

    public ItemStack getStoredRecorder() {
        return recorderStack;
    }

    public void insertRecorder(ItemStack stack) {
        this.recorderStack = stack;
        this.cachedRecording = ChronoRecording.fromStack(stack).orElse(null);
        this.pausedTick = 0;
        this.active = false;
        shutdownReplayer();
        setChanged();
        syncToClients();
    }

    public ItemStack removeRecorder() {
        ItemStack result = recorderStack;
        recorderStack = ItemStack.EMPTY;
        cachedRecording = null;
        active = false;
        pausedTick = 0;
        shutdownReplayer();
        setChanged();
        syncToClients();
        return result;
    }

    @Nullable
    public ChronoRecording getCachedRecording() {
        return cachedRecording;
    }

    /** Whether the loop is running or paused (out of energy), plus the recording's average and
     * peak per-tick energy cost so a player can judge whether their power supply can sustain it. */
    @Override
    public List<Component> getEntropyTooltip() {
        if (cachedRecording == null) {
            return List.of(Component.translatable("block.temporalindustries.echo_projector.glasses_no_recording")
                    .withStyle(ChatFormatting.GRAY));
        }

        return List.of(
                Component.translatable(active
                                ? "block.temporalindustries.echo_projector.glasses_active"
                                : "block.temporalindustries.echo_projector.glasses_paused")
                        .withStyle(active ? ChatFormatting.GREEN : ChatFormatting.RED),
                EntropyDisplay.colorTokens(Component.translatable("block.temporalindustries.echo_projector.glasses_energy_rate",
                        String.format("%.1f", cachedRecording.averageEnergyPerTick()), cachedRecording.peakEnergyPerTick()).getString(), ChatFormatting.GRAY)
        );
    }

    /** Fractional position within the loop at gameTime + partialTick, or negative if nothing to play back. */
    public double computePlaybackProgress(long gameTime, float partialTick) {
        if (!active || cachedRecording == null) return -1.0;
        int frameCount = cachedRecording.frameCount();
        if (frameCount < 2) return -1.0;

        double ticks = (gameTime - loopEpoch) + partialTick;
        double mod = ticks % frameCount;
        if (mod < 0) mod += frameCount;
        return mod;
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
        loadWithComponents(tag, registries);
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

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
        if (ghostColor != DEFAULT_GHOST_COLOR) tag.putInt("GhostColor", ghostColor);
        ContainerHelper.saveAllItems(tag, items, registries);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("RecorderItem")) {
            recorderStack = ItemStack.parseOptional(registries, tag.getCompound("RecorderItem"));
            cachedRecording = ChronoRecording.fromStack(recorderStack).orElse(null);
        } else {
            recorderStack = ItemStack.EMPTY;
            cachedRecording = null;
        }
        active = tag.getBoolean("Active");
        loopEpoch = tag.getLong("LoopEpoch");
        pausedTick = tag.getInt("PausedTick");
        if (tag.contains("Energy")) energyStorage.deserializeNBT(registries, tag.get("Energy"));
        ghostColor = tag.contains("GhostColor") ? tag.getInt("GhostColor") : DEFAULT_GHOST_COLOR;
        items.clear();
        ContainerHelper.loadAllItems(tag, items, registries);
    }
}
