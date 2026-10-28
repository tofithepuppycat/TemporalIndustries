package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.item.ChronoscopeItem;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -> server: the Chronoscope's "Save" button, storing the selected moment on the Chronoscope
 * the player is holding.
 */
public class ChronoLensSavePacket implements CustomPacketPayload {
    public static final Type<ChronoLensSavePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chrono_lens_save"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronoLensSavePacket> STREAM_CODEC =
            StreamCodec.of(ChronoLensSavePacket::encode, ChronoLensSavePacket::decode);

    private final long time;

    /** @param time a game time, or {@link ChronoscopeItem#END_OF_TIME} */
    public ChronoLensSavePacket(long time) {
        this.time = time == ChronoscopeItem.END_OF_TIME ? time : Math.max(0L, time);
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChronoLensSavePacket packet) {
        buf.writeLong(packet.time);
    }

    public static ChronoLensSavePacket decode(RegistryFriendlyByteBuf buf) {
        return new ChronoLensSavePacket(buf.readLong());
    }

    public static void handle(ChronoLensSavePacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            for (InteractionHand hand : InteractionHand.values()) {
                ItemStack stack = sender.getItemInHand(hand);
                if (stack.getItem() instanceof ChronoscopeItem) {
                    long time = packet.time == ChronoscopeItem.END_OF_TIME
                            ? packet.time : Math.min(packet.time, sender.level().getGameTime());
                    stack.set(Registration.CHRONOSCOPE_TIME.get(), time);
                    return;
                }
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
