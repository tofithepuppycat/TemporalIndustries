package io.github.tofithepuppycat.temporalindustries.entropy;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

import java.util.Locale;

/** ORD (order) and CHS (chaos), the two xp-like substances from IDEAS.md. */
public enum EntropyType implements StringRepresentable {
    ORDER(0xfecbe6, 0xcfa0f3),
    CHAOS(0x87f3fb, 0x009295);

    public static final Codec<EntropyType> CODEC = StringRepresentable.fromEnum(EntropyType::values);
    public static final StreamCodec<ByteBuf, EntropyType> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(i -> values()[i], Enum::ordinal);

    private final int color;
    private final int tint_to;

    EntropyType(int color, int tint_to) {
        this.color = color;
        this.tint_to = tint_to;
    }

    public int color() {
        return color;
    }

    public int tint_to() {
        return tint_to;
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
