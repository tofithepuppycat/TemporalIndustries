package io.github.tofithepuppycat.temporalindustries.block;

import com.mojang.serialization.MapCodec;
import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.MachineFrameBlockEntity;
import io.github.tofithepuppycat.temporalindustries.block.entity.MachineFrameController;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Generic structural filler block used to complete multiblock machines, starting with the
 * {@link LootGenerator}. Carries no behavior of its own beyond forwarding clicks to whatever
 * controller its {@link MachineFrameBlockEntity} currently points at - machines scan for its
 * presence at the expected structure positions. */
public class MachineFrame extends BaseEntityBlock {
    // Drives which model/texture is used - the Fusion connected-textures casing while this frame is
    // confirmed part of a formed multiblock, or a plain non-connecting texture while it isn't, so a
    // stray/broken frame never visually blends into a structure it isn't actually completing. Kept
    // up to date by the controller re-scanning its structure - see
    // LootGeneratorBlockEntity#checkStructure.
    public static final BooleanProperty CONNECTED = BooleanProperty.create("connected");

    private static final MapCodec<MachineFrame> CODEC = simpleCodec(MachineFrame::new);

    public MachineFrame(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(CONNECTED, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CONNECTED);
    }

    /** Pushes whether {@code pos} should render as part of a formed multiblock, no-oping if it's
     * already showing that state or isn't actually a MachineFrame (e.g. the position turned out to
     * be missing). */
    public static void setConnected(Level level, BlockPos pos, boolean connected) {
        BlockState state = level.getBlockState(pos);
        if (state.is(Registration.MACHINE_FRAME_BLOCK.get()) && state.getValue(CONNECTED) != connected) {
            level.setBlock(pos, state.setValue(CONNECTED, connected), Block.UPDATE_CLIENTS);
        }
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
