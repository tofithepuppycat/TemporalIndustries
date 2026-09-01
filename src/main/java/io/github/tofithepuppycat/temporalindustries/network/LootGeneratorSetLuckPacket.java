package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.LootGeneratorBlockEntity;
import io.github.tofithepuppycat.temporalindustries.menu.LootGeneratorMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: drag the Loot Generator's luck slider. The server clamps the value itself (see
 * {@link LootGeneratorBlockEntity#setLuck}) rather than trusting the client's slider position. */
public class LootGeneratorSetLuckPacket implements CustomPacketPayload {
    public static final Type<LootGeneratorSetLuckPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "loot_generator_set_luck"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LootGeneratorSetLuckPacket> STREAM_CODEC =
            StreamCodec.of(LootGeneratorSetLuckPacket::encode, LootGeneratorSetLuckPacket::decode);

    private final BlockPos machinePos;
    private final int luck;

    public LootGeneratorSetLuckPacket(BlockPos machinePos, int luck) {
        this.machinePos = machinePos;
        this.luck = luck;
    }

    public static void encode(RegistryFriendlyByteBuf buf, LootGeneratorSetLuckPacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeVarInt(packet.luck);
    }

    public static LootGeneratorSetLuckPacket decode(RegistryFriendlyByteBuf buf) {
        return new LootGeneratorSetLuckPacket(buf.readBlockPos(), buf.readVarInt());
    }

    public static void handle(LootGeneratorSetLuckPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof LootGeneratorMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;

            BlockEntity be = sender.level().getBlockEntity(packet.machinePos);
            if (!(be instanceof LootGeneratorBlockEntity generator)) return;

            generator.setLuck(packet.luck);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
