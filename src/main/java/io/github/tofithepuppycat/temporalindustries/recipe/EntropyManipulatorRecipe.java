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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
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

import java.util.List;
import java.util.Optional;

/**
 * A single step of the Entropy Manipulator's transmutation chains: either an item ({@code
 * input_item}) or a fluid ({@code input_fluid} + {@code fluid_amount}) consumed alongside
 * {@code entropy_cost} mB of liquid Order/Chaos over {@code process_ticks} to produce either a
 * fixed {@code result} item or, for a chaotic "any dust"-style output, one random item drawn from
 * {@code result_tag} (count {@code result_count}) each time the recipe completes -- see {@link
 * #rollResult}. Exactly one of input_item/input_fluid, and exactly one of result/result_tag, must
 * be present in the JSON.
 */
public class EntropyManipulatorRecipe implements Recipe<EntropyManipulatorRecipe.Input> {
    private final EntropyType entropyType;
    @Nullable private final Ingredient inputItem;
    @Nullable private final Fluid inputFluid;
    private final int fluidAmount;
    @Nullable private final ItemStack result;
    @Nullable private final TagKey<Item> resultTag;
    private final int resultCount;
    private final int processTicks;
    private final int entropyCost;

    public EntropyManipulatorRecipe(EntropyType entropyType, @Nullable Ingredient inputItem, @Nullable Fluid inputFluid,
                                       int fluidAmount, @Nullable ItemStack result, @Nullable TagKey<Item> resultTag,
                                       int resultCount, int processTicks, int entropyCost) {
        if ((inputItem == null) == (inputFluid == null)) {
            throw new IllegalArgumentException("Recipe must specify exactly one of input_item or input_fluid");
        }
        if ((result == null) == (resultTag == null)) {
            throw new IllegalArgumentException("Recipe must specify exactly one of result or result_tag");
        }
        this.entropyType = entropyType;
        this.inputItem = inputItem;
        this.inputFluid = inputFluid;
        this.fluidAmount = fluidAmount;
        this.result = result;
        this.resultTag = resultTag;
        this.resultCount = resultCount;
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

    public Optional<ItemStack> resultOpt() {
        return Optional.ofNullable(result);
    }

    public Optional<TagKey<Item>> resultTagOpt() {
        return Optional.ofNullable(resultTag);
    }

    public int resultCount() {
        return resultCount;
    }

    public boolean isTagResult() {
        return resultTag != null;
    }

    public Optional<Ingredient> inputItemOpt() {
        return Optional.ofNullable(inputItem);
    }

    public Optional<Fluid> inputFluidOpt() {
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
        return getResultItem(registries).copy();
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return true;
    }

    /** Every item this recipe could produce: just {@code result}, or every item in {@code result_tag}. */
    public List<ItemStack> possibleResults(HolderLookup.Provider registries) {
        if (result != null) return List.of(result);
        return registries.lookupOrThrow(Registries.ITEM).get(resultTag)
                .map(named -> named.stream().map(holder -> new ItemStack(holder, resultCount)).toList())
                .orElse(List.of());
    }

    /** A representative output for display/base-{@link Recipe} purposes; use {@link #rollResult} to actually craft. */
    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        if (result != null) return result;
        List<ItemStack> possible = possibleResults(registries);
        return possible.isEmpty() ? ItemStack.EMPTY : possible.get(0);
    }

    /** Picks the actual output of a completed craft: the fixed item, or a random draw from {@code result_tag}. */
    public ItemStack rollResult(HolderLookup.Provider registries, RandomSource random) {
        if (result != null) return result.copy();
        List<ItemStack> possible = possibleResults(registries);
        return possible.isEmpty() ? ItemStack.EMPTY : possible.get(random.nextInt(possible.size())).copy();
    }

    @Override
    public RecipeSerializer<? extends Recipe<Input>> getSerializer() {
        return Registration.ENTROPY_MANIPULATOR_RECIPE_SERIALIZER.get();
    }

    @Override
    public RecipeType<? extends Recipe<Input>> getType() {
        return Registration.ENTROPY_MANIPULATOR_RECIPE_TYPE.get();
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

    public static class Serializer implements RecipeSerializer<EntropyManipulatorRecipe> {
        private static final StreamCodec<RegistryFriendlyByteBuf, TagKey<Item>> TAG_STREAM_CODEC =
                ResourceLocation.STREAM_CODEC.<RegistryFriendlyByteBuf>cast()
                        .map(loc -> TagKey.create(Registries.ITEM, loc), TagKey::location);

        public static final MapCodec<EntropyManipulatorRecipe> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
                        EntropyType.CODEC.fieldOf("entropy_type").forGetter(EntropyManipulatorRecipe::entropyType),
                        Ingredient.CODEC.optionalFieldOf("input_item").forGetter(EntropyManipulatorRecipe::inputItemOpt),
                        BuiltInRegistries.FLUID.byNameCodec().optionalFieldOf("input_fluid").forGetter(EntropyManipulatorRecipe::inputFluidOpt),
                        Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("fluid_amount", 1000).forGetter(EntropyManipulatorRecipe::fluidAmount),
                        ItemStack.STRICT_CODEC.optionalFieldOf("result").forGetter(EntropyManipulatorRecipe::resultOpt),
                        TagKey.codec(Registries.ITEM).optionalFieldOf("result_tag").forGetter(EntropyManipulatorRecipe::resultTagOpt),
                        Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("result_count", 1).forGetter(EntropyManipulatorRecipe::resultCount),
                        Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("process_ticks", 100).forGetter(EntropyManipulatorRecipe::processTicks),
                        Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("entropy_cost", 100).forGetter(EntropyManipulatorRecipe::entropyCost))
                .apply(inst, (type, itemOpt, fluidOpt, fluidAmount, resultOpt, resultTagOpt, resultCount, processTicks, entropyCost) ->
                        new EntropyManipulatorRecipe(type, itemOpt.orElse(null), fluidOpt.orElse(null), fluidAmount,
                                resultOpt.orElse(null), resultTagOpt.orElse(null), resultCount, processTicks, entropyCost)));

        public static final StreamCodec<RegistryFriendlyByteBuf, EntropyManipulatorRecipe> STREAM_CODEC = StreamCodec.of(
                (buf, recipe) -> {
                    EntropyType.STREAM_CODEC.encode(buf, recipe.entropyType);
                    ByteBufCodecs.optional(Ingredient.CONTENTS_STREAM_CODEC).encode(buf, Optional.ofNullable(recipe.inputItem));
                    ByteBufCodecs.optional(ByteBufCodecs.registry(Registries.FLUID)).encode(buf, Optional.ofNullable(recipe.inputFluid));
                    ByteBufCodecs.VAR_INT.encode(buf, recipe.fluidAmount);
                    ByteBufCodecs.optional(ItemStack.STREAM_CODEC).encode(buf, Optional.ofNullable(recipe.result));
                    ByteBufCodecs.optional(TAG_STREAM_CODEC).encode(buf, Optional.ofNullable(recipe.resultTag));
                    ByteBufCodecs.VAR_INT.encode(buf, recipe.resultCount);
                    ByteBufCodecs.VAR_INT.encode(buf, recipe.processTicks);
                    ByteBufCodecs.VAR_INT.encode(buf, recipe.entropyCost);
                },
                buf -> {
                    EntropyType type = EntropyType.STREAM_CODEC.decode(buf);
                    Optional<Ingredient> item = ByteBufCodecs.optional(Ingredient.CONTENTS_STREAM_CODEC).decode(buf);
                    Optional<Fluid> fluid = ByteBufCodecs.optional(ByteBufCodecs.registry(Registries.FLUID)).decode(buf);
                    int fluidAmount = ByteBufCodecs.VAR_INT.decode(buf);
                    Optional<ItemStack> result = ByteBufCodecs.optional(ItemStack.STREAM_CODEC).decode(buf);
                    Optional<TagKey<Item>> resultTag = ByteBufCodecs.optional(TAG_STREAM_CODEC).decode(buf);
                    int resultCount = ByteBufCodecs.VAR_INT.decode(buf);
                    int processTicks = ByteBufCodecs.VAR_INT.decode(buf);
                    int entropyCost = ByteBufCodecs.VAR_INT.decode(buf);
                    return new EntropyManipulatorRecipe(type, item.orElse(null), fluid.orElse(null), fluidAmount,
                            result.orElse(null), resultTag.orElse(null), resultCount, processTicks, entropyCost);
                });

        @Override
        public MapCodec<EntropyManipulatorRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, EntropyManipulatorRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
