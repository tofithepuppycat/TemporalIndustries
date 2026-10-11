package io.github.tofithepuppycat.temporalindustries.device;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.TemporalLifelineBlockEntity;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.network.LifelineShieldPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Nullifies the block damage of any explosion (mob, TNT, crystal, bed...) inside chunks claimed by
 * a repository that has a Temporal Lifeline attached. The destroyed-block list is emptied before
 * the explosion applies it, so those blocks stay exactly as they were: nothing to roll back, and
 * since nothing breaks, nothing drops either. A shockwave and beams from the
 * Lifeline show players what was saved. Blocks in unprotected chunks still blow up.
 */
@EventBusSubscriber(modid = TemporalIndustries.MODID)
public final class TemporalLifelineListener {
    /** Beams drawn per explosion; more than this just turns into a solid wall of particles. */
    private static final int MAX_BEAMS = 24;
    /** Every player in the dimension within this many blocks of the blast sees the effect, not just those tracking its chunk. */
    private static final double SHIELD_VISIBLE_RANGE = 32.0;

    private TemporalLifelineListener() {}

    @SubscribeEvent
    public static void onDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (event.getAffectedBlocks().isEmpty()) return;

        Map<UUID, BlockPos> lifelines = TemporalLifelineBlockEntity.protectingLifelines(level);
        if (lifelines.isEmpty()) return;

        TemporalWorldData data = TemporalWorldData.get(level.getServer());
        ResourceLocation dimension = level.dimension().location();
        List<BlockPos> saved = new ArrayList<>();
        List<LifelineShieldPacket.Beam> beams = new ArrayList<>();
        event.getAffectedBlocks().removeIf(pos -> {
            UUID owner = data.getClaimingRepo(dimension, new ChunkPos(pos));
            BlockPos lifeline = owner == null ? null : lifelines.get(owner);
            if (lifeline == null) return false;
            saved.add(pos.immutable());
            if (!level.getBlockState(pos).isAir()) {
                beams.add(new LifelineShieldPacket.Beam(lifeline, pos.immutable()));
            }
            return true;
        });
        if (saved.isEmpty()) return;

        // Shockwave from the blast plus a beam from the Lifeline to a sample of the saved blocks.
        if (beams.size() > MAX_BEAMS) {
            Collections.shuffle(beams);
            beams.subList(MAX_BEAMS, beams.size()).clear();
        }
        Explosion explosion = event.getExplosion();
        Vec3 center = explosion.center();
        PacketDistributor.sendToPlayersNear(level, null, center.x, center.y, center.z, SHIELD_VISIBLE_RANGE,
                new LifelineShieldPacket(center, explosion.radius(), beams));
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        TemporalLifelineBlockEntity.clearRegistry();
    }
}
