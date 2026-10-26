package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.ChronosphereBlockEntity;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.item.TemporalGlueItem;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -> server: requests the region list a held glue draws. The anti-temporal list is the
 * player's current dimension's anti-glued regions; the tracked list is the regions of the
 * Chronosphere the held Temporal Glue is linked to.
 */
public class GlueRegionRequestPacket implements CustomPacketPayload {
    public static final Type<GlueRegionRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "glue_region_request"));

    public static final GlueRegionRequestPacket INSTANCE = new GlueRegionRequestPacket(false);
    public static final GlueRegionRequestPacket TRACKED = new GlueRegionRequestPacket(true);

    public static final StreamCodec<RegistryFriendlyByteBuf, GlueRegionRequestPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> buf.writeBoolean(packet.tracked),
                    buf -> buf.readBoolean() ? TRACKED : INSTANCE);

    private final boolean tracked;

    private GlueRegionRequestPacket(boolean tracked) {
        this.tracked = tracked;
    }

    public static void handle(GlueRegionRequestPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (packet.tracked) {
                ChronosphereBlockEntity machine = null;
                if (sender.level() instanceof ServerLevel level) {
                    for (ItemStack stack : new ItemStack[] {sender.getMainHandItem(), sender.getOffhandItem()}) {
                        if (stack.getItem() instanceof TemporalGlueItem) {
                            machine = TemporalGlueItem.resolveMachine(level, stack);
                            break;
                        }
                    }
                }
                TemporalGlueItem.sendRegions(sender, machine);
                return;
            }

            ResourceLocation dimension = sender.level().dimension().location();
            TemporalWorldData worldData = TemporalWorldData.get(sender.getServer());
            PacketDistributor.sendToPlayer(sender,
                    new GlueRegionSyncPacket(dimension, worldData.getGluedRegions(dimension)));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
