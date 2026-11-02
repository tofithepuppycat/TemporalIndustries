package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.block.MachineFrame;
import net.minecraft.core.BlockPos;
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

    /** The green of the pylon's own glow. */
    public static final int COLOR = 0x53E159;

    public EnergyPylonBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state, null, COLOR);
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
                sendBolt(serverLevel, COLOR, inPos, worldPosition);
                sendBolt(serverLevel, COLOR, worldPosition, outPos);
            }
        }
    }
}
