package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.EntropyManipulatorBlockEntity;
import io.github.tofithepuppycat.temporalindustries.menu.EntropyManipulatorMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -> server: press the Entropy Manipulator's recipe-select icon, advancing {@link
 * EntropyManipulatorBlockEntity#cyclePreferredRecipe()} to the next matching output for the current
 * input item.
 */
public class EntropyManipulatorCycleRecipePacket implements CustomPacketPayload {
    public static final Type<EntropyManipulatorCycleRecipePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "entropy_manipulator_cycle_recipe"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EntropyManipulatorCycleRecipePacket> STREAM_CODEC =
            StreamCodec.of(EntropyManipulatorCycleRecipePacket::encode, EntropyManipulatorCycleRecipePacket::decode);

    private final BlockPos machinePos;

    public EntropyManipulatorCycleRecipePacket(BlockPos machinePos) {
        this.machinePos = machinePos;
    }

    public static void encode(RegistryFriendlyByteBuf buf, EntropyManipulatorCycleRecipePacket packet) {
        buf.writeBlockPos(packet.machinePos);
    }

    public static EntropyManipulatorCycleRecipePacket decode(RegistryFriendlyByteBuf buf) {
        return new EntropyManipulatorCycleRecipePacket(buf.readBlockPos());
    }

    public static void handle(EntropyManipulatorCycleRecipePacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof EntropyManipulatorMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;

            BlockEntity be = sender.level().getBlockEntity(packet.machinePos);
            if (!(be instanceof EntropyManipulatorBlockEntity manipulator)) return;

            manipulator.cyclePreferredRecipe();
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
