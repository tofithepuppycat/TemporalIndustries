package io.github.tofithepuppycat.temporalindustries.block;

import com.mojang.serialization.MapCodec;
import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.LootGeneratorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** Spends liquid Chaos to roll a player-chosen loot table into its own chest-sized inventory; see
 * {@link LootGeneratorBlockEntity}. Acts as the controller of a small {@link MachineFrame}
 * multiblock ({@link LootGeneratorStructure}) - the machine only works once that frame is formed. */
@SuppressWarnings("null")
public class LootGenerator extends BaseEntityBlock {
    private static final MapCodec<LootGenerator> CODEC = simpleCodec(LootGenerator::new);

    public LootGenerator(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, Registration.LOOT_GENERATOR_BLOCK_ENTITY.get(), LootGeneratorBlockEntity::tick);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new LootGeneratorBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos, @NotNull Player player, @NotNull BlockHitResult hitResult) {
        if (level.isClientSide) return InteractionResult.SUCCESS;

        if (level.getBlockEntity(pos) instanceof LootGeneratorBlockEntity be && player instanceof ServerPlayer serverPlayer) {
            if (be.checkStructure()) {
                serverPlayer.openMenu(be, buf -> buf.writeBlockPos(pos));
                return InteractionResult.CONSUME;
            }

            fillFromInventory(level, be, serverPlayer);
            if (be.checkStructure()) {
                serverPlayer.openMenu(be, buf -> buf.writeBlockPos(pos));
                return InteractionResult.CONSUME;
            }

            highlightMissing((ServerLevel) level, be.findMissing(), serverPlayer);
        }
        return InteractionResult.CONSUME;
    }

    /** Auto-consumes {@link Registration#MACHINE_FRAME_ITEM} from the player's inventory to fill as
     * many missing frame positions as they can currently afford. */
    private static void fillFromInventory(Level level, LootGeneratorBlockEntity be, ServerPlayer player) {
        for (BlockPos missing : be.findMissing()) {
            if (!takeOneMachineFrame(player)) continue;
            level.setBlockAndUpdate(missing, Registration.MACHINE_FRAME_BLOCK.get().defaultBlockState());
        }
    }

    private static boolean takeOneMachineFrame(ServerPlayer player) {
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(Registration.MACHINE_FRAME_ITEM.get())) {
                stack.shrink(1);
                return true;
            }
        }
        return false;
    }

    private static final DustParticleOptions MISSING_FRAME_PARTICLE = new DustParticleOptions(new Vector3f(1.0F, 0.35F, 0.35F), 0.6F);

    /** Points, in [0,1] cube-local space, spaced along the 12 edges of a unit cube - used to draw a
     * small-particle outline around each missing frame position rather than a burst at its center. */
    private static final List<Vector3f> CUBE_EDGE_POINTS = buildCubeEdgePoints();

    private static List<Vector3f> buildCubeEdgePoints() {
        float[][] corners = {
                {0, 0, 0}, {1, 0, 0}, {0, 1, 0}, {0, 0, 1},
                {1, 1, 0}, {1, 0, 1}, {0, 1, 1}, {1, 1, 1}
        };
        int[][] edges = {
                {0, 1}, {1, 5}, {5, 3}, {3, 0}, // bottom face
                {2, 4}, {4, 7}, {7, 6}, {6, 2}, // top face
                {0, 2}, {1, 4}, {3, 6}, {5, 7}  // verticals
        };
        List<Vector3f> points = new ArrayList<>();
        int stepsPerEdge = 5;
        for (int[] edge : edges) {
            float[] a = corners[edge[0]];
            float[] b = corners[edge[1]];
            for (int i = 0; i <= stepsPerEdge; i++) {
                float t = i / (float) stepsPerEdge;
                points.add(new Vector3f(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t));
            }
        }
        return points;
    }

    private static void highlightMissing(ServerLevel level, List<BlockPos> missing, ServerPlayer player) {
        for (BlockPos pos : missing) {
            for (Vector3f point : CUBE_EDGE_POINTS) {
                level.sendParticles(MISSING_FRAME_PARTICLE, pos.getX() + point.x(), pos.getY() + point.y(), pos.getZ() + point.z(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }
        player.displayClientMessage(Component.translatable("block.temporalindustries.loot_generator.missing_frame", missing.size()), true);
    }
}
