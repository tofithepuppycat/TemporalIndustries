package io.github.tofithepuppycat.temporalindustries.block;

import com.mojang.serialization.MapCodec;
import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.world.level.block.BaseEntityBlock;

/** Single-liquid variant of {@link EntropicPylon} that only routes Order - see
 * {@link io.github.tofithepuppycat.temporalindustries.block.entity.EntropicPylonBlockEntity#acceptsFluid}. */
public class OrderPylon extends EntropicPylon {
    private static final MapCodec<OrderPylon> CODEC = simpleCodec(OrderPylon::new);

    public OrderPylon(Properties properties) {
        super(properties, EntropyType.ORDER, Registration.ORDER_PYLON_BLOCK_ENTITY);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }
}
