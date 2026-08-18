package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.config.TemporalIndustriesConfig;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyOrbEntity;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.HashMap;
import java.util.List;
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
            Map.entry(Blocks.CAMPFIRE, 450),
            Map.entry(Blocks.SOUL_CAMPFIRE, 400),
            Map.entry(Blocks.WATER, -10),
            Map.entry(Blocks.SNOW_BLOCK, -300),
            Map.entry(Blocks.ICE, -400),
            Map.entry(Blocks.PACKED_ICE, -600),
            Map.entry(Blocks.BLUE_ICE, -800),
            Map.entry(Blocks.POWDER_SNOW, -900)
    );
    private static final int MAX_TEMP_DIFF = 1000 - (-900);

    private static final Logger LOGGER = LogUtils.getLogger();

    private static List<? extends String> cachedHotConfigRaw = null;
    private static Map<Block, Integer> cachedHotConfigMap = Map.of();
    private static List<? extends String> cachedColdConfigRaw = null;
    private static Map<Block, Integer> cachedColdConfigMap = Map.of();

    private static final int ENERGY_CAPACITY = 20_000;
    private static final int ENERGY_MAX_EXTRACT = 200;
    private static final int MIN_GEN_RATE_FE_PER_TICK = 4;
    private static final int MAX_GEN_RATE_FE_PER_TICK = 40;

    // Orbs are emitted four times as often as before, with the per-emission amount scaled down to
    // match, so total ORD/CHS output per minute is unchanged. entropyAccumulator carries the
    // fractional remainder between emissions since an orb needs a whole value of at least 1.
    private static final int ENTROPY_INTERVAL_TICKS = 50;
    private static final double MIN_ENTROPY_PER_INTERVAL = 0.25;
    private static final double MAX_ENTROPY_PER_INTERVAL = 1.0;

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
    private double entropyAccumulator = 0.0;

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

        Map<Block, Integer> extraHot = extraHotSources();
        Map<Block, Integer> extraCold = extraColdSources();

        boolean hot = (hotState.is(HOT_SOURCES) || extraHot.containsKey(hotState.getBlock())) && isActiveHot(hotState);
        boolean cold = coldState.is(COLD_SOURCES) || extraCold.containsKey(coldState.getBlock());

        if (!(hot && cold)) return;

        double tempFactor = temperatureFactor(hotState.getBlock(), coldState.getBlock(), extraHot, extraCold);
        int genRate = (int) Math.round(MIN_GEN_RATE_FE_PER_TICK + (MAX_GEN_RATE_FE_PER_TICK - MIN_GEN_RATE_FE_PER_TICK) * tempFactor);
        be.energyStorage.produce(genRate);

        if (level.getGameTime() % ENTROPY_INTERVAL_TICKS == 0) {
            be.entropyAccumulator += MIN_ENTROPY_PER_INTERVAL + (MAX_ENTROPY_PER_INTERVAL - MIN_ENTROPY_PER_INTERVAL) * tempFactor;
            int entropyAmount = (int) Math.floor(be.entropyAccumulator);
            if (entropyAmount <= 0) return;
            be.entropyAccumulator -= entropyAmount;
            EntropyOrbEntity.spawn(serverLevel, hotPos.getX() + 0.5, orbSpawnY(hotPos, hotState), hotPos.getZ() + 0.5, EntropyType.CHAOS, entropyAmount);
            EntropyOrbEntity.spawn(serverLevel, coldPos.getX() + 0.5, orbSpawnY(coldPos, coldState), coldPos.getZ() + 0.5, EntropyType.ORDER, entropyAmount);
        }
    }

    /**
     * Fluid source blocks (lava, water) have no collision, so an orb spawned at their center can only
     * escape if there's open space directly above; when the source is embedded in terrain that space
     * may not exist, leaving the orb stuck bobbing inside the fluid forever. Spawning above the block
     * instead lets the existing solid-block escape logic ({@code moveTowardsClosestSpace}) handle it.
     */
    private static double orbSpawnY(BlockPos pos, BlockState state) {
        return pos.getY() + (state.getFluidState().isEmpty() ? 0.5 : 1.05);
    }

    /** Campfires only count as a hot source while lit; every other hot source is always active. */
    private static boolean isActiveHot(BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.CAMPFIRE || block == Blocks.SOUL_CAMPFIRE) {
            return state.getValue(BlockStateProperties.LIT);
        }
        return true;
    }

    /** Normalized (0-1) temperature gap between the hot and cold source, scaled against the widest possible pairing. */
    private static double temperatureFactor(Block hot, Block cold, Map<Block, Integer> extraHot, Map<Block, Integer> extraCold) {
        int hotTemp = TEMPERATURES.getOrDefault(hot, extraHot.getOrDefault(hot, 0));
        int coldTemp = TEMPERATURES.getOrDefault(cold, extraCold.getOrDefault(cold, 0));
        return Math.clamp((double) (hotTemp - coldTemp) / MAX_TEMP_DIFF, 0.0, 1.0);
    }

    /** Config-defined hot sources beyond the {@link #HOT_SOURCES} tag, re-parsed only when the config list changes. */
    private static Map<Block, Integer> extraHotSources() {
        List<? extends String> raw = TemporalIndustriesConfig.INSTANCE.seebeckSources.extraHotSources.get();
        if (raw != cachedHotConfigRaw) {
            cachedHotConfigMap = parseSourceEntries(raw);
            cachedHotConfigRaw = raw;
        }
        return cachedHotConfigMap;
    }

    /** Config-defined cold sources beyond the {@link #COLD_SOURCES} tag, re-parsed only when the config list changes. */
    private static Map<Block, Integer> extraColdSources() {
        List<? extends String> raw = TemporalIndustriesConfig.INSTANCE.seebeckSources.extraColdSources.get();
        if (raw != cachedColdConfigRaw) {
            cachedColdConfigMap = parseSourceEntries(raw);
            cachedColdConfigRaw = raw;
        }
        return cachedColdConfigMap;
    }

    /** Parses "block_id=temperature" config lines (see SeebeckSourceConfig) into a block-to-temperature map. */
    private static Map<Block, Integer> parseSourceEntries(List<? extends String> rawLines) {
        Map<Block, Integer> result = new HashMap<>();
        for (String line : rawLines) {
            int eq = line.indexOf('=');
            if (eq <= 0 || eq == line.length() - 1) {
                LOGGER.warn("[{}] Malformed Seebeck source entry '{}' (expected \"block_id=temperature\"), skipping", MODID, line);
                continue;
            }
            String id = line.substring(0, eq).trim();
            try {
                int temperature = Integer.parseInt(line.substring(eq + 1).trim());
                Block block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id))
                        .orElseThrow(() -> new IllegalArgumentException("Unknown block '" + id + "'"));
                result.put(block, temperature);
            } catch (RuntimeException e) {
                LOGGER.warn("[{}] Malformed Seebeck source entry '{}', skipping", MODID, line, e);
            }
        }
        return result;
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Energy", energyStorage.serializeNBT(registries));
        tag.putDouble("EntropyAccumulator", entropyAccumulator);
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Energy")) energyStorage.deserializeNBT(registries, tag.get("Energy"));
        entropyAccumulator = tag.getDouble("EntropyAccumulator");
    }
}
