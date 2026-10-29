package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.AbstractTimelineMachineBlockEntity;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.dimension.RiftDeviceLock;
import io.github.tofithepuppycat.temporalindustries.item.ChronoRemoteControlItem;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * Client -> server: asks the Chrono Remote Control's linked machine to switch to the repository
 * called {@code name}, or which repositories it could switch to that match {@code name}.
 */
public class ChronoRemoteRepositoryPacket implements CustomPacketPayload {
    public static final Type<ChronoRemoteRepositoryPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chrono_remote_repository"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronoRemoteRepositoryPacket> STREAM_CODEC =
            StreamCodec.of(ChronoRemoteRepositoryPacket::encode, ChronoRemoteRepositoryPacket::decode);

    private static final int NAME_MAX_BYTES = TemporalWorldData.MAX_REPOSITORY_NAME_LENGTH * 4;

    public enum Action { SUGGEST, CHANGE }

    private final Action action;
    private final String name;

    public ChronoRemoteRepositoryPacket(Action action, String name) {
        this.action = action;
        this.name = name;
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChronoRemoteRepositoryPacket packet) {
        buf.writeEnum(packet.action);
        buf.writeUtf(packet.name, NAME_MAX_BYTES);
    }

    public static ChronoRemoteRepositoryPacket decode(RegistryFriendlyByteBuf buf) {
        return new ChronoRemoteRepositoryPacket(buf.readEnum(Action.class), buf.readUtf(NAME_MAX_BYTES));
    }

    public static void handle(ChronoRemoteRepositoryPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            GlobalPos linked = ChronoRemoteControlItem.findHeldLink(sender);
            if (linked == null || RiftDeviceLock.blockIfInRift(sender.level(), sender)) return;

            ServerLevel level = sender.server.getLevel(linked.dimension());
            if (level == null || !level.isLoaded(linked.pos())
                    || !(level.getBlockEntity(linked.pos()) instanceof AbstractTimelineMachineBlockEntity machine)) {
                ChronoRemoteActionPacket.message(sender, "unreachable");
                return;
            }

            switch (packet.action) {
                case SUGGEST -> suggest(sender, level, packet.name);
                case CHANGE -> change(sender, machine, level, packet.name);
            }
        });
    }

    private static void suggest(ServerPlayer sender, ServerLevel level, String query) {
        if (!RequestThrottle.tryAcquire(sender, RequestThrottle.REPOSITORY_SUGGEST, 4)) return;
        List<String> names = TemporalWorldData.get(sender.server).suggestAvailableRepositoryNames(
                query, level.dimension().location(), RepositorySuggestRequestPacket.MAX_SUGGESTIONS);
        PacketDistributor.sendToPlayer(sender, new RepositorySuggestSyncPacket(query, names));
    }

    private static void change(ServerPlayer sender, AbstractTimelineMachineBlockEntity machine, ServerLevel level, String name) {
        AbstractTimelineMachineBlockEntity.BindResult result = machine.changeRepositoryTo(name);
        String messageKey = switch (result) {
            case IN_USE -> "gui.temporalindustries.chronosphere.bind_in_use";
            case WRONG_DIMENSION -> "gui.temporalindustries.chronosphere.bind_wrong_dimension";
            case INVALID_NAME -> "gui.temporalindustries.chronosphere.bind_invalid_name";
            case UNAVAILABLE -> "gui.temporalindustries.chronosphere.bind_unavailable";
            case ALREADY_BOUND -> "gui.temporalindustries.chrono_remote.msg.repository_unchanged";
            case CREATED, ADOPTED -> null;
        };
        if (messageKey != null) {
            sender.displayClientMessage(Component.translatable(messageKey), true);
        } else {
            sender.displayClientMessage(Component.translatable("gui.temporalindustries.chrono_remote.msg.repository_changed",
                    machine.getRepository() == null ? name : machine.getRepository().getName()), true);
        }
        ChronoRemoteActionPacket.sendState(sender, machine, level);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
