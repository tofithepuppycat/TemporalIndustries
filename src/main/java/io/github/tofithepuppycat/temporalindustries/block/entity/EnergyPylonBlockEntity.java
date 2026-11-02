package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.MachineFrame;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

/** Transfers FE between linked blocks. */
@SuppressWarnings("null")
public class EnergyPylonBlockEntity extends EntropicPylonBlockEntity {
    /** FE moved per input to output pair each tick. */
    public static final int TRANSFER_RATE_FE = 20_000;

    private static final int FE_COLOR = EntropyDisplay.FE_COLOR.getValue();
    private static final ColorParticleOption BOLT_PARTICLE = ColorParticleOption.create(Registration.TRANSMIT_SPARK.get(),
            ((FE_COLOR >> 16) & 0xFF) / 255F, ((FE_COLOR >> 8) & 0xFF) / 255F, (FE_COLOR & 0xFF) / 255F);

    public EnergyPylonBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state, null, FE_COLOR);
    }

    @Override
    protected boolean isLinkable(BlockPos target) {
        return level.getCapability(Capabilities.EnergyStorage.BLOCK, target, null) != null;
    }

    @Override
    protected String invalidLinkKey() {
        return "invalid_energy";
    }

    @Override
    protected MachineFrame.PylonStyle pylonStyle() {
        return MachineFrame.PylonStyle.ENERGY;
    }

    @Override
    protected void transferOnce() {
        for (BlockPos inPos : getInputs()) {
            if (!isReachable(inPos)) continue;
            IEnergyStorage source = level.getCapability(Capabilities.EnergyStorage.BLOCK, inPos, null);
            if (source == null || !source.canExtract()) continue;

            BlockPos outPos = nextOutput();
            if (outPos == null || !isReachable(outPos)) continue;
            IEnergyStorage dest = level.getCapability(Capabilities.EnergyStorage.BLOCK, outPos, null);
            if (dest == null || !dest.canReceive()) continue;

            int offered = source.extractEnergy(TRANSFER_RATE_FE, true);
            if (offered <= 0) continue;
            int accepted = dest.receiveEnergy(offered, true);
            if (accepted <= 0) continue;

            int taken = source.extractEnergy(accepted, false);
            if (taken <= 0) continue;
            dest.receiveEnergy(taken, false);

            if (level instanceof ServerLevel serverLevel) {
                spawnBolt(serverLevel, BOLT_PARTICLE, inPos, worldPosition);
                spawnBolt(serverLevel, BOLT_PARTICLE, worldPosition, outPos);
            }
        }
    }
}
