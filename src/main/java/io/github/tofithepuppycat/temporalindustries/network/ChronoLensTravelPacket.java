package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.data.PlayerTemporalState;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.dimension.ChronoRiftDimension;
import io.github.tofithepuppycat.temporalindustries.timeline.ChronoRiftFiller;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: the Chronoscope's "Travel" button, requesting entry into the Chrono Rift at
 * the selected moment. Refused if the rift already has occupants anchored to a different moment
 * (see {@link TemporalWorldData#canEnterChronoRift}). */
public class ChronoLensTravelPacket implements CustomPacketPayload {
    public static final Type<ChronoLensTravelPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chrono_lens_travel"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronoLensTravelPacket> STREAM_CODEC =
            StreamCodec.of(ChronoLensTravelPacket::encode, ChronoLensTravelPacket::decode);

    /** Radius (in chunks) synchronously filled around the landing point before teleporting, so the
     * player never lands in unfilled void. Further exploration fills lazily via ChronoRiftChunkListener. */
    private static final int PREFILL_RADIUS_CHUNKS = 1;

    private final long selectedGameTime;

    public ChronoLensTravelPacket(long selectedGameTime) {
        this.selectedGameTime = selectedGameTime;
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChronoLensTravelPacket packet) {
        buf.writeLong(packet.selectedGameTime);
    }

    public static ChronoLensTravelPacket decode(RegistryFriendlyByteBuf buf) {
        return new ChronoLensTravelPacket(buf.readLong());
    }

    public static void handle(ChronoLensTravelPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            MinecraftServer server = sender.getServer();
            if (server == null || sender.level().dimension() == ChronoRiftDimension.LEVEL_KEY) return;

            ServerLevel riftLevel = ChronoRiftDimension.getLevel(server);
            if (riftLevel == null) return;

            TemporalWorldData data = TemporalWorldData.get(server);
            if (!data.canEnterChronoRift(riftLevel, packet.selectedGameTime)) {
                sender.displayClientMessage(Component.translatable("item.temporalindustries.chronoscope.rift_busy"), true);
                return;
            }
            if (riftLevel.players().isEmpty()) {
                data.setChronoRiftActiveTime(packet.selectedGameTime);
            }

            PlayerTemporalState state = data.getOrCreatePlayerState(sender.getUUID());
            state.setChronoRiftOrigin(new PlayerTemporalState.ChronoRiftOrigin(
                    sender.level().dimension().location(), sender.getX(), sender.getY(), sender.getZ(),
                    sender.getYRot(), sender.getXRot()));
            data.setDirty();

            ChunkPos destChunk = new ChunkPos(sender.blockPosition());
            for (int dx = -PREFILL_RADIUS_CHUNKS; dx <= PREFILL_RADIUS_CHUNKS; dx++) {
                for (int dz = -PREFILL_RADIUS_CHUNKS; dz <= PREFILL_RADIUS_CHUNKS; dz++) {
                    ChunkPos pos = new ChunkPos(destChunk.x + dx, destChunk.z + dz);
                    riftLevel.getChunk(pos.x, pos.z); // force load/generate (pure void, cheap)
                    ChronoRiftFiller.fillChunk(server, riftLevel, pos, packet.selectedGameTime);
                }
            }

            int x = sender.getBlockX();
            int z = sender.getBlockZ();
            int y = riftLevel.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            sender.teleportTo(riftLevel, x + 0.5D, y + 1.0D, z + 0.5D, sender.getYRot(), sender.getXRot());
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
