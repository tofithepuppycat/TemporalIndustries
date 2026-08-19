package io.github.tofithepuppycat.temporalindustries;

import static io.github.tofithepuppycat.temporalindustries.TemporalIndustries.MODID;
import io.github.tofithepuppycat.temporalindustries.block.EchoProjector;
import io.github.tofithepuppycat.temporalindustries.block.Chronodial;
import io.github.tofithepuppycat.temporalindustries.block.Chronosphere;
import io.github.tofithepuppycat.temporalindustries.block.CrudeEntropyCondenser;
import io.github.tofithepuppycat.temporalindustries.block.EntropyCondenser;
import io.github.tofithepuppycat.temporalindustries.block.SchrodingersBox;
import io.github.tofithepuppycat.temporalindustries.block.SeebeckGenerator;
import io.github.tofithepuppycat.temporalindustries.block.Chronovault;
import io.github.tofithepuppycat.temporalindustries.block.entity.ChronoProjectorBlockEntity;
import io.github.tofithepuppycat.temporalindustries.block.entity.ChronodialBlockEntity;
import io.github.tofithepuppycat.temporalindustries.block.entity.ChronosphereBlockEntity;
import io.github.tofithepuppycat.temporalindustries.block.entity.CrudeEntropyCondenserBlockEntity;
import io.github.tofithepuppycat.temporalindustries.block.entity.EntropyCondenserBlockEntity;
import io.github.tofithepuppycat.temporalindustries.block.entity.SchrodingersBoxBlockEntity;
import io.github.tofithepuppycat.temporalindustries.block.entity.SeebeckGeneratorBlockEntity;
import io.github.tofithepuppycat.temporalindustries.block.entity.ChronovaultBlockEntity;
import io.github.tofithepuppycat.temporalindustries.capture.CapturedMob;
import io.github.tofithepuppycat.temporalindustries.entropy.BottleContents;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyContents;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyOrbEntity;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.item.EchoRecordItem;
import io.github.tofithepuppycat.temporalindustries.item.EntropyCellItem;
import io.github.tofithepuppycat.temporalindustries.item.EntropyGlassesItem;
import io.github.tofithepuppycat.temporalindustries.item.DualEntropyCellItem;
import io.github.tofithepuppycat.temporalindustries.item.PortableChronoMarkerItem;
import io.github.tofithepuppycat.temporalindustries.item.SchrodingersBoxItem;
import io.github.tofithepuppycat.temporalindustries.item.TemporalAnchorItem;
import io.github.tofithepuppycat.temporalindustries.item.TemporalGlueItem;
import io.github.tofithepuppycat.temporalindustries.menu.ChronosphereMenu;
import io.github.tofithepuppycat.temporalindustries.menu.CrudeEntropyCondenserMenu;
import io.github.tofithepuppycat.temporalindustries.menu.EntropyCondenserMenu;
import io.github.tofithepuppycat.temporalindustries.menu.ChronovaultMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.fml.common.asm.enumextension.EnumProxy;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.List;
import java.util.Map;

@SuppressWarnings("null")
public class Registration {

    // "Echo"-prefixed items get a dark cyan name; "Chrono"-prefixed items reuse vanilla EPIC (light purple).
    // See META-INF/enumextensions.json - the -1 is a placeholder for Rarity's id param, which FML fills in with the ordinal.
    public static final EnumProxy<Rarity> ECHO_RARITY = new EnumProxy<>(Rarity.class, -1, MODID + ":echo", ChatFormatting.DARK_AQUA);

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MODID);
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    public static final DeferredRegister.DataComponents DATA_COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MODID);
    public static final DeferredRegister<FluidType> FLUID_TYPES = DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, MODID);
    public static final DeferredRegister<Fluid> FLUIDS = DeferredRegister.create(Registries.FLUID, MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredRegister<ArmorMaterial> ARMOR_MATERIALS = DeferredRegister.create(Registries.ARMOR_MATERIAL, MODID);

    public static final DeferredBlock<Chronovault> CHRONOVAULT_BLOCK = BLOCKS.register("chronovault",
            () -> new Chronovault(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5F, 6.0F).sound(SoundType.METAL).requiresCorrectToolForDrops()));

    public static final DeferredItem<Item> CHRONOVAULT_ITEM = ITEMS.register("chronovault",
            () -> new BlockItem(CHRONOVAULT_BLOCK.get(), new Item.Properties().rarity(Rarity.EPIC)));

    public static final DeferredItem<Item> TEMPORAL_ANCHOR_ITEM = ITEMS.register("temporal_anchor",
            () -> new TemporalAnchorItem(new Item.Properties().stacksTo(1)));

    public static final DeferredBlock<EchoProjector> ECHO_PROJECTOR_BLOCK = BLOCKS.register("echo_projector",
            () -> new EchoProjector(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_CYAN).strength(3.0F, 6.0F).sound(SoundType.AMETHYST).requiresCorrectToolForDrops().lightLevel(state -> 3)));

    public static final DeferredItem<Item> ECHO_PROJECTOR_ITEM = ITEMS.register("echo_projector",
            () -> new BlockItem(ECHO_PROJECTOR_BLOCK.get(), new Item.Properties().rarity(ECHO_RARITY.getValue())));

    public static final DeferredItem<Item> ECHO_RECORD_ITEM = ITEMS.register("echo_record",
            () -> new EchoRecordItem(new Item.Properties().stacksTo(1).rarity(ECHO_RARITY.getValue())));

    public static final DeferredItem<Item> PORTABLE_CHRONO_MARKER_ITEM = ITEMS.register("portable_chrono_marker",
            () -> new PortableChronoMarkerItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    public static final DeferredItem<Item> TEMPORAL_GLUE_ITEM = ITEMS.register("temporal_glue",
            () -> new TemporalGlueItem(new Item.Properties().durability(20)));

    // --- Entropy Glasses: no defense, lets the wearer see EntropyInfoProvider block entities' state ---

    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> ENTROPY_GLASSES_MATERIAL = ARMOR_MATERIALS.register("entropy_glasses",
            () -> new ArmorMaterial(Map.of(ArmorItem.Type.HELMET, 0), 9, SoundEvents.ARMOR_EQUIP_LEATHER,
                    () -> Ingredient.of(net.minecraft.world.item.Items.GLASS),
                    List.of(new ArmorMaterial.Layer(ResourceLocation.fromNamespaceAndPath(MODID, "entropy_glasses"))),
                    0.0F, 0.0F));

    public static final DeferredItem<Item> ENTROPY_GLASSES_ITEM = ITEMS.register("entropy_glasses",
            () -> new EntropyGlassesItem(ENTROPY_GLASSES_MATERIAL, ArmorItem.Type.HELMET,
                    new Item.Properties().stacksTo(1)));

    public static final DeferredBlock<Chronodial> CHRONODIAL_BLOCK = BLOCKS.register("chronodial",
            () -> new Chronodial(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(2.5F, 6.0F).sound(SoundType.AMETHYST).requiresCorrectToolForDrops()));

    public static final DeferredItem<Item> CHRONODIAL_ITEM = ITEMS.register("chronodial",
            () -> new BlockItem(CHRONODIAL_BLOCK.get(), new Item.Properties().rarity(Rarity.EPIC)));

    public static final DeferredBlock<Chronosphere> CHRONOSPHERE_BLOCK = BLOCKS.register("chronosphere",
            () -> new Chronosphere(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE).strength(4.5F, 8.0F).sound(SoundType.AMETHYST).requiresCorrectToolForDrops().lightLevel(state -> 5)));

    public static final DeferredItem<Item> CHRONOSPHERE_ITEM = ITEMS.register("chronosphere",
            () -> new BlockItem(CHRONOSPHERE_BLOCK.get(), new Item.Properties().rarity(Rarity.EPIC)));

    public static final DeferredItem<Item> DUAL_ENTROPY_CELL_ITEM = ITEMS.register("dual_entropy_cell",
            () -> new DualEntropyCellItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<EntropyContents>> ENTROPY_CONTENTS = DATA_COMPONENTS.registerComponentType(
            "entropy_contents", builder -> builder.persistent(EntropyContents.CODEC).networkSynchronized(EntropyContents.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BottleContents>> BOTTLE_CONTENTS = DATA_COMPONENTS.registerComponentType(
            "bottle_contents", builder -> builder.persistent(BottleContents.CODEC).networkSynchronized(BottleContents.STREAM_CODEC));

    public static final DeferredItem<Item> ORDER_CELL_ITEM = ITEMS.register("order_cell",
            () -> new EntropyCellItem(EntropyType.ORDER, new Item.Properties().stacksTo(1)));

    public static final DeferredItem<Item> CHAOS_CELL_ITEM = ITEMS.register("chaos_cell",
            () -> new EntropyCellItem(EntropyType.CHAOS, new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CapturedMob>> CAPTURED_MOB = DATA_COMPONENTS.registerComponentType(
            "captured_mob", builder -> builder.persistent(CapturedMob.CODEC).networkSynchronized(CapturedMob.STREAM_CODEC));

    public static final DeferredHolder<EntityType<?>, EntityType<EntropyOrbEntity>> ENTROPY_ORB = ENTITY_TYPES.register("entropy_orb",
            () -> EntityType.Builder.<EntropyOrbEntity>of(EntropyOrbEntity::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F).clientTrackingRange(6).updateInterval(20).fireImmune().build("entropy_orb"));

    public static final DeferredBlock<SchrodingersBox> SCHRODINGERS_BOX_BLOCK = BLOCKS.register("schrodingers_box",
            () -> new SchrodingersBox(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(2.0F, 4.0F).sound(SoundType.WOOD)));

    public static final DeferredItem<Item> SCHRODINGERS_BOX_ITEM = ITEMS.register("schrodingers_box",
            () -> new SchrodingersBoxItem(SCHRODINGERS_BOX_BLOCK.get(), new Item.Properties().stacksTo(1)));

    public static final DeferredBlock<SeebeckGenerator> SEEBECK_GENERATOR_BLOCK = BLOCKS.register("seebeck_generator",
            () -> new SeebeckGenerator(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5F, 6.0F).sound(SoundType.METAL).requiresCorrectToolForDrops()));

    public static final DeferredItem<Item> SEEBECK_GENERATOR_ITEM = ITEMS.register("seebeck_generator",
            () -> new BlockItem(SEEBECK_GENERATOR_BLOCK.get(), new Item.Properties()));

    // --- Order/Chaos fluids: the "liquid form" of ORD/CHS condensed by the Entropy Condenser ---

    public static final DeferredHolder<FluidType, FluidType> ORDER_FLUID_TYPE = FLUID_TYPES.register("order_fluid_type",
            () -> new FluidType(FluidType.Properties.create()
                    .density(1000).viscosity(1000).lightLevel(6)
                    .descriptionId("fluid.temporalindustries.order_fluid")));

    public static final DeferredHolder<FluidType, FluidType> CHAOS_FLUID_TYPE = FLUID_TYPES.register("chaos_fluid_type",
            () -> new FluidType(FluidType.Properties.create()
                    .density(1000).viscosity(1000).lightLevel(6)
                    .descriptionId("fluid.temporalindustries.chaos_fluid")));

    public static final DeferredHolder<Fluid, FlowingFluid> ORDER_FLUID = FLUIDS.register("order_fluid",
            () -> new BaseFlowingFluid.Source(orderFluidProperties()));
    public static final DeferredHolder<Fluid, FlowingFluid> ORDER_FLUID_FLOWING = FLUIDS.register("order_fluid_flowing",
            () -> new BaseFlowingFluid.Flowing(orderFluidProperties()));

    public static final DeferredHolder<Fluid, FlowingFluid> CHAOS_FLUID = FLUIDS.register("chaos_fluid",
            () -> new BaseFlowingFluid.Source(chaosFluidProperties()));
    public static final DeferredHolder<Fluid, FlowingFluid> CHAOS_FLUID_FLOWING = FLUIDS.register("chaos_fluid_flowing",
            () -> new BaseFlowingFluid.Flowing(chaosFluidProperties()));

    public static final DeferredBlock<LiquidBlock> ORDER_FLUID_BLOCK = BLOCKS.register("order_fluid_block",
            () -> new LiquidBlock(ORDER_FLUID.get(), BlockBehaviour.Properties.of().mapColor(MapColor.SNOW).replaceable()
                    .noCollission().strength(100.0F).pushReaction(PushReaction.DESTROY).noLootTable()
                    .liquid().sound(SoundType.EMPTY)));

    public static final DeferredBlock<LiquidBlock> CHAOS_FLUID_BLOCK = BLOCKS.register("chaos_fluid_block",
            () -> new LiquidBlock(CHAOS_FLUID.get(), BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).replaceable()
                    .noCollission().strength(100.0F).pushReaction(PushReaction.DESTROY).noLootTable()
                    .liquid().sound(SoundType.EMPTY)));

    public static final DeferredItem<Item> ORDER_BUCKET_ITEM = ITEMS.register("order_bucket",
            () -> new BucketItem(ORDER_FLUID.get(), new Item.Properties().craftRemainder(net.minecraft.world.item.Items.BUCKET).stacksTo(1)));

    public static final DeferredItem<Item> CHAOS_BUCKET_ITEM = ITEMS.register("chaos_bucket",
            () -> new BucketItem(CHAOS_FLUID.get(), new Item.Properties().craftRemainder(net.minecraft.world.item.Items.BUCKET).stacksTo(1)));

    private static BaseFlowingFluid.Properties orderFluidProperties() {
        return new BaseFlowingFluid.Properties(ORDER_FLUID_TYPE, ORDER_FLUID, ORDER_FLUID_FLOWING)
                .block(ORDER_FLUID_BLOCK).bucket(ORDER_BUCKET_ITEM);
    }

    private static BaseFlowingFluid.Properties chaosFluidProperties() {
        return new BaseFlowingFluid.Properties(CHAOS_FLUID_TYPE, CHAOS_FLUID, CHAOS_FLUID_FLOWING)
                .block(CHAOS_FLUID_BLOCK).bucket(CHAOS_BUCKET_ITEM);
    }

    // --- Entropy Condenser ---

    public static final DeferredBlock<EntropyCondenser> ENTROPY_CONDENSER_BLOCK = BLOCKS.register("entropy_condenser",
            () -> new EntropyCondenser(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5F, 6.0F).sound(SoundType.METAL).requiresCorrectToolForDrops()));

    public static final DeferredItem<Item> ENTROPY_CONDENSER_ITEM = ITEMS.register("entropy_condenser",
            () -> new BlockItem(ENTROPY_CONDENSER_BLOCK.get(), new Item.Properties()));

    // --- Crude Entropy Condenser: unpowered lower tier that drains Cells instead of catching orbs ---

    public static final DeferredBlock<CrudeEntropyCondenser> CRUDE_ENTROPY_CONDENSER_BLOCK = BLOCKS.register("crude_entropy_condenser",
            () -> new CrudeEntropyCondenser(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.0F, 5.0F).sound(SoundType.METAL).requiresCorrectToolForDrops()));

    public static final DeferredItem<Item> CRUDE_ENTROPY_CONDENSER_ITEM = ITEMS.register("crude_entropy_condenser",
            () -> new BlockItem(CRUDE_ENTROPY_CONDENSER_BLOCK.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ChronovaultBlockEntity>> CHRONOVAULT_BLOCK_ENTITY = BLOCK_ENTITIES.register("chronovault",
            () -> BlockEntityType.Builder.of(ChronovaultBlockEntity::new, CHRONOVAULT_BLOCK.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ChronoProjectorBlockEntity>> CHRONO_PROJECTOR_BLOCK_ENTITY = BLOCK_ENTITIES.register("chrono_projector",
            () -> BlockEntityType.Builder.of(ChronoProjectorBlockEntity::new, ECHO_PROJECTOR_BLOCK.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ChronodialBlockEntity>> CHRONODIAL_BLOCK_ENTITY = BLOCK_ENTITIES.register("chronodial",
            () -> BlockEntityType.Builder.of(ChronodialBlockEntity::new, CHRONODIAL_BLOCK.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ChronosphereBlockEntity>> CHRONOSPHERE_BLOCK_ENTITY = BLOCK_ENTITIES.register("chronosphere",
            () -> BlockEntityType.Builder.of(ChronosphereBlockEntity::new, CHRONOSPHERE_BLOCK.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SchrodingersBoxBlockEntity>> SCHRODINGERS_BOX_BLOCK_ENTITY = BLOCK_ENTITIES.register("schrodingers_box",
            () -> BlockEntityType.Builder.of(SchrodingersBoxBlockEntity::new, SCHRODINGERS_BOX_BLOCK.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SeebeckGeneratorBlockEntity>> SEEBECK_GENERATOR_BLOCK_ENTITY = BLOCK_ENTITIES.register("seebeck_generator",
            () -> BlockEntityType.Builder.of(SeebeckGeneratorBlockEntity::new, SEEBECK_GENERATOR_BLOCK.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<EntropyCondenserBlockEntity>> ENTROPY_CONDENSER_BLOCK_ENTITY = BLOCK_ENTITIES.register("entropy_condenser",
            () -> BlockEntityType.Builder.of(EntropyCondenserBlockEntity::new, ENTROPY_CONDENSER_BLOCK.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CrudeEntropyCondenserBlockEntity>> CRUDE_ENTROPY_CONDENSER_BLOCK_ENTITY = BLOCK_ENTITIES.register("crude_entropy_condenser",
            () -> BlockEntityType.Builder.of(CrudeEntropyCondenserBlockEntity::new, CRUDE_ENTROPY_CONDENSER_BLOCK.get()).build(null));

    public static final DeferredHolder<MenuType<?>, MenuType<ChronovaultMenu>> CHRONOVAULT_MENU = MENUS.register("chronovault",
            () -> IMenuTypeExtension.create(ChronovaultMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<ChronosphereMenu>> CHRONOSPHERE_MENU = MENUS.register("chronosphere",
            () -> IMenuTypeExtension.create(ChronosphereMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<EntropyCondenserMenu>> ENTROPY_CONDENSER_MENU = MENUS.register("entropy_condenser",
            () -> IMenuTypeExtension.create(EntropyCondenserMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<CrudeEntropyCondenserMenu>> CRUDE_ENTROPY_CONDENSER_MENU = MENUS.register("crude_entropy_condenser",
            () -> IMenuTypeExtension.create(CrudeEntropyCondenserMenu::new));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TEMPORAL_INDUSTRIES_TAB = CREATIVE_MODE_TABS.register("temporal_industries",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.temporalindustries"))
                    .icon(() -> CHRONOVAULT_ITEM.toStack())
                    .displayItems((params, output) -> {
                        output.accept(CHRONOVAULT_ITEM);
                        output.accept(ECHO_PROJECTOR_ITEM);
                        output.accept(CHRONODIAL_ITEM);
                        output.accept(CHRONOSPHERE_ITEM);
                        output.accept(SCHRODINGERS_BOX_ITEM);
                        output.accept(SEEBECK_GENERATOR_ITEM);
                        output.accept(ENTROPY_CONDENSER_ITEM);
                        output.accept(CRUDE_ENTROPY_CONDENSER_ITEM);
                        output.accept(TEMPORAL_ANCHOR_ITEM);
                        output.accept(ECHO_RECORD_ITEM);
                        output.accept(PORTABLE_CHRONO_MARKER_ITEM);
                        output.accept(TEMPORAL_GLUE_ITEM);
                        output.accept(ENTROPY_GLASSES_ITEM);
                        output.accept(DUAL_ENTROPY_CELL_ITEM);
                        output.accept(ORDER_CELL_ITEM);
                        output.accept(CHAOS_CELL_ITEM);
                        output.accept(ORDER_BUCKET_ITEM);
                        output.accept(CHAOS_BUCKET_ITEM);
                    })
                    .build());

    public static void init(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        MENUS.register(modEventBus);
        ENTITY_TYPES.register(modEventBus);
        DATA_COMPONENTS.register(modEventBus);
        FLUID_TYPES.register(modEventBus);
        FLUIDS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        ARMOR_MATERIALS.register(modEventBus);
    }

    static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, CHRONOVAULT_BLOCK_ENTITY.get(),
                (be, side) -> be.getEnergyStorage());
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, CHRONO_PROJECTOR_BLOCK_ENTITY.get(),
                (be, side) -> be.getEnergyStorage());
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, CHRONO_PROJECTOR_BLOCK_ENTITY.get(),
                (be, side) -> be.getItemHandler());
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, CHRONODIAL_BLOCK_ENTITY.get(),
                (be, side) -> be.getEnergyStorage());
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, CHRONOSPHERE_BLOCK_ENTITY.get(),
                (be, side) -> be.getEnergyStorage());
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, SEEBECK_GENERATOR_BLOCK_ENTITY.get(),
                (be, side) -> be.getEnergyStorage());
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ENTROPY_CONDENSER_BLOCK_ENTITY.get(),
                (be, side) -> be.getEnergyStorage());
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, ENTROPY_CONDENSER_BLOCK_ENTITY.get(),
                (be, side) -> be.getFluidHandler());
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ENTROPY_CONDENSER_BLOCK_ENTITY.get(),
                (be, side) -> be.getItemHandler());
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, CRUDE_ENTROPY_CONDENSER_BLOCK_ENTITY.get(),
                (be, side) -> be.getItemHandler());
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, CRUDE_ENTROPY_CONDENSER_BLOCK_ENTITY.get(),
                (be, side) -> be.getFluidHandler());
    }

}
