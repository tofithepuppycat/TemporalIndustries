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

/** Client -> server: press the Loot Generator's single/repeat mode icon, flipping
 * {@link LootGeneratorBlockEntity#isRepeatMode()}. */
public class LootGeneratorToggleRepeatPacket implements CustomPacketPayload {
    public static final Type<LootGeneratorToggleRepeatPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "loot_generator_toggle_repeat"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LootGeneratorToggleRepeatPacket> STREAM_CODEC =
            StreamCodec.of(LootGeneratorToggleRepeatPacket::encode, LootGeneratorToggleRepeatPacket::decode);

    private final BlockPos machinePos;

    public LootGeneratorToggleRepeatPacket(BlockPos machinePos) {
        this.machinePos = machinePos;
    }

    public static void encode(RegistryFriendlyByteBuf buf, LootGeneratorToggleRepeatPacket packet) {
        buf.writeBlockPos(packet.machinePos);
    }

    public static LootGeneratorToggleRepeatPacket decode(RegistryFriendlyByteBuf buf) {
        return new LootGeneratorToggleRepeatPacket(buf.readBlockPos());
    }

    public static void handle(LootGeneratorToggleRepeatPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof LootGeneratorMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;

            BlockEntity be = sender.level().getBlockEntity(packet.machinePos);
            if (!(be instanceof LootGeneratorBlockEntity generator)) return;

            generator.setRepeatMode(!generator.isRepeatMode());
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
