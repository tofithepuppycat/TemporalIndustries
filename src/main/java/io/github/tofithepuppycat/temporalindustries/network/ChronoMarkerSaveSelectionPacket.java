package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.item.PortableChronoMarkerItem;
import net.minecraft.network.chat.Component;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Client -> server: confirm the Portable Chrono Marker's area-select map, saving the player's
 * chosen chunk offsets onto the marker item as its custom shape. Never marks anything itself — only
 * a plain right-click does that. Offsets are relative to wherever the marker is next used, so a
 * saved shape is reusable anywhere. Re-validated server-side; nothing from the client is trusted
 * beyond "here are some chunk offsets". */
public class ChronoMarkerSaveSelectionPacket implements CustomPacketPayload {
    public static final Type<ChronoMarkerSaveSelectionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chrono_marker_save_selection"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronoMarkerSaveSelectionPacket> STREAM_CODEC =
            StreamCodec.of(ChronoMarkerSaveSelectionPacket::encode, ChronoMarkerSaveSelectionPacket::decode);

    private final List<PortableChronoMarkerItem.ChunkOffset> offsets;

    public ChronoMarkerSaveSelectionPacket(List<PortableChronoMarkerItem.ChunkOffset> offsets) {
        this.offsets = offsets;
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChronoMarkerSaveSelectionPacket packet) {
        buf.writeVarInt(packet.offsets.size());
        for (PortableChronoMarkerItem.ChunkOffset offset : packet.offsets) {
            buf.writeVarInt(offset.dx());
            buf.writeVarInt(offset.dz());
        }
    }

    public static ChronoMarkerSaveSelectionPacket decode(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<PortableChronoMarkerItem.ChunkOffset> offsets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            offsets.add(new PortableChronoMarkerItem.ChunkOffset(buf.readVarInt(), buf.readVarInt()));
        }
        return new ChronoMarkerSaveSelectionPacket(offsets);
    }

    public static void handle(ChronoMarkerSaveSelectionPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            ItemStack stack = holdingMarker(sender);
            if (stack == null) return;

            int radius = PortableChronoMarkerItem.MAP_RADIUS_CHUNKS;
            Set<PortableChronoMarkerItem.ChunkOffset> validated = new LinkedHashSet<>();
            validated.add(new PortableChronoMarkerItem.ChunkOffset(0, 0));
            for (PortableChronoMarkerItem.ChunkOffset offset : packet.offsets) {
                if (PortableChronoMarkerItem.MAP_SHAPE.contains(radius, offset.dx(), offset.dz())) {
                    validated.add(offset);
                }
            }

            PortableChronoMarkerItem.saveOffsets(stack, new ArrayList<>(validated));
            sender.displayClientMessage(Component.translatable("item.temporalindustries.portable_chrono_marker.area_saved"), true);
            sender.level().playSound(null, sender.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.4F);
        });
    }

    /** Whichever hand currently holds the Portable Chrono Marker (main hand preferred), or null if neither does. */
    private static ItemStack holdingMarker(ServerPlayer sender) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = sender.getItemInHand(hand);
            if (stack.getItem() instanceof PortableChronoMarkerItem) return stack;
        }
        return null;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
