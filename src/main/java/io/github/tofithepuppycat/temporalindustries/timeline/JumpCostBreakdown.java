package io.github.tofithepuppycat.temporalindustries.timeline;

import net.minecraft.network.FriendlyByteBuf;

/** The parts of a timeline jump's energy price: how far the target lies from the present, the
 * blocks the jump rewrites, and the machine's entropic surcharge, plus the inputs each part was
 * computed from. */
public record JumpCostBreakdown(long time, long blocks, long entropy,
                                double minutes, long changedBlocks, double chaos) {
    public long total() {
        return time + blocks + entropy;
    }

    public static void encode(JumpCostBreakdown cost, FriendlyByteBuf buf) {
        buf.writeLong(cost.time);
        buf.writeLong(cost.blocks);
        buf.writeLong(cost.entropy);
        buf.writeDouble(cost.minutes);
        buf.writeLong(cost.changedBlocks);
        buf.writeDouble(cost.chaos);
    }

    public static JumpCostBreakdown decode(FriendlyByteBuf buf) {
        return new JumpCostBreakdown(buf.readLong(), buf.readLong(), buf.readLong(),
                buf.readDouble(), buf.readLong(), buf.readDouble());
    }
}
