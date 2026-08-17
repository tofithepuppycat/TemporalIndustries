package io.github.tofithepuppycat.temporalindustries.capture;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.Optional;

/** The mob a Schrodinger's Box has captured, carried as a data component on the item and mirrored
 * onto the placed block entity via {@code applyImplicitComponents}/{@code collectImplicitComponents}. */
public record CapturedMob(ResourceLocation entityTypeId, Optional<Component> customName) {
    public static final Codec<CapturedMob> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("entity_type").forGetter(CapturedMob::entityTypeId),
            ComponentSerialization.CODEC.optionalFieldOf("custom_name").forGetter(CapturedMob::customName)
    ).apply(instance, CapturedMob::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, CapturedMob> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, CapturedMob::entityTypeId,
            ByteBufCodecs.optional(ComponentSerialization.STREAM_CODEC), CapturedMob::customName,
            CapturedMob::new
    );

    public EntityType<?> entityType() {
        return BuiltInRegistries.ENTITY_TYPE.get(entityTypeId);
    }
}
