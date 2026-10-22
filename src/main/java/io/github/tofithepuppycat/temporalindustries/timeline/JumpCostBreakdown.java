package io.github.tofithepuppycat.temporalindustries.timeline;

import net.minecraft.network.FriendlyByteBuf;

/**
 * The parts of a timeline jump's energy price: how far the target lies from the present, the blocks
 * the jump rewrites, the entities it restores, the container contents it adds, and the machine's
 * entropic surcharge, plus the inputs each part was computed from.
 */
public record JumpCostBreakdown(long time, long blocks, long entities, long containers, long entropy,
                                double minutes, long changedBlocks, int changedEntities, double entityHealth,
                                double containerSlots, double chaos, boolean savesEntities, boolean chargesContainers) {
    public long total() {
        return time + blocks + entities + containers + entropy;
    }

    public static void encode(JumpCostBreakdown cost, FriendlyByteBuf buf) {
        buf.writeLong(cost.time);
        buf.writeLong(cost.blocks);
        buf.writeLong(cost.entities);
        buf.writeLong(cost.containers);
        buf.writeLong(cost.entropy);
        buf.writeDouble(cost.minutes);
        buf.writeLong(cost.changedBlocks);
        buf.writeInt(cost.changedEntities);
        buf.writeDouble(cost.entityHealth);
        buf.writeDouble(cost.containerSlots);
        buf.writeDouble(cost.chaos);
        buf.writeBoolean(cost.savesEntities);
        buf.writeBoolean(cost.chargesContainers);
    }

    public static JumpCostBreakdown decode(FriendlyByteBuf buf) {
        return new JumpCostBreakdown(buf.readLong(), buf.readLong(), buf.readLong(), buf.readLong(), buf.readLong(),
                buf.readDouble(), buf.readLong(), buf.readInt(), buf.readDouble(), buf.readDouble(),
                buf.readDouble(), buf.readBoolean(), buf.readBoolean());
    }
}
