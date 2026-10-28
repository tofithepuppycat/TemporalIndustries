package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.client.screen.ChronoRemoteChunkScreen;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineProjectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * What the Chrono Remote Control is doing on the client between wheel selections: outlining the
 * linked machine's selected chunks in-world, or previewing a commit until the player jumps or
 * cancels. Also holds the last-synced settings of the linked machine, which the settings wheel
 * displays.
 */
public final class ChronoRemoteClientState {
    private enum Mode { IDLE, CHUNK_PREVIEW, COMMIT_PREVIEW }

    private static Mode mode = Mode.IDLE;
    private static long previewCommitId = -1L;

    private static boolean bound;
    private static boolean chronosphere;
    private static boolean saveEntities;
    private static boolean chargeContainers;

    private ChronoRemoteClientState() {}

    public static boolean isChunkPreview() {
        return mode == Mode.CHUNK_PREVIEW;
    }

    public static boolean isPreviewing() {
        return mode == Mode.COMMIT_PREVIEW;
    }

    public static boolean isActive() {
        return mode != Mode.IDLE;
    }

    public static long getPreviewCommitId() {
        return previewCommitId;
    }

    /** Starts or stops outlining the selected chunks in-world; ends any commit preview first. */
    public static void setChunkPreview(boolean enabled) {
        endSession();
        if (enabled) {
            mode = Mode.CHUNK_PREVIEW;
        }
    }

    /** Shows the changes a jump to commitId would make; the selection must already be set on {@link TimelineProjectionManager}. */
    public static void startPreview(long commitId) {
        mode = Mode.COMMIT_PREVIEW;
        previewCommitId = commitId;
        TimelineProjectionManager.enableShowChanges();
    }

    public static void endSession() {
        if (mode == Mode.COMMIT_PREVIEW) {
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
        if (chronosphereNow && boundNow) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        boolean selecting = minecraft.screen instanceof ChronoRemoteChunkScreen;
        if (!selecting && mode != Mode.CHUNK_PREVIEW) {
            return;
        }
        if (selecting) {
            minecraft.setScreen(null);
        }
        if (mode == Mode.CHUNK_PREVIEW) {
            mode = Mode.IDLE;
        }
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(boundNow
                    ? "gui.temporalindustries.chrono_remote.msg.single_chunk"
                    : "gui.temporalindustries.chrono_remote.msg.unbound"), true);
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
