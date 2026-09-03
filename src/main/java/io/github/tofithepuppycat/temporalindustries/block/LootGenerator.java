package io.github.tofithepuppycat.temporalindustries.block;

import com.mojang.serialization.MapCodec;
import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.LootGeneratorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.List;

/** Spends liquid Chaos to roll a player-chosen loot table into its own chest-sized inventory; see
 * {@link LootGeneratorBlockEntity}. Acts as the controller of a small {@link MachineFrame}
 * multiblock ({@link LootGeneratorStructure}) - the machine only works once that frame is formed. */
@SuppressWarnings("null")
public class LootGenerator extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    // Drives which front-face model is used: the animated texture while the surrounding
    // MachineFrame multiblock is formed, or a static last-frame texture while it isn't - see
    // LootGeneratorBlockEntity#checkStructure.
    public static final BooleanProperty FORMED = BooleanProperty.create("formed");

    private static final MapCodec<LootGenerator> CODEC = simpleCodec(LootGenerator::new);

    public LootGenerator(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(FORMED, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, FORMED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
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
            return interact(level, pos, be, serverPlayer);
        }
        return InteractionResult.CONSUME;
    }

    /** Opens the loot generator's GUI if its frame is already formed, otherwise auto-fills what it can
     * from the player's inventory (without opening the GUI that same click, so completing the frame
     * doesn't immediately throw the player into the menu) or highlights what's still missing. Shared
     * by both a direct click on the controller and a click on one of its {@link MachineFrame}
     * satellites forwarding here via its stored controller position. */
    public static InteractionResult interact(Level level, BlockPos pos, LootGeneratorBlockEntity be, ServerPlayer serverPlayer) {
        if (be.checkStructure()) {
            serverPlayer.openMenu(be, buf -> buf.writeBlockPos(pos));
            return InteractionResult.CONSUME;
        }

        fillFromInventory(level, be, serverPlayer);
        if (be.checkStructure()) return InteractionResult.CONSUME;

        highlightMissing((ServerLevel) level, be.findMissing(), serverPlayer);
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
    private static final double EDGE_PARTICLE_SPACING = 0.2;

    private static void highlightMissing(ServerLevel level, List<BlockPos> missing, ServerPlayer player) {
        for (BlockPos pos : missing) {
            for (Vector3f point : BoxEdgeParticles.outline(pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1, EDGE_PARTICLE_SPACING)) {
                level.sendParticles(MISSING_FRAME_PARTICLE, point.x(), point.y(), point.z(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }
        player.displayClientMessage(Component.translatable("block.temporalindustries.loot_generator.missing_frame", missing.size()), true);
    }
}
