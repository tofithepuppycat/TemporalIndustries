package io.github.tofithepuppycat.temporalindustries.block;

import com.mojang.serialization.MapCodec;
import io.github.tofithepuppycat.temporalindustries.block.entity.ChronodialBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The single-block-tier time machine: no chunk tracking, no branching timeline (see
 * TemporalTimeline) — just one marker on the block its front face points at. Sneak-right-click
 * sets the marker to that block's current state; a plain right-click restores it, paying an
 * energy cost drawn from {@link io.github.tofithepuppycat.temporalindustries.energy.ItemEnergyCosts}.
 */
@SuppressWarnings("null")
public class Chronodial extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    private static final MapCodec<Chronodial> CODEC = simpleCodec(Chronodial::new);

    public Chronodial(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite());
    }

    @Override
    public RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useWithoutItem(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos,
                                                @NotNull Player player, @NotNull BlockHitResult hitResult) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        if (!(level.getBlockEntity(pos) instanceof ChronodialBlockEntity chronodial)) {
            return InteractionResult.PASS;
        }

        if (player.isShiftKeyDown()) {
            chronodial.setMarker();
            player.displayClientMessage(Component.translatable("block.temporalindustries.chronodial.marker_set"), true);
            level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1.0F, 1.2F);
            return InteractionResult.CONSUME;
        }

        ChronodialBlockEntity.JumpResult result = chronodial.jumpToMarker();
        switch (result) {
            case SUCCESS -> level.playSound(null, pos, SoundEvents.PORTAL_TRAVEL, SoundSource.BLOCKS, 1.0F, 1.0F);
            case ALREADY_AT_MARKER -> player.displayClientMessage(
                    Component.translatable("block.temporalindustries.chronodial.already_at_marker"), true);
            case NO_MARKER -> player.displayClientMessage(
                    Component.translatable("block.temporalindustries.chronodial.no_marker"), true);
            case INSUFFICIENT_ENERGY -> {
                player.displayClientMessage(Component.translatable("block.temporalindustries.chronodial.insufficient_energy"), true);
                level.playSound(null, pos, SoundEvents.VILLAGER_NO, SoundSource.BLOCKS, 1.0F, 1.0F);
            }
        }
        return InteractionResult.CONSUME;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new ChronodialBlockEntity(pos, state);
    }
}
