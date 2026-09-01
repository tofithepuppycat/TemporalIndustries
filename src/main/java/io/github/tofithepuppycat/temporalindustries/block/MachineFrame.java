package io.github.tofithepuppycat.temporalindustries.block;

import com.mojang.serialization.MapCodec;
import io.github.tofithepuppycat.temporalindustries.block.entity.LootGeneratorBlockEntity;
import io.github.tofithepuppycat.temporalindustries.block.entity.MachineFrameBlockEntity;
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
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Generic structural filler block used to complete multiblock machines, starting with the
 * {@link LootGenerator}. Carries no behavior of its own beyond forwarding clicks to whatever
 * controller its {@link MachineFrameBlockEntity} currently points at - machines scan for its
 * presence at the expected structure positions. */
public class MachineFrame extends BaseEntityBlock {
    private static final MapCodec<MachineFrame> CODEC = simpleCodec(MachineFrame::new);

    public MachineFrame(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
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

        if (level.getBlockEntity(controllerPos) instanceof LootGeneratorBlockEntity controllerBe && player instanceof ServerPlayer serverPlayer) {
            return LootGenerator.interact(level, controllerPos, controllerBe, serverPlayer);
        }
        return InteractionResult.PASS;
    }
}
