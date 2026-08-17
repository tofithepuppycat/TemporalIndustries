package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.EntropyCondenserBlockEntity;
import io.github.tofithepuppycat.temporalindustries.menu.EntropyCondenserMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: set an Entropy Condenser's absorb range (see the GUI's range control), clamped
 * to {@link EntropyCondenserBlockEntity#MIN_RANGE}..{@link EntropyCondenserBlockEntity#MAX_RANGE}. */
public class EntropyCondenserSetRangePacket implements CustomPacketPayload {
    public static final Type<EntropyCondenserSetRangePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "entropy_condenser_set_range"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EntropyCondenserSetRangePacket> STREAM_CODEC =
            StreamCodec.of(EntropyCondenserSetRangePacket::encode, EntropyCondenserSetRangePacket::decode);

    private final BlockPos machinePos;
    private final int range;

    public EntropyCondenserSetRangePacket(BlockPos machinePos, int range) {
        this.machinePos = machinePos;
        this.range = range;
    }

    public static void encode(RegistryFriendlyByteBuf buf, EntropyCondenserSetRangePacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeVarInt(packet.range);
    }

    public static EntropyCondenserSetRangePacket decode(RegistryFriendlyByteBuf buf) {
        return new EntropyCondenserSetRangePacket(buf.readBlockPos(), buf.readVarInt());
    }

    public static void handle(EntropyCondenserSetRangePacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof EntropyCondenserMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;

            BlockEntity be = sender.level().getBlockEntity(packet.machinePos);
            if (!(be instanceof EntropyCondenserBlockEntity condenser)) return;

            condenser.setRange(packet.range);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
