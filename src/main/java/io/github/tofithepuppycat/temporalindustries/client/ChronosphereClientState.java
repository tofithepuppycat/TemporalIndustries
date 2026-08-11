package io.github.tofithepuppycat.temporalindustries.client;

import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.Set;

/** Client-side view of the active Chronosphere's map: which chunks it has claimed, and which
 * chunks in its claimable circle are blocked (claimed by another machine) — last reported by
 * {@link io.github.tofithepuppycat.temporalindustries.network.ChronosphereStateSyncPacket}. */
public final class ChronosphereClientState {
    private static BlockPos activeMachinePos;
    private static Set<Long> selectedChunks = new HashSet<>();
    private static Set<Long> blockedChunks = new HashSet<>();

    private ChronosphereClientState() {}

    public static void setActiveMachine(BlockPos machinePos) {
        activeMachinePos = machinePos;
    }

    public static void clearActiveMachine(BlockPos machinePos) {
        if (activeMachinePos != null && activeMachinePos.equals(machinePos)) {
            activeMachinePos = null;
            selectedChunks = new HashSet<>();
            blockedChunks = new HashSet<>();
        }
    }

    public static void updateFromServer(BlockPos machinePos, Set<Long> serverSelected, Set<Long> serverBlocked) {
        activeMachinePos = machinePos;
        selectedChunks = serverSelected;
        blockedChunks = serverBlocked;
    }

    public static boolean isSelected(long chunkKey) {
        return selectedChunks.contains(chunkKey);
    }

    public static boolean isBlocked(long chunkKey) {
        return blockedChunks.contains(chunkKey);
    }

    public static int getSelectedCount() {
        return selectedChunks.size();
    }
}
