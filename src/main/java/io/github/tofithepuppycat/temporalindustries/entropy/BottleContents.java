package io.github.tofithepuppycat.temporalindustries.entropy;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** How much of a single {@link EntropyType} an Order/Chaos Bottle is holding, stored as a data component on the stack. */
public record BottleContents(int amount) {
    public static final BottleContents EMPTY = new BottleContents(0);
    public static final int CAPACITY = 4_000;

    public static final Codec<BottleContents> CODEC = Codec.INT.xmap(BottleContents::new, BottleContents::amount);

    public static final StreamCodec<ByteBuf, BottleContents> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, BottleContents::amount,
            BottleContents::new
    );
}
