package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.GlueSelectionClientState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** Server -> client: the full glued-region list for one dimension, for {@link GlueSelectionClientState}
 * — sent in reply to a {@link GlueRegionRequestPacket}, and immediately after any glue/unglue so
 * everyone currently in that dimension sees the change right away. */
public class GlueRegionSyncPacket implements CustomPacketPayload {
    public static final Type<GlueRegionSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "glue_region_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GlueRegionSyncPacket> STREAM_CODEC =
            StreamCodec.of(GlueRegionSyncPacket::encode, GlueRegionSyncPacket::decode);

    private final ResourceLocation dimension;
    private final List<BoundingBox> regions;

    public GlueRegionSyncPacket(ResourceLocation dimension, List<BoundingBox> regions) {
        this.dimension = dimension;
        this.regions = regions;
    }

    public static void encode(RegistryFriendlyByteBuf buf, GlueRegionSyncPacket packet) {
        buf.writeResourceLocation(packet.dimension);
        buf.writeVarInt(packet.regions.size());
        for (BoundingBox region : packet.regions) {
            buf.writeInt(region.minX());
            buf.writeInt(region.minY());
            buf.writeInt(region.minZ());
            buf.writeInt(region.maxX());
            buf.writeInt(region.maxY());
            buf.writeInt(region.maxZ());
        }
    }

    public static GlueRegionSyncPacket decode(RegistryFriendlyByteBuf buf) {
        ResourceLocation dimension = buf.readResourceLocation();
        int count = buf.readVarInt();
        List<BoundingBox> regions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            regions.add(new BoundingBox(buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readInt(), buf.readInt(), buf.readInt()));
        }
        return new GlueRegionSyncPacket(dimension, regions);
    }

    public static void handle(GlueRegionSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> GlueSelectionClientState.updateFromServer(packet.dimension, packet.regions));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
