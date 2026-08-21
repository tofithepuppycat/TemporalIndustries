package io.github.tofithepuppycat.temporalindustries.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * A single step of the Entropy Change Inducer's transmutation chains: either an item ({@code
 * input_item}) or a fluid ({@code input_fluid} + {@code fluid_amount}) consumed alongside
 * {@code entropy_cost} mB of liquid Order/Chaos over {@code process_ticks} to produce {@code
 * result}. Exactly one of input_item/input_fluid must be present in the JSON.
 */
public class EntropyChangeInducerRecipe implements Recipe<EntropyChangeInducerRecipe.Input> {
    private final EntropyType entropyType;
    @Nullable private final Ingredient inputItem;
    @Nullable private final Fluid inputFluid;
    private final int fluidAmount;
    private final ItemStack result;
    private final int processTicks;
    private final int entropyCost;

    public EntropyChangeInducerRecipe(EntropyType entropyType, @Nullable Ingredient inputItem, @Nullable Fluid inputFluid,
                                       int fluidAmount, ItemStack result, int processTicks, int entropyCost) {
        if ((inputItem == null) == (inputFluid == null)) {
            throw new IllegalArgumentException("Recipe must specify exactly one of input_item or input_fluid");
        }
        this.entropyType = entropyType;
        this.inputItem = inputItem;
        this.inputFluid = inputFluid;
        this.fluidAmount = fluidAmount;
        this.result = result;
        this.processTicks = processTicks;
        this.entropyCost = entropyCost;
    }

    public EntropyType entropyType() {
        return entropyType;
    }

    public boolean isFluidRecipe() {
        return inputFluid != null;
    }

    public int fluidAmount() {
        return fluidAmount;
    }

    public int processTicks() {
        return processTicks;
    }

    public int entropyCost() {
        return entropyCost;
    }

    private ItemStack result() {
        return result;
    }

    private Optional<Ingredient> inputItemOpt() {
        return Optional.ofNullable(inputItem);
    }

    private Optional<Fluid> inputFluidOpt() {
        return Optional.ofNullable(inputFluid);
    }

    @Override
    public boolean matches(Input input, Level level) {
        if (inputItem != null) {
            return inputItem.test(input.item());
        }
        FluidStack fluid = input.fluid();
        return !fluid.isEmpty() && fluid.getFluid().isSame(inputFluid) && fluid.getAmount() >= fluidAmount;
    }

    @Override
    public ItemStack assemble(Input input, HolderLookup.Provider registries) {
        return result.copy();
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return true;
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return result;
    }

    @Override
    public RecipeSerializer<? extends Recipe<Input>> getSerializer() {
        return Registration.ENTROPY_CHANGE_INDUCER_RECIPE_SERIALIZER.get();
    }

    @Override
    public RecipeType<? extends Recipe<Input>> getType() {
        return Registration.ENTROPY_CHANGE_INDUCER_RECIPE_TYPE.get();
    }

    /** The input item slot's stack plus the liquid tank's current fluid; a recipe only ever reads one. */
    public record Input(ItemStack item, FluidStack fluid) implements RecipeInput {
        @Override
        public ItemStack getItem(int index) {
            return item;
        }

        @Override
        public int size() {
            return 1;
        }
    }

    public static class Serializer implements RecipeSerializer<EntropyChangeInducerRecipe> {
        public static final MapCodec<EntropyChangeInducerRecipe> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
                        EntropyType.CODEC.fieldOf("entropy_type").forGetter(EntropyChangeInducerRecipe::entropyType),
                        Ingredient.CODEC.optionalFieldOf("input_item").forGetter(EntropyChangeInducerRecipe::inputItemOpt),
                        BuiltInRegistries.FLUID.byNameCodec().optionalFieldOf("input_fluid").forGetter(EntropyChangeInducerRecipe::inputFluidOpt),
                        Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("fluid_amount", 1000).forGetter(EntropyChangeInducerRecipe::fluidAmount),
                        ItemStack.STRICT_CODEC.fieldOf("result").forGetter(EntropyChangeInducerRecipe::result),
                        Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("process_ticks", 100).forGetter(EntropyChangeInducerRecipe::processTicks),
                        Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("entropy_cost", 100).forGetter(EntropyChangeInducerRecipe::entropyCost))
                .apply(inst, (type, itemOpt, fluidOpt, fluidAmount, result, processTicks, entropyCost) ->
                        new EntropyChangeInducerRecipe(type, itemOpt.orElse(null), fluidOpt.orElse(null), fluidAmount, result, processTicks, entropyCost)));

        public static final StreamCodec<RegistryFriendlyByteBuf, EntropyChangeInducerRecipe> STREAM_CODEC = StreamCodec.of(
                (buf, recipe) -> {
                    EntropyType.STREAM_CODEC.encode(buf, recipe.entropyType);
                    ByteBufCodecs.optional(Ingredient.CONTENTS_STREAM_CODEC).encode(buf, Optional.ofNullable(recipe.inputItem));
                    ByteBufCodecs.optional(ByteBufCodecs.registry(Registries.FLUID)).encode(buf, Optional.ofNullable(recipe.inputFluid));
                    ByteBufCodecs.VAR_INT.encode(buf, recipe.fluidAmount);
                    ItemStack.STREAM_CODEC.encode(buf, recipe.result);
                    ByteBufCodecs.VAR_INT.encode(buf, recipe.processTicks);
                    ByteBufCodecs.VAR_INT.encode(buf, recipe.entropyCost);
                },
                buf -> {
                    EntropyType type = EntropyType.STREAM_CODEC.decode(buf);
                    Optional<Ingredient> item = ByteBufCodecs.optional(Ingredient.CONTENTS_STREAM_CODEC).decode(buf);
                    Optional<Fluid> fluid = ByteBufCodecs.optional(ByteBufCodecs.registry(Registries.FLUID)).decode(buf);
                    int fluidAmount = ByteBufCodecs.VAR_INT.decode(buf);
                    ItemStack result = ItemStack.STREAM_CODEC.decode(buf);
                    int processTicks = ByteBufCodecs.VAR_INT.decode(buf);
                    int entropyCost = ByteBufCodecs.VAR_INT.decode(buf);
                    return new EntropyChangeInducerRecipe(type, item.orElse(null), fluid.orElse(null), fluidAmount, result, processTicks, entropyCost);
                });

        @Override
        public MapCodec<EntropyChangeInducerRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, EntropyChangeInducerRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
