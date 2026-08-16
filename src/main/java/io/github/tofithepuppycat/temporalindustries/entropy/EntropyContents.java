package io.github.tofithepuppycat.temporalindustries.entropy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** How much ORD and CHS an Entropy Container item is holding, stored as a data component on the stack. */
public record EntropyContents(int order, int chaos) {
    public static final EntropyContents EMPTY = new EntropyContents(0, 0);
    public static final int CAPACITY = 10_000;

    public static final Codec<EntropyContents> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("order").forGetter(EntropyContents::order),
            Codec.INT.fieldOf("chaos").forGetter(EntropyContents::chaos)
    ).apply(instance, EntropyContents::new));

    public static final StreamCodec<ByteBuf, EntropyContents> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, EntropyContents::order,
            ByteBufCodecs.VAR_INT, EntropyContents::chaos,
            EntropyContents::new
    );

    public int amount(EntropyType type) {
        return type == EntropyType.ORDER ? order : chaos;
    }

    public EntropyContents with(EntropyType type, int amount) {
        return type == EntropyType.ORDER ? new EntropyContents(amount, chaos) : new EntropyContents(order, amount);
    }
}
