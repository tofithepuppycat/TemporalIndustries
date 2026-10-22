package io.github.tofithepuppycat.temporalindustries.item;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.data.PlayerTemporalState;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.dimension.ChronoRiftDimension;
import io.github.tofithepuppycat.temporalindustries.dimension.ChronoRiftVoidWalkListener;
import io.github.tofithepuppycat.temporalindustries.dimension.EndOfTimeDimension;
import io.github.tofithepuppycat.temporalindustries.network.ChronoRiftTravel;
import io.github.tofithepuppycat.temporalindustries.network.EndOfTimeTravel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;

/** Handheld tool for visiting the Overworld's past in the Chrono Rift. */
public class ChronoscopeItem extends Item {
    /** Saved-time value that selects the End of Time instead of a moment in the Overworld's history. */
    public static final long END_OF_TIME = -1L;

    public ChronoscopeItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (player.level().dimension() == ChronoRiftDimension.LEVEL_KEY
                || player.level().dimension() == EndOfTimeDimension.LEVEL_KEY) {
            if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
                returnFromRift(serverPlayer);
            }
            return InteractionResultHolder.success(stack);
        }

        Long saved = stack.get(Registration.CHRONOSCOPE_TIME.get());
        if (saved == null || player.isShiftKeyDown()) {
            if (level.isClientSide) {
                io.github.tofithepuppycat.temporalindustries.client.screen.ChronoLensScreen.open(saved);
            }
            return InteractionResultHolder.success(stack);
        }

        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            if (saved == END_OF_TIME) EndOfTimeTravel.enter(serverPlayer);
            else ChronoRiftTravel.enter(serverPlayer, saved);
        }
        return InteractionResultHolder.success(stack);
    }

    private static void returnFromRift(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;

        TemporalWorldData data = TemporalWorldData.get(server);
        PlayerTemporalState state = data.getPlayerState(player.getUUID());
        PlayerTemporalState.ChronoRiftOrigin origin = state == null ? null : state.getChronoRiftOrigin();
        if (origin == null) {
            player.displayClientMessage(Component.translatable("item.temporalindustries.chronoscope.no_origin"), true);
            return;
        }

        ResourceKey<Level> originKey = ResourceKey.create(Registries.DIMENSION, origin.dimension());
        ServerLevel originLevel = server.getLevel(originKey);
        if (originLevel == null) originLevel = server.overworld();

        if (player.level().dimension() == ChronoRiftDimension.LEVEL_KEY) {
            BlockPos target = findStandingSpace(originLevel, player.blockPosition());
            player.teleportTo(originLevel, player.getX(), target.getY(), player.getZ(), player.getYRot(), player.getXRot());
        } else {
            player.teleportTo(originLevel, origin.x(), origin.y(), origin.z(), origin.yRot(), origin.xRot());
        }
        ChronoRiftVoidWalkListener.protectFromFall(player);
        state.clearChronoRiftOrigin();
        data.setDirty();
    }

    /** pos itself if it and the block above are passable, otherwise the nearest such pair above it. */
    public static BlockPos findStandingSpace(ServerLevel level, BlockPos pos) {
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 2;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(pos.getX(), Math.max(pos.getY(), minY), pos.getZ());
        for (; cursor.getY() <= maxY; cursor.move(0, 1, 0)) {
            if (isPassable(level, cursor) && isPassable(level, cursor.above())) return cursor.immutable();
        }
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
        return new BlockPos(pos.getX(), surface, pos.getZ());
    }

    private static boolean isPassable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        TooltipUtil.appendDescription(tooltip, "item.temporalindustries.chronoscope.tooltip");
    }
}
