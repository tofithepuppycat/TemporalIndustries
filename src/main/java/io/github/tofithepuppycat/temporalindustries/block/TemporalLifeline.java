package io.github.tofithepuppycat.temporalindustries.block;

import com.mojang.serialization.MapCodec;
import io.github.tofithepuppycat.temporalindustries.block.entity.TemporalLifelineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Tethers the chunks of an adjacent {@link Chronosphere}'s repository against explosions: while
 * touching a Chronosphere with any face, explosions cannot destroy blocks inside that
 * Chronosphere's claimed chunks (see {@link
 * io.github.tofithepuppycat.temporalindustries.device.TemporalLifelineListener}).
 */
public class TemporalLifeline extends BaseEntityBlock {
    private static final MapCodec<TemporalLifeline> CODEC = simpleCodec(TemporalLifeline::new);

    public TemporalLifeline(Properties properties) {
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
        return new TemporalLifelineBlockEntity(pos, state);
    }
}
