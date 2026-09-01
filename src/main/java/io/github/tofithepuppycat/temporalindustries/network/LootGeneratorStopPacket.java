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

/** Client -> server: press the Loot Generator's stop icon, triggering
 * {@link LootGeneratorBlockEntity#stopGeneration()}. */
public class LootGeneratorStopPacket implements CustomPacketPayload {
    public static final Type<LootGeneratorStopPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "loot_generator_stop"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LootGeneratorStopPacket> STREAM_CODEC =
            StreamCodec.of(LootGeneratorStopPacket::encode, LootGeneratorStopPacket::decode);

    private final BlockPos machinePos;

    public LootGeneratorStopPacket(BlockPos machinePos) {
        this.machinePos = machinePos;
    }

    public static void encode(RegistryFriendlyByteBuf buf, LootGeneratorStopPacket packet) {
        buf.writeBlockPos(packet.machinePos);
    }

    public static LootGeneratorStopPacket decode(RegistryFriendlyByteBuf buf) {
        return new LootGeneratorStopPacket(buf.readBlockPos());
    }

    public static void handle(LootGeneratorStopPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof LootGeneratorMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;

            BlockEntity be = sender.level().getBlockEntity(packet.machinePos);
            if (!(be instanceof LootGeneratorBlockEntity generator)) return;

            generator.stopGeneration();
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
