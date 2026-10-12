package io.github.tofithepuppycat.temporalindustries.client;

import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Client-side view of the active Chronosphere's map: which chunks it has claimed, and which chunks
 * in its claimable circle are blocked (claimed by another machine) — last reported by {@link
 * io.github.tofithepuppycat.temporalindustries.network.ChronosphereStateSyncPacket}.
 */
public final class ChronosphereClientState {
    private static BlockPos activeMachinePos;
    private static Set<Long> selectedChunks = new HashSet<>();
    private static Set<Long> blockedChunks = new HashSet<>();
    private static int capturedCount = 0;
    private static String currentBranch = "";
    private static String suggestionQuery = "";
    private static List<String> suggestions = List.of();

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
        capturedCount = 0;
        currentBranch = "";
        clearSuggestions();
    }

    public static void updateFromServer(BlockPos machinePos, Set<Long> serverSelected, Set<Long> serverBlocked,
                                        int serverCapturedCount, String serverCurrentBranch) {
        activeMachinePos = machinePos;
        selectedChunks = serverSelected;
        blockedChunks = serverBlocked;
        capturedCount = serverCapturedCount;
        currentBranch = serverCurrentBranch;
    }

    /**
     * The query the current repository-name suggestions answer, so a late reply to text the player
     * has since changed can be told apart.
     */
    public static String getSuggestionQuery() {
        return suggestionQuery;
    }

    public static List<String> getSuggestions() {
        return suggestions;
    }

    public static void updateSuggestions(String query, List<String> names) {
        suggestionQuery = query;
        suggestions = names;
    }

    public static void clearSuggestions() {
        updateSuggestions("", List.of());
    }

    public static String getCurrentBranch() {
        return currentBranch;
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

    /** Every claimed chunk's packed key, used to drive the Chronosphere GUI's claim map. */
    public static Set<Long> getSelectedChunks() {
        return selectedChunks;
    }

    /**
     * How many claimed chunks this machine's repository actually holds the claim on; below
     * getSelectedCount() only when another machine beat it to one.
     */
    public static int getCapturedCount() {
        return capturedCount;
    }
}
