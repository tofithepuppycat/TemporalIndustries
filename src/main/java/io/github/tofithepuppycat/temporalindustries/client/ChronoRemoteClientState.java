package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineProjectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * What the Chrono Remote Control is doing on the client between wheel selections: picking chunks
 * with left-click, or previewing a commit in-world until the player jumps or cancels. Also holds the
 * last-synced settings of the linked machine, which the settings wheel displays.
 */
public final class ChronoRemoteClientState {
    private enum Mode { IDLE, SELECTING, PREVIEWING }

    private static Mode mode = Mode.IDLE;
    private static long previewCommitId = -1L;

    private static boolean bound;
    private static boolean chronosphere;
    private static boolean saveEntities;
    private static boolean chargeContainers;

    private ChronoRemoteClientState() {}

    public static boolean isSelecting() {
        return mode == Mode.SELECTING;
    }

    public static boolean isPreviewing() {
        return mode == Mode.PREVIEWING;
    }

    public static boolean isActive() {
        return mode != Mode.IDLE;
    }

    public static long getPreviewCommitId() {
        return previewCommitId;
    }

    public static void startSelecting() {
        endSession();
        mode = Mode.SELECTING;
    }

    /** Shows the changes a jump to commitId would make; the selection must already be set on {@link TimelineProjectionManager}. */
    public static void startPreview(long commitId) {
        mode = Mode.PREVIEWING;
        previewCommitId = commitId;
        TimelineProjectionManager.enableShowChanges();
    }

    public static void endSession() {
        if (mode == Mode.PREVIEWING) {
            TimelineProjectionManager.clearAll();
        }
        mode = Mode.IDLE;
        previewCommitId = -1L;
    }

    public static boolean isBound() { return bound; }
    public static boolean isChronosphere() { return chronosphere; }
    public static boolean isSaveEntities() { return saveEntities; }
    public static boolean isChargeContainers() { return chargeContainers; }

    public static void onSettingsSynced(boolean boundNow, boolean chronosphereNow, boolean saveEntitiesNow,
                                        boolean chargeContainersNow) {
        bound = boundNow;
        chronosphere = chronosphereNow;
        saveEntities = saveEntitiesNow;
        chargeContainers = chargeContainersNow;

        // Chunk selection only makes sense on a machine that claims more than its own chunk.
        if (mode == Mode.SELECTING && (!chronosphereNow || !boundNow)) {
            mode = Mode.IDLE;
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.translatable(boundNow
                        ? "gui.temporalindustries.chrono_remote.msg.single_chunk"
                        : "gui.temporalindustries.chrono_remote.msg.unbound"), true);
            }
        }
    }

    /** Drops the session and cached settings, e.g. when leaving a world. */
    public static void clearAll() {
        endSession();
        bound = false;
        chronosphere = false;
        saveEntities = false;
        chargeContainers = false;
    }
}
