package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.item.CellTransfer;
import io.github.tofithepuppycat.temporalindustries.item.DualEntropyCellItem;
import io.github.tofithepuppycat.temporalindustries.item.EntropyCellItem;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: scroll-wheel change of how much liquid entropy one right-click pours out of a
 * cell, sent while the player is sneaking and holding it (see the client-side input handler). */
public class CellTransferAmountPacket implements CustomPacketPayload {
    public static final Type<CellTransferAmountPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "cell_transfer_amount"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CellTransferAmountPacket> STREAM_CODEC =
            StreamCodec.of(CellTransferAmountPacket::encode, CellTransferAmountPacket::decode);

    private final int delta;

    public CellTransferAmountPacket(int delta) {
        this.delta = delta;
    }

    public static void encode(RegistryFriendlyByteBuf buf, CellTransferAmountPacket packet) {
        buf.writeVarInt(packet.delta);
    }

    public static CellTransferAmountPacket decode(RegistryFriendlyByteBuf buf) {
        return new CellTransferAmountPacket(buf.readVarInt());
    }

    public static void handle(CellTransferAmountPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            ItemStack stack = sender.getMainHandItem();
            if (!(stack.getItem() instanceof EntropyCellItem) && !(stack.getItem() instanceof DualEntropyCellItem)) return;

            int amount = CellTransfer.cycle(stack, packet.delta);
            sender.displayClientMessage(Component.translatable("item.temporalindustries.cell.transfer",
                    EntropyDisplay.formatFluid(amount)), true);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
