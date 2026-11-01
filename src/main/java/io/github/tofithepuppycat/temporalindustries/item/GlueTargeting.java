package io.github.tofithepuppycat.temporalindustries.item;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Lets the glue tools pick a corner out of thin air when the player is not looking at a block. */
public final class GlueTargeting {
    private GlueTargeting() {}

    /**
     * The block at the far end of the player's reach along their line of sight, or empty if a block
     * is in the way (which the tool then handles as an ordinary click on that block).
     */
    public static Optional<BlockPos> airTarget(Level level, Player player) {
        Vec3 origin = player.getEyePosition();
        Vec3 end = origin.add(player.getViewVector(1.0F).scale(player.blockInteractionRange()));
        HitResult hit = level.clip(new ClipContext(origin, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.MISS) return Optional.empty();
        return Optional.of(BlockPos.containing(end));
    }
}
