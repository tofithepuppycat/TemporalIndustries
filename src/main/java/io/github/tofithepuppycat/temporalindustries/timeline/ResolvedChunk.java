package io.github.tofithepuppycat.temporalindustries.timeline;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What one chunk should look like after a jump, without expanding it into a per-block map.
 * <p>
 * Either a {@link ChunkSnapshot} baseline with sparse edits layered over it, or (when the jump path
 * only crosses deltas) just the sparse edits. Changes against the live chunk are found one section
 * at a time, so a caller can spread the work over as many ticks as it needs.
 */
final class ResolvedChunk {
    /** A chunk that has no recorded state at all. */
    static final ResolvedChunk NONE = new ResolvedChunk(null, Map.of(), Map.of(), 0, 0);

    @Nullable private final ChunkSnapshot base;
    private final Map<BlockPos, BlockState> overlay;
    private final Map<BlockPos, CompoundTag> blockEntityTags;
    private final int minSectionY;
    private final int sectionCount;
    /** Section index -> positions in it that overlay or block entity data single out; built on first use. */
    @Nullable private Map<Integer, Set<BlockPos>> sparseBySection;

    ResolvedChunk(@Nullable ChunkSnapshot base, Map<BlockPos, BlockState> overlay,
                  Map<BlockPos, CompoundTag> blockEntityTags, int minSectionY, int sectionCount) {
        this.base = base;
        this.overlay = overlay;
        this.blockEntityTags = blockEntityTags;
        this.minSectionY = minSectionY;
        this.sectionCount = sectionCount;
    }

    /** The recorded block entity data per position; a null value means the block has none. */
    Map<BlockPos, CompoundTag> blockEntityTags() {
        return blockEntityTags;
    }

    /** Whether this state says what pos holds, rather than leaving it as it is. */
    boolean covers(BlockPos pos) {
        return base != null || overlay.containsKey(pos);
    }

    BlockState targetState(BlockPos pos) {
        BlockState state = overlay.get(pos);
        if (state != null) return state;
        return base != null ? base.stateAt(pos) : Blocks.AIR.defaultBlockState();
    }

    @Nullable
    CompoundTag blockEntityTag(BlockPos pos) {
        return blockEntityTags.get(pos);
    }

    /** The section indices that can hold a change, ascending. */
    int[] sectionsToVisit() {
        if (base != null) {
            int[] all = new int[sectionCount];
            for (int i = 0; i < sectionCount; i++) all[i] = i;
            return all;
        }
        return sparse().keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
    }

    /**
     * Positions of section sectionIndex whose live block differs from the target, or that carry
     * block entity data to restore, bottom-up so support blocks are written before what rests on
     * them. Positions isGlued accepts are left out.
     */
    List<BlockPos> changesInSection(int sectionIndex, LevelChunk live, Predicate<BlockPos> isGlued) {
        List<BlockPos> out = new ArrayList<>();
        Set<BlockPos> sparse = sparse().get(sectionIndex);

        if (base != null) {
            base.collectDifferingInto(sectionIndex, live.getSection(sectionIndex), out);
            if (sparse != null) out.removeIf(overlay::containsKey);
        }
        if (sparse != null) {
            for (BlockPos pos : sparse) {
                BlockState target = overlay.get(pos);
                boolean inOverlay = target != null;
                if (!inOverlay) {
                    if (base == null) continue;
                    target = base.stateAt(pos);
                }
                boolean differs = !live.getBlockState(pos).equals(target);
                boolean hasBlockEntity = blockEntityTags.get(pos) != null;
                // A differing base position was already collected from the baseline.
                if (inOverlay ? differs || hasBlockEntity : !differs && hasBlockEntity) out.add(pos);
            }
            out.sort(ChunkSnapshot.BOTTOM_UP_ORDER);
        }
        out.removeIf(isGlued);
        return out;
    }

    private Map<Integer, Set<BlockPos>> sparse() {
        if (sparseBySection == null) {
            Map<Integer, Set<BlockPos>> map = new HashMap<>();
            for (BlockPos pos : overlay.keySet()) addSparse(map, pos);
            for (Map.Entry<BlockPos, CompoundTag> entry : blockEntityTags.entrySet()) {
                if (entry.getValue() != null) addSparse(map, entry.getKey());
            }
            sparseBySection = map;
        }
        return sparseBySection;
    }

    private void addSparse(Map<Integer, Set<BlockPos>> map, BlockPos pos) {
        int index = (pos.getY() >> 4) - minSectionY;
        if (index < 0 || index >= sectionCount) return;
        map.computeIfAbsent(index, k -> new HashSet<>()).add(pos);
    }
}
