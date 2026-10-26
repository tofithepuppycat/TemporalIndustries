package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.EntropyBalancerBlockEntity;
import io.github.tofithepuppycat.temporalindustries.menu.EntropyBalancerMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: sets an Entropy Balancer's target balance and optionally detaches its output. */
public class EntropyBalancerConfigPacket implements CustomPacketPayload {
    public static final Type<EntropyBalancerConfigPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "entropy_balancer_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EntropyBalancerConfigPacket> STREAM_CODEC =
            StreamCodec.of(EntropyBalancerConfigPacket::encode, EntropyBalancerConfigPacket::decode);

    private final BlockPos machinePos;
    private final int target;
    private final boolean clearOutput;

    public EntropyBalancerConfigPacket(BlockPos machinePos, int target, boolean clearOutput) {
        this.machinePos = machinePos;
        this.target = target;
        this.clearOutput = clearOutput;
    }

    public static void encode(RegistryFriendlyByteBuf buf, EntropyBalancerConfigPacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeVarInt(packet.target);
        buf.writeBoolean(packet.clearOutput);
    }

    public static EntropyBalancerConfigPacket decode(RegistryFriendlyByteBuf buf) {
        return new EntropyBalancerConfigPacket(buf.readBlockPos(), buf.readVarInt(), buf.readBoolean());
    }

    public static void handle(EntropyBalancerConfigPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof EntropyBalancerMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;

            BlockEntity be = sender.level().getBlockEntity(packet.machinePos);
            if (!(be instanceof EntropyBalancerBlockEntity balancer)) return;

            balancer.setTarget(packet.target);
            if (packet.clearOutput) balancer.clearOutput();
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
