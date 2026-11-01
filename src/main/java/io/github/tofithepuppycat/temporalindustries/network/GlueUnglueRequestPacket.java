package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.item.AntiTemporalGlueItem;
import io.github.tofithepuppycat.temporalindustries.item.TemporalGlueItem;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -> server: the player punched empty air while holding a glue, which the server is not told
 * about otherwise. Removes the glued regions along their line of sight, like punching a block does.
 */
public class GlueUnglueRequestPacket implements CustomPacketPayload {
    public static final Type<GlueUnglueRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "glue_unglue_request"));

    public static final GlueUnglueRequestPacket INSTANCE = new GlueUnglueRequestPacket();

    public static final StreamCodec<RegistryFriendlyByteBuf, GlueUnglueRequestPacket> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    private GlueUnglueRequestPacket() {}

    public static void handle(GlueUnglueRequestPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) return;

        context.enqueueWork(() -> {
            if (!(sender.level() instanceof ServerLevel level)) return;
            for (ItemStack stack : new ItemStack[] {sender.getMainHandItem(), sender.getOffhandItem()}) {
                if (stack.getItem() instanceof AntiTemporalGlueItem) {
                    AntiTemporalGlueItem.deleteRegionsAlongSight(level, sender, stack);
                    return;
                }
                if (stack.getItem() instanceof TemporalGlueItem) {
                    TemporalGlueItem.deleteRegionsAlongSight(level, sender, stack);
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
