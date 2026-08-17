package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyOrbEntity;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

import static io.github.tofithepuppycat.temporalindustries.TemporalIndustries.MODID;
import static io.github.tofithepuppycat.temporalindustries.block.SeebeckGenerator.FACING;

/**
 * Passive FE generator: reads the neighbors to the left and right of the block (relative to
 * {@link net.minecraft.world.level.block.state.properties.DirectionProperty} {@link
 * io.github.tofithepuppycat.temporalindustries.block.SeebeckGenerator#FACING}) as a hot source (left)
 * and a cold source (right). While both are present it credits FE every tick, scaled by the
 * {@link #TEMPERATURES} gap between the two sources (per IDEAS.md's "Generates electricity based on
 * the temperature difference"), and periodically emits ORD/CHS orbs whose count scales the same way.
 * A depletable hot source (fire/soul fire, but not lava, which is treated as inexhaustible like
 * vanilla) burns out to air after a long time; a cold source melts (ice family to water, snow family
 * to air) the same way.
 */
@SuppressWarnings("null")
public class SeebeckGeneratorBlockEntity extends BlockEntity {
    public static final TagKey<Block> HOT_SOURCES = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(MODID, "seebeck_hot_sources"));
    public static final TagKey<Block> COLD_SOURCES = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(MODID, "seebeck_cold_sources"));

    /** Relative "temperature" of each hot/cold source block, used to scale generator output by the gap between them. */
    private static final Map<Block, Integer> TEMPERATURES = Map.ofEntries(
            Map.entry(Blocks.LAVA, 1000),
            Map.entry(Blocks.FIRE, 600),
            Map.entry(Blocks.SOUL_FIRE, 500),
            Map.entry(Blocks.SNOW_BLOCK, -300),
            Map.entry(Blocks.ICE, -400),
            Map.entry(Blocks.PACKED_ICE, -600),
            Map.entry(Blocks.BLUE_ICE, -800),
            Map.entry(Blocks.POWDER_SNOW, -900)
    );
    private static final int MAX_TEMP_DIFF = 1000 - (-900);

    private static final Map<Block, BlockState> COLD_MELT_RESULTS = Map.of(
            Blocks.ICE, Blocks.WATER.defaultBlockState(),
            Blocks.PACKED_ICE, Blocks.WATER.defaultBlockState(),
            Blocks.BLUE_ICE, Blocks.WATER.defaultBlockState(),
            Blocks.SNOW_BLOCK, Blocks.AIR.defaultBlockState(),
            Blocks.POWDER_SNOW, Blocks.AIR.defaultBlockState()
    );

    private static final int ENERGY_CAPACITY = 20_000;
    private static final int ENERGY_MAX_EXTRACT = 200;
    private static final int MIN_GEN_RATE_FE_PER_TICK = 4;
    private static final int MAX_GEN_RATE_FE_PER_TICK = 40;

    private static final int ENTROPY_INTERVAL_TICKS = 200;
    private static final int MAX_ENTROPY_PER_INTERVAL = 4;

    /** ~10 minutes of active generation before a depletable hot source burns out / a cold source melts. */
    private static final int HOT_LIFESPAN_TICKS = 12_000;
    private static final int COLD_LIFESPAN_TICKS = 12_000;

    private final class GeneratorEnergyStorage extends EnergyStorage {
        GeneratorEnergyStorage() {
            super(ENERGY_CAPACITY, 0, ENERGY_MAX_EXTRACT);
        }

        @Override public int extractEnergy(int max, boolean simulate) {
            int v = super.extractEnergy(max, simulate);
            if (!simulate && v > 0) setChanged();
            return v;
        }

        void produce(int amount) {
            if (amount <= 0) return;
            energy = Math.min(capacity, energy + amount);
            setChanged();
        }
    }

    private final GeneratorEnergyStorage energyStorage = new GeneratorEnergyStorage();

    private int hotBurnTicks = 0;
    private int coldMeltTicks = 0;

    public SeebeckGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(Registration.SEEBECK_GENERATOR_BLOCK_ENTITY.get(), pos, state);
    }

    public IEnergyStorage getEnergyStorage() {
        return energyStorage;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, SeebeckGeneratorBlockEntity be) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        Direction facing = state.getValue(FACING);
        BlockPos hotPos = pos.relative(facing.getCounterClockWise());
        BlockPos coldPos = pos.relative(facing.getClockWise());
        BlockState hotState = level.getBlockState(hotPos);
        BlockState coldState = level.getBlockState(coldPos);

        boolean hot = hotState.is(HOT_SOURCES);
        boolean cold = coldState.is(COLD_SOURCES);

        if (!(hot && cold)) {
            be.hotBurnTicks = 0;
            be.coldMeltTicks = 0;
            return;
        }

        double tempFactor = temperatureFactor(hotState.getBlock(), coldState.getBlock());
        int genRate = (int) Math.round(MIN_GEN_RATE_FE_PER_TICK + (MAX_GEN_RATE_FE_PER_TICK - MIN_GEN_RATE_FE_PER_TICK) * tempFactor);
        be.energyStorage.produce(genRate);

        if (level.getGameTime() % ENTROPY_INTERVAL_TICKS == 0) {
            int entropyAmount = 1 + (int) Math.round((MAX_ENTROPY_PER_INTERVAL - 1) * tempFactor);
            EntropyOrbEntity.spawn(serverLevel, hotPos.getX() + 0.5, hotPos.getY() + 0.5, hotPos.getZ() + 0.5, EntropyType.CHAOS, entropyAmount);
            EntropyOrbEntity.spawn(serverLevel, coldPos.getX() + 0.5, coldPos.getY() + 0.5, coldPos.getZ() + 0.5, EntropyType.ORDER, entropyAmount);
        }

        if (isDepletableHot(hotState.getBlock())) {
            if (++be.hotBurnTicks >= HOT_LIFESPAN_TICKS) {
                level.setBlockAndUpdate(hotPos, Blocks.AIR.defaultBlockState());
                be.hotBurnTicks = 0;
            }
        } else {
            be.hotBurnTicks = 0;
        }

        BlockState meltResult = COLD_MELT_RESULTS.get(coldState.getBlock());
        if (meltResult != null) {
            if (++be.coldMeltTicks >= COLD_LIFESPAN_TICKS) {
                level.setBlockAndUpdate(coldPos, meltResult);
                be.coldMeltTicks = 0;
            }
        } else {
            be.coldMeltTicks = 0;
        }
    }

    private static boolean isDepletableHot(Block block) {
        return block == Blocks.FIRE || block == Blocks.SOUL_FIRE;
    }

    /** Normalized (0-1) temperature gap between the hot and cold source, scaled against the widest possible pairing. */
    private static double temperatureFactor(Block hot, Block cold) {
        int hotTemp = TEMPERATURES.getOrDefault(hot, 0);
        int coldTemp = TEMPERATURES.getOrDefault(cold, 0);
        return Math.clamp((double) (hotTemp - coldTemp) / MAX_TEMP_DIFF, 0.0, 1.0);
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Energy", energyStorage.serializeNBT(registries));
        tag.putInt("HotBurnTicks", hotBurnTicks);
        tag.putInt("ColdMeltTicks", coldMeltTicks);
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Energy")) energyStorage.deserializeNBT(registries, tag.get("Energy"));
        hotBurnTicks = tag.getInt("HotBurnTicks");
        coldMeltTicks = tag.getInt("ColdMeltTicks");
    }
}
