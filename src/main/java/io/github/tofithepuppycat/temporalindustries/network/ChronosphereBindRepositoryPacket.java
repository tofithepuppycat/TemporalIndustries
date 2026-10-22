package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.AbstractTimelineMachineBlockEntity;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.menu.TimelineViewMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -> server: names the repository an unbound Chronosphere or Chronovault should use, creating it if no
 * repository by that name exists.
 */
public class ChronosphereBindRepositoryPacket implements CustomPacketPayload {
    public static final Type<ChronosphereBindRepositoryPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chronosphere_bind_repository"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronosphereBindRepositoryPacket> STREAM_CODEC =
            StreamCodec.of(ChronosphereBindRepositoryPacket::encode, ChronosphereBindRepositoryPacket::decode);

    private final BlockPos machinePos;
    private final String name;

    public ChronosphereBindRepositoryPacket(BlockPos machinePos, String name) {
        this.machinePos = machinePos;
        this.name = name;
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChronosphereBindRepositoryPacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeUtf(packet.name, TemporalWorldData.MAX_REPOSITORY_NAME_LENGTH * 4);
    }

    public static ChronosphereBindRepositoryPacket decode(RegistryFriendlyByteBuf buf) {
        return new ChronosphereBindRepositoryPacket(buf.readBlockPos(), buf.readUtf(TemporalWorldData.MAX_REPOSITORY_NAME_LENGTH * 4));
    }

    public static void handle(ChronosphereBindRepositoryPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof TimelineViewMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;
            if (!(sender.level().getBlockEntity(packet.machinePos) instanceof AbstractTimelineMachineBlockEntity machine)) return;

            AbstractTimelineMachineBlockEntity.BindResult result = machine.bindToRepositoryNamed(packet.name);
            String messageKey = switch (result) {
                case IN_USE -> "gui.temporalindustries.chronosphere.bind_in_use";
                case WRONG_DIMENSION -> "gui.temporalindustries.chronosphere.bind_wrong_dimension";
                case INVALID_NAME -> "gui.temporalindustries.chronosphere.bind_invalid_name";
                case UNAVAILABLE -> "gui.temporalindustries.chronosphere.bind_unavailable";
                case CREATED, ADOPTED, ALREADY_BOUND -> null;
            };
            if (messageKey != null) {
                sender.displayClientMessage(Component.translatable(messageKey), true);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
