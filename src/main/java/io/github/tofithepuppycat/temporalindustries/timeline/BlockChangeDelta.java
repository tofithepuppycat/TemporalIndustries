package io.github.tofithepuppycat.temporalindustries.timeline;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public final class BlockChangeDelta {
    private final BlockPos pos;
    private final BlockState previousState;
    private final BlockState newState;
    @Nullable private final CompoundTag previousBlockEntityTag;
    @Nullable private final CompoundTag newBlockEntityTag;

    public BlockChangeDelta(BlockPos pos, BlockState previousState, BlockState newState,
                            @Nullable CompoundTag previousBlockEntityTag,
                            @Nullable CompoundTag newBlockEntityTag) {
        this.pos = pos;
        this.previousState = previousState;
        this.newState = newState;
        this.previousBlockEntityTag = previousBlockEntityTag == null ? null : previousBlockEntityTag.copy();
        this.newBlockEntityTag = newBlockEntityTag == null ? null : newBlockEntityTag.copy();
    }

    public BlockPos getPos() { return pos; }
    public BlockState getPreviousState() { return previousState; }
    public BlockState getNewState() { return newState; }

    @Nullable
    public CompoundTag getPreviousBlockEntityTag() {
        return previousBlockEntityTag == null ? null : previousBlockEntityTag.copy();
    }

    @Nullable
    public CompoundTag getNewBlockEntityTag() {
        return newBlockEntityTag == null ? null : newBlockEntityTag.copy();
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        BlockState.CODEC.encodeStart(NbtOps.INSTANCE, previousState).result()
                .ifPresent(nbt -> tag.put("prev", nbt));
        BlockState.CODEC.encodeStart(NbtOps.INSTANCE, newState).result()
                .ifPresent(nbt -> tag.put("new", nbt));
        if (previousBlockEntityTag != null) tag.put("prevBE", previousBlockEntityTag.copy());
        if (newBlockEntityTag != null) tag.put("newBE", newBlockEntityTag.copy());
        return tag;
    }

    public static BlockChangeDelta fromTag(CompoundTag tag) {
        BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
        BlockState prev = BlockState.CODEC.parse(NbtOps.INSTANCE, tag.getCompound("prev"))
                .result().orElse(Blocks.AIR.defaultBlockState());
        BlockState next = BlockState.CODEC.parse(NbtOps.INSTANCE, tag.getCompound("new"))
                .result().orElse(Blocks.AIR.defaultBlockState());
        CompoundTag prevBE = tag.contains("prevBE") ? tag.getCompound("prevBE") : null;
        CompoundTag newBE = tag.contains("newBE") ? tag.getCompound("newBE") : null;
        return new BlockChangeDelta(pos, prev, next, prevBE, newBE);
    }

    public static void encode(BlockChangeDelta delta, FriendlyByteBuf buf) {
        buf.writeBlockPos(delta.pos);
        buf.writeVarInt(Block.getId(delta.previousState));
        buf.writeVarInt(Block.getId(delta.newState));
        buf.writeBoolean(delta.previousBlockEntityTag != null);
        if (delta.previousBlockEntityTag != null) buf.writeNbt(delta.previousBlockEntityTag);
        buf.writeBoolean(delta.newBlockEntityTag != null);
        if (delta.newBlockEntityTag != null) buf.writeNbt(delta.newBlockEntityTag);
    }

    public static BlockChangeDelta decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        BlockState prev = Block.stateById(buf.readVarInt());
        BlockState next = Block.stateById(buf.readVarInt());
        CompoundTag prevBE = buf.readBoolean() ? buf.readNbt() : null;
        CompoundTag newBE = buf.readBoolean() ? buf.readNbt() : null;
        return new BlockChangeDelta(pos, prev, next, prevBE, newBE);
    }
}
