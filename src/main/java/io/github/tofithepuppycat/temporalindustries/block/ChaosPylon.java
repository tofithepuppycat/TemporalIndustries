package io.github.tofithepuppycat.temporalindustries.block;

import com.mojang.serialization.MapCodec;
import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.world.level.block.BaseEntityBlock;

/** Single-liquid variant of {@link EntropicPylon} that only routes Chaos - see
 * {@link io.github.tofithepuppycat.temporalindustries.block.entity.EntropicPylonBlockEntity#acceptsFluid}. */
public class ChaosPylon extends EntropicPylon {
    private static final MapCodec<ChaosPylon> CODEC = simpleCodec(ChaosPylon::new);

    public ChaosPylon(Properties properties) {
        super(properties, EntropyType.CHAOS, Registration.CHAOS_PYLON_BLOCK_ENTITY);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }
}
