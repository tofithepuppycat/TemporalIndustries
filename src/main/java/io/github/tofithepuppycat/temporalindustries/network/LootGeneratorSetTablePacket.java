package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.LootGeneratorBlockEntity;
import io.github.tofithepuppycat.temporalindustries.menu.LootGeneratorMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: sets a Loot Generator's selected loot table id; the server re-validates it
 * rather than trusting the client. */
public class LootGeneratorSetTablePacket implements CustomPacketPayload {
    public static final Type<LootGeneratorSetTablePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "loot_generator_set_table"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LootGeneratorSetTablePacket> STREAM_CODEC =
            StreamCodec.of(LootGeneratorSetTablePacket::encode, LootGeneratorSetTablePacket::decode);

    private final BlockPos machinePos;
    private final String lootTableId;

    public LootGeneratorSetTablePacket(BlockPos machinePos, String lootTableId) {
        this.machinePos = machinePos;
        this.lootTableId = lootTableId;
    }

    public static void encode(RegistryFriendlyByteBuf buf, LootGeneratorSetTablePacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeUtf(packet.lootTableId);
    }

    public static LootGeneratorSetTablePacket decode(RegistryFriendlyByteBuf buf) {
        return new LootGeneratorSetTablePacket(buf.readBlockPos(), buf.readUtf());
    }

    public static void handle(LootGeneratorSetTablePacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof LootGeneratorMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;

            BlockEntity be = sender.level().getBlockEntity(packet.machinePos);
            if (!(be instanceof LootGeneratorBlockEntity generator)) return;

            String trimmed = packet.lootTableId.trim();
            generator.setSelectedLootTable(trimmed.isEmpty() ? null : ResourceLocation.tryParse(trimmed));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
