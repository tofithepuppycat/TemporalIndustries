package io.github.tofithepuppycat.temporalindustries.entropy;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** How many mB of a single {@link EntropyType}'s liquid form an Order/Chaos Cell is holding, stored
 * as a data component on the stack. Also reused as a plain persistent+networked int wrapper for the
 * Temporal Anchor's mode, the Temporal Glue's charge progress and a cell's transfer step. */
public record BottleContents(int amount) {
    public static final BottleContents EMPTY = new BottleContents(0);
    public static final int CAPACITY = 5_000;

    public static final Codec<BottleContents> CODEC = Codec.INT.xmap(BottleContents::new, BottleContents::amount);

    public static final StreamCodec<ByteBuf, BottleContents> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, BottleContents::amount,
            BottleContents::new
    );
}
