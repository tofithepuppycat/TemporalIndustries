package io.github.tofithepuppycat.temporalindustries.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.Block;

/** Generic structural filler block used to complete multiblock machines, starting with the
 * {@link LootGenerator}. Carries no behavior of its own - machines scan for its presence at the
 * expected structure positions. */
public class MachineFrame extends Block {
    private static final MapCodec<MachineFrame> CODEC = simpleCodec(MachineFrame::new);

    public MachineFrame(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }
}
