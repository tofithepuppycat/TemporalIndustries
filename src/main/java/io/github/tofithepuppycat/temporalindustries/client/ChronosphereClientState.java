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
    private static boolean autoTrackingEnabled = false;
    private static int trackedCount = 0;

    private ChronosphereClientState() {}

    public static void setActiveMachine(BlockPos machinePos) {
        activeMachinePos = machinePos;
    }

    public static void clearActiveMachine(BlockPos machinePos) {
        if (activeMachinePos != null && activeMachinePos.equals(machinePos)) {
            clearAll();
        }
    }

    /** Unconditionally drops all client-side state; called on world/server disconnect. */
    public static void clearAll() {
        activeMachinePos = null;
        selectedChunks = new HashSet<>();
        blockedChunks = new HashSet<>();
        autoTrackingEnabled = false;
        trackedCount = 0;
    }

    public static void updateFromServer(BlockPos machinePos, Set<Long> serverSelected, Set<Long> serverBlocked,
                                        boolean serverAutoTrackingEnabled, int serverTrackedCount) {
        activeMachinePos = machinePos;
        selectedChunks = serverSelected;
        blockedChunks = serverBlocked;
        autoTrackingEnabled = serverAutoTrackingEnabled;
        trackedCount = serverTrackedCount;
    }

    public static boolean isAutoTrackingEnabled() {
        return autoTrackingEnabled;
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

    /** Every claimed chunk's packed key, used to build the Chronosphere GUI's per-chunk timeline tabs. */
    public static Set<Long> getSelectedChunks() {
        return selectedChunks;
    }

    /** How many claimed chunks are currently tracked; may be less than getSelectedCount() when
     * auto-tracking is off. */
    public static int getTrackedCount() {
        return trackedCount;
    }
}
