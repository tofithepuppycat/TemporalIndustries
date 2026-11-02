package io.github.tofithepuppycat.temporalindustries.block;

import com.mojang.serialization.MapCodec;
import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.EntropicPylonBlockEntity;
import io.github.tofithepuppycat.temporalindustries.block.entity.MachineFrameBlockEntity;
import io.github.tofithepuppycat.temporalindustries.block.entity.MachineFrameController;
import net.minecraft.core.BlockPos;
import net.minecraft.util.StringRepresentable;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Generic structural filler block used to complete multiblock machines, starting with the {@link
 * LootGenerator}.
 */
public class MachineFrame extends BaseEntityBlock {
    // Whether this frame is confirmed part of a formed multiblock (drives connected-texture rendering).
    public static final BooleanProperty CONNECTED = BooleanProperty.create("connected");

    /** Which pylon, if any, this frame forms the lower half of. */
    public static final EnumProperty<PylonStyle> PYLON = EnumProperty.create("pylon", PylonStyle.class);

    /** Whether this frame is the lowest of the frames extending a pylon, and so carries its base plate. */
    public static final BooleanProperty PYLON_BASE = BooleanProperty.create("pylon_base");

    /** Lowest frame of a pylon extension: base plate, step and the column. */
    private static final VoxelShape PYLON_BASE_SHAPE = Shapes.or(
            Block.box(0, 0, 0, 16, 2, 16),
            Block.box(3, 2, 3, 13, 4, 13),
            Block.box(5, 4, 5, 11, 16, 11));

    /** Any other frame of a pylon extension: just the column. */
    private static final VoxelShape PYLON_COLUMN_SHAPE = Block.box(5, 0, 5, 11, 16, 11);

    public enum PylonStyle implements StringRepresentable {
        NONE("none"), ENTROPIC("entropic"), CHAOS("chaos"), ORDER("order"), ENERGY("energy");

        private final String name;

        PylonStyle(String name) {
            this.name = name;
        }

        @Override
        public @NotNull String getSerializedName() {
            return name;
        }
    }

    private static final MapCodec<MachineFrame> CODEC = simpleCodec(MachineFrame::new);

    public MachineFrame(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(CONNECTED, false).setValue(PYLON, PylonStyle.NONE).setValue(PYLON_BASE, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CONNECTED, PYLON, PYLON_BASE);
    }

    /**
     * Sets whether {@code pos} should render as part of a formed multiblock; no-ops if already set
     * or not actually a MachineFrame.
     */
    public static void setConnected(Level level, BlockPos pos, boolean connected) {
        BlockState state = level.getBlockState(pos);
        if (state.is(Registration.MACHINE_FRAME_BLOCK.get())
                && (state.getValue(CONNECTED) != connected || state.getValue(PYLON) != PylonStyle.NONE)) {
            level.setBlock(pos, state.setValue(CONNECTED, connected).setValue(PYLON, PylonStyle.NONE).setValue(PYLON_BASE, false), Block.UPDATE_CLIENTS);
        }
    }

    /**
     * Marks the frame at {@code pos} as part of a pylon's extension of {@code style}, with {@code base}
     * set for the lowest frame, or releases it when {@code style} is {@link PylonStyle#NONE}; no-ops
     * if already set or not actually a MachineFrame.
     */
    public static void setPylon(Level level, BlockPos pos, PylonStyle style, boolean base) {
        BlockState state = level.getBlockState(pos);
        boolean connected = style != PylonStyle.NONE;
        boolean baseValue = connected && base;
        if (state.is(Registration.MACHINE_FRAME_BLOCK.get())
                && (state.getValue(PYLON) != style || state.getValue(CONNECTED) != connected || state.getValue(PYLON_BASE) != baseValue)) {
            level.setBlock(pos, state.setValue(CONNECTED, connected).setValue(PYLON, style).setValue(PYLON_BASE, baseValue), Block.UPDATE_CLIENTS);
        }
    }

    /** Releases every pylon-styled frame in the run directly below {@code pylonPos}. */
    public static void releasePylonFrames(Level level, BlockPos pylonPos) {
        releaseDownward(level, pylonPos.below());
    }

    private static void releaseDownward(Level level, BlockPos from) {
        BlockPos pos = from;
        while (level.getBlockState(pos).is(Registration.MACHINE_FRAME_BLOCK.get())
                && level.getBlockState(pos).getValue(PYLON) != PylonStyle.NONE) {
            setConnected(level, pos, false);
            pos = pos.below();
        }
    }

    @Override
    protected void onPlace(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos, @NotNull BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!oldState.is(state.getBlock()) && !level.isClientSide) {
            EntropicPylonBlockEntity.refreshAbove(level, pos);
        }
    }

    @Override
    protected void onRemove(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos, @NotNull BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide) {
            if (state.getValue(PYLON) != PylonStyle.NONE) releaseDownward(level, pos.below());
            EntropicPylonBlockEntity.refreshAbove(level, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        if (state.getValue(PYLON) == PylonStyle.NONE) return Shapes.block();
        return state.getValue(PYLON_BASE) ? PYLON_BASE_SHAPE : PYLON_COLUMN_SHAPE;
    }

    /**
     * Auto-consumes {@link Registration#MACHINE_FRAME_ITEM} from {@code player}'s inventory to fill
     * as many of {@code missing} positions as they can afford.
     */
    public static void fillFromInventory(Level level, List<BlockPos> missing, ServerPlayer player) {
        fillFromInventory(level, missing, player, Registration.MACHINE_FRAME_ITEM.get(), Registration.MACHINE_FRAME_BLOCK.get());
    }

    /** Same as above, but fills with an arbitrary {@code item} that places {@code block}. */
    public static void fillFromInventory(Level level, List<BlockPos> missing, ServerPlayer player, Item item, Block block) {
        for (BlockPos pos : missing) {
            if (!level.getBlockState(pos).canBeReplaced()) continue;
            if (!takeOne(player, item)) return;
            level.setBlockAndUpdate(pos, block.defaultBlockState());
        }
    }

    private static boolean takeOne(ServerPlayer player, Item item) {
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(item)) {
                stack.shrink(1);
                return true;
            }
        }
        return false;
    }

    @Override
    public RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new MachineFrameBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos, @NotNull Player player, @NotNull BlockHitResult hitResult) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof MachineFrameBlockEntity frameBe)) return InteractionResult.PASS;

        BlockPos controllerPos = frameBe.getController();
        if (controllerPos == null) return InteractionResult.PASS;

        if (level.getBlockEntity(controllerPos) instanceof MachineFrameController controller && player instanceof ServerPlayer serverPlayer) {
            return controller.onFrameInteract(level, controllerPos, serverPlayer);
        }
        return InteractionResult.PASS;
    }
}
