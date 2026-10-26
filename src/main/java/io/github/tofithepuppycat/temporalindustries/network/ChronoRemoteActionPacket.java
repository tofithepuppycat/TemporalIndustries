package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.AbstractTimelineMachineBlockEntity;
import io.github.tofithepuppycat.temporalindustries.block.entity.ChronosphereBlockEntity;
import io.github.tofithepuppycat.temporalindustries.block.entity.TimelineViewProvider;
import io.github.tofithepuppycat.temporalindustries.dimension.RiftDeviceLock;
import io.github.tofithepuppycat.temporalindustries.item.ChronoRemoteControlItem;
import io.github.tofithepuppycat.temporalindustries.timeline.RepoCommit;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalRepository;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: an action picked on the Chrono Remote Control, run on its linked machine. */
public class ChronoRemoteActionPacket implements CustomPacketPayload {
    public static final Type<ChronoRemoteActionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chrono_remote_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronoRemoteActionPacket> STREAM_CODEC =
            StreamCodec.of(ChronoRemoteActionPacket::encode, ChronoRemoteActionPacket::decode);

    public enum Action {
        /** Replies with the machine's settings without changing anything. */
        SYNC,
        COMMIT,
        TOGGLE_SAVE_ENTITIES,
        TOGGLE_CHARGE_CONTAINERS,
        UNBIND_REPOSITORY,
        DELETE_HISTORY,
        /** Includes the chunk the sender stands in in the next commit. */
        SELECT_CHUNK,
        /** Excludes the chunk the sender stands in from the next commit. */
        DESELECT_CHUNK,
        /** Jumps to the commit id carried as the argument. */
        JUMP
    }

    private final Action action;
    private final long argument;

    public ChronoRemoteActionPacket(Action action) {
        this(action, 0L);
    }

    public ChronoRemoteActionPacket(Action action, long argument) {
        this.action = action;
        this.argument = argument;
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChronoRemoteActionPacket packet) {
        buf.writeEnum(packet.action);
        buf.writeLong(packet.argument);
    }

    public static ChronoRemoteActionPacket decode(RegistryFriendlyByteBuf buf) {
        return new ChronoRemoteActionPacket(buf.readEnum(Action.class), buf.readLong());
    }

    public static void handle(ChronoRemoteActionPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            GlobalPos linked = ChronoRemoteControlItem.findHeldLink(sender);
            if (linked == null || RiftDeviceLock.blockIfInRift(sender.level(), sender)) return;

            ServerLevel level = sender.server.getLevel(linked.dimension());
            if (level == null || !level.isLoaded(linked.pos())
                    || !(level.getBlockEntity(linked.pos()) instanceof AbstractTimelineMachineBlockEntity machine)) {
                message(sender, "unreachable");
                return;
            }

            if (packet.action == Action.SYNC) {
                sendState(sender, machine, level);
                return;
            }
            if (!machine.isRepositoryBound() || machine.getRepository() == null) {
                message(sender, "unbound");
                return;
            }

            run(sender, machine, level, packet.action, packet.argument);
        });
    }

    private static void run(ServerPlayer sender, AbstractTimelineMachineBlockEntity machine, ServerLevel level,
                            Action action, long argument) {
        TemporalRepository repo = machine.getRepository();
        long head = repo.getHeadCommitId();

        switch (action) {
            case COMMIT -> {
                machine.commitNow();
                message(sender, repo.getHeadCommitId() != head ? "committed" : "nothing_to_commit");
                sendState(sender, machine, level);
            }
            case TOGGLE_SAVE_ENTITIES -> {
                machine.setSaveEntities(!machine.isSaveEntities());
                sendState(sender, machine, level);
            }
            case TOGGLE_CHARGE_CONTAINERS -> {
                machine.setChargeContainers(!machine.isChargeContainers());
                sendState(sender, machine, level);
            }
            case UNBIND_REPOSITORY -> {
                machine.unbindRepository();
                message(sender, "repository_detached");
                sendState(sender, machine, level);
            }
            case DELETE_HISTORY -> {
                machine.deleteRepository();
                message(sender, "history_deleted");
                sendState(sender, machine, level);
            }
            case SELECT_CHUNK -> toggleChunk(sender, machine, level, true);
            case DESELECT_CHUNK -> toggleChunk(sender, machine, level, false);
            case JUMP -> jump(sender, machine, argument);
            case SYNC -> { }
        }
    }

    private static void toggleChunk(ServerPlayer sender, AbstractTimelineMachineBlockEntity machine, ServerLevel level,
                                    boolean add) {
        if (!(machine instanceof ChronosphereBlockEntity chronosphere)) {
            message(sender, "single_chunk");
            return;
        }
        if (sender.level() != level) {
            message(sender, "wrong_dimension");
            return;
        }

        ChunkPos chunk = sender.chunkPosition();
        ChronosphereBlockEntity.ToggleResult result = chronosphere.toggleChunk(chunk, add);
        switch (result) {
            case ADDED -> sender.displayClientMessage(
                    Component.translatable("gui.temporalindustries.chrono_remote.msg.chunk_selected", chunk.x, chunk.z), true);
            case REMOVED -> sender.displayClientMessage(
                    Component.translatable("gui.temporalindustries.chrono_remote.msg.chunk_deselected", chunk.x, chunk.z), true);
            case IS_HOME -> message(sender, "home_chunk");
            case NOT_SELECTED -> message(sender, "not_selected");
            case OUT_OF_BOUNDS -> sender.displayClientMessage(
                    Component.translatable("block.temporalindustries.chronosphere.out_of_bounds"), true);
            case ALREADY_CLAIMED_ELSEWHERE -> sender.displayClientMessage(
                    Component.translatable("block.temporalindustries.chronosphere.already_tracked"), true);
            case LIMIT_REACHED -> sender.displayClientMessage(
                    Component.translatable("block.temporalindustries.chronosphere.limit_reached"), true);
            case REGION_CHUNK -> sender.displayClientMessage(
                    Component.translatable("block.temporalindustries.chronosphere.region_chunk"), true);
        }
        sendState(sender, machine, level);
    }

    private static void jump(ServerPlayer sender, AbstractTimelineMachineBlockEntity machine, long target) {
        TemporalRepository repo = machine.getRepository();
        RepoCommit commit = target == RepoCommit.NO_PARENT ? null : repo.getCommit(target);
        if (commit == null || target == repo.getHeadCommitId()) {
            message(sender, "no_target");
            return;
        }

        if (machine.jump(target) == TimelineViewProvider.JumpResult.CHARGING) {
            sender.displayClientMessage(Component.translatable("gui.temporalindustries.timeline_view.charging"), true);
            return;
        }
        sender.displayClientMessage(Component.translatable("gui.temporalindustries.chrono_remote.jumped", commit.getShortHash()), true);
    }

    /** Pushes the machine's settings, plus its claim map when the sender is in the same dimension to read it against. */
    private static void sendState(ServerPlayer sender, AbstractTimelineMachineBlockEntity machine, ServerLevel level) {
        boolean chronosphere = machine instanceof ChronosphereBlockEntity;
        PacketDistributor.sendToPlayer(sender, new ChronoRemoteStateSyncPacket(
                machine.isRepositoryBound() && machine.getRepository() != null, chronosphere,
                machine.isSaveEntities(), machine.isChargeContainers()));

        if (machine instanceof ChronosphereBlockEntity sphere && sender.level() == level) {
            BlockPos pos = sphere.getBlockPos();
            ChronosphereStateRequestPacket.sendStateSync(sender, sphere, pos);
        }
    }

    private static void message(ServerPlayer sender, String key) {
        sender.displayClientMessage(Component.translatable("gui.temporalindustries.chrono_remote.msg." + key), true);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
