package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.AbstractTimelineMachineBlockEntity;
import io.github.tofithepuppycat.temporalindustries.dimension.RiftDeviceLock;
import io.github.tofithepuppycat.temporalindustries.item.ChronoRemoteControlItem;
import io.github.tofithepuppycat.temporalindustries.block.entity.TimelineViewProvider;
import io.github.tofithepuppycat.temporalindustries.timeline.RepoCommit;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalRepository;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: an option picked on the Chrono Remote Control's wheel. */
public class ChronoRemoteActionPacket implements CustomPacketPayload {
    public static final Type<ChronoRemoteActionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chrono_remote_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronoRemoteActionPacket> STREAM_CODEC =
            StreamCodec.of(ChronoRemoteActionPacket::encode, ChronoRemoteActionPacket::decode);

    /** What the wheel offers; the order is the order around the wheel, starting at the top. */
    public enum Action {
        COMMIT("commit"),
        JUMP_FORWARD("jump_forward"),
        JUMP_LATEST("jump_latest"),
        JUMP_BACK("jump_back");

        private final String key;

        Action(String key) {
            this.key = key;
        }

        public Component label() {
            return Component.translatable("gui.temporalindustries.chrono_remote." + key);
        }
    }

    private final Action action;

    public ChronoRemoteActionPacket(Action action) {
        this.action = action;
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChronoRemoteActionPacket packet) {
        buf.writeEnum(packet.action);
    }

    public static ChronoRemoteActionPacket decode(RegistryFriendlyByteBuf buf) {
        return new ChronoRemoteActionPacket(buf.readEnum(Action.class));
    }

    public static void handle(ChronoRemoteActionPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            GlobalPos linked = null;
            for (InteractionHand hand : InteractionHand.values()) {
                ItemStack stack = sender.getItemInHand(hand);
                if (stack.getItem() instanceof ChronoRemoteControlItem) {
                    linked = ChronoRemoteControlItem.getLinkedChronosphere(stack);
                    break;
                }
            }
            if (linked == null || RiftDeviceLock.blockIfInRift(sender.level(), sender)) return;

            ServerLevel level = sender.server.getLevel(linked.dimension());
            if (level == null || !level.isLoaded(linked.pos())
                    || !(level.getBlockEntity(linked.pos()) instanceof AbstractTimelineMachineBlockEntity machine)
                    || machine.getRepository() == null) {
                message(sender, "unreachable");
                return;
            }

            run(sender, machine, packet.action);
        });
    }

    private static void run(ServerPlayer sender, AbstractTimelineMachineBlockEntity machine, Action action) {
        TemporalRepository repo = machine.getRepository();
        long head = repo.getHeadCommitId();

        switch (action) {
            case COMMIT -> {
                machine.commitNow();
                message(sender, repo.getHeadCommitId() != head ? "committed" : "nothing_to_commit");
            }
            case JUMP_BACK -> {
                RepoCommit current = repo.getCommit(head);
                jump(sender, machine, current == null ? RepoCommit.NO_PARENT : current.getParentId());
            }
            case JUMP_FORWARD -> jump(sender, machine, repo.nextCommitAfterHead());
            case JUMP_LATEST -> jump(sender, machine, repo.latestCommitId());
        }
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

    private static void message(ServerPlayer sender, String key) {
        sender.displayClientMessage(Component.translatable("gui.temporalindustries.chrono_remote.msg." + key), true);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
