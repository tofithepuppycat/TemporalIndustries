package io.github.tofithepuppycat.temporalindustries.entropy;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

import java.util.Locale;

/** ORD (order) and CHS (chaos), the two xp-like substances from IDEAS.md. */
public enum EntropyType implements StringRepresentable {
    ORDER(0xfecbe6, 0x54398a),
    CHAOS(0x87f3fb, 0x009295);

    public static final Codec<EntropyType> CODEC = StringRepresentable.fromEnum(EntropyType::values);
    public static final StreamCodec<ByteBuf, EntropyType> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(i -> values()[i], Enum::ordinal);

    private final int color;
    private final int dark_color;

    EntropyType(int color, int dark_color) {
        this.color = color;
        this.dark_color = dark_color;
    }

    public int color() {
        return color;
    }

    public int tint_to() {
        return dark_color;
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
