package io.github.tofithepuppycat.temporalindustries.block;

import com.mojang.serialization.MapCodec;
import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.EnergyPylonBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Variant of {@link EntropicPylon} that routes FE instead of liquid entropy. */
@SuppressWarnings("null")
public class EnergyPylon extends EntropicPylon {
    private static final MapCodec<EnergyPylon> CODEC = simpleCodec(EnergyPylon::new);

    public EnergyPylon(Properties properties) {
        super(properties, null, Registration.ENERGY_PYLON_BLOCK_ENTITY);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new EnergyPylonBlockEntity(Registration.ENERGY_PYLON_BLOCK_ENTITY.get(), pos, state);
    }
}
