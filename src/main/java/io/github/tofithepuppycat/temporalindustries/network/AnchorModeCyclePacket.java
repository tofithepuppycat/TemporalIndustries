package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.item.TemporalAnchorItem;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: scroll-wheel mode cycle for the Temporal Anchor, sent while the player is
 * sneaking and holding it (see the client-side input handler). */
public class AnchorModeCyclePacket implements CustomPacketPayload {
    public static final Type<AnchorModeCyclePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "anchor_mode_cycle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AnchorModeCyclePacket> STREAM_CODEC =
            StreamCodec.of(AnchorModeCyclePacket::encode, AnchorModeCyclePacket::decode);

    private final int delta;

    public AnchorModeCyclePacket(int delta) {
        this.delta = delta;
    }

    public static void encode(RegistryFriendlyByteBuf buf, AnchorModeCyclePacket packet) {
        buf.writeVarInt(packet.delta);
    }

    public static AnchorModeCyclePacket decode(RegistryFriendlyByteBuf buf) {
        return new AnchorModeCyclePacket(buf.readVarInt());
    }

    public static void handle(AnchorModeCyclePacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            ItemStack stack = sender.getMainHandItem();
            if (!(stack.getItem() instanceof TemporalAnchorItem)) return;

            int mode = Math.floorMod(TemporalAnchorItem.getMode(stack) + packet.delta, TemporalAnchorItem.MODE_COUNT);
            TemporalAnchorItem.setMode(stack, mode);

            String modeKey = mode == TemporalAnchorItem.MODE_KEEP_INVENTORY
                    ? "item.temporalindustries.temporal_anchor.mode_keep_inventory"
                    : "item.temporalindustries.temporal_anchor.mode_rewind_all";
            sender.displayClientMessage(Component.translatable(modeKey), true);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
