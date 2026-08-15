package io.github.tofithepuppycat.temporalindustries;

import static io.github.tofithepuppycat.temporalindustries.TemporalIndustries.MODID;
import io.github.tofithepuppycat.temporalindustries.block.ChronoProjector;
import io.github.tofithepuppycat.temporalindustries.block.Chronodial;
import io.github.tofithepuppycat.temporalindustries.block.Chronosphere;
import io.github.tofithepuppycat.temporalindustries.block.TimeMachine;
import io.github.tofithepuppycat.temporalindustries.block.entity.ChronoProjectorBlockEntity;
import io.github.tofithepuppycat.temporalindustries.block.entity.ChronodialBlockEntity;
import io.github.tofithepuppycat.temporalindustries.block.entity.ChronosphereBlockEntity;
import io.github.tofithepuppycat.temporalindustries.block.entity.TimeMachineBlockEntity;
import io.github.tofithepuppycat.temporalindustries.item.ChronoRecordItem;
import io.github.tofithepuppycat.temporalindustries.item.PortableChronoMarkerItem;
import io.github.tofithepuppycat.temporalindustries.item.TemporalAnchorItem;
import io.github.tofithepuppycat.temporalindustries.item.TemporalGlueItem;
import io.github.tofithepuppycat.temporalindustries.menu.ChronosphereMenu;
import io.github.tofithepuppycat.temporalindustries.menu.TimeMachineMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@SuppressWarnings("null")
public class Registration {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MODID);

    public static final DeferredBlock<TimeMachine> TIME_MACHINE_BLOCK = BLOCKS.register("time_machine",
            () -> new TimeMachine(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5F, 6.0F).sound(SoundType.METAL).requiresCorrectToolForDrops()));

    public static final DeferredItem<Item> TIME_MACHINE_ITEM = ITEMS.register("time_machine",
            () -> new BlockItem(TIME_MACHINE_BLOCK.get(), new Item.Properties()));

    public static final DeferredItem<Item> TEMPORAL_ANCHOR_ITEM = ITEMS.register("temporal_anchor",
            () -> new TemporalAnchorItem(new Item.Properties().stacksTo(1)));

    public static final DeferredBlock<ChronoProjector> CHRONO_PROJECTOR_BLOCK = BLOCKS.register("chrono_projector",
            () -> new ChronoProjector(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_CYAN).strength(3.0F, 6.0F).sound(SoundType.AMETHYST).requiresCorrectToolForDrops().lightLevel(state -> 3)));

    public static final DeferredItem<Item> CHRONO_PROJECTOR_ITEM = ITEMS.register("chrono_projector",
            () -> new BlockItem(CHRONO_PROJECTOR_BLOCK.get(), new Item.Properties()));

    public static final DeferredItem<Item> CHRONO_RECORD_ITEM = ITEMS.register("chrono_record",
            () -> new ChronoRecordItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<Item> PORTABLE_CHRONO_MARKER_ITEM = ITEMS.register("portable_chrono_marker",
            () -> new PortableChronoMarkerItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<Item> TEMPORAL_GLUE_ITEM = ITEMS.register("temporal_glue",
            () -> new TemporalGlueItem(new Item.Properties().durability(20)));

    public static final DeferredBlock<Chronodial> CHRONODIAL_BLOCK = BLOCKS.register("chronodial",
            () -> new Chronodial(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(2.5F, 6.0F).sound(SoundType.AMETHYST).requiresCorrectToolForDrops()));

    public static final DeferredItem<Item> CHRONODIAL_ITEM = ITEMS.register("chronodial",
            () -> new BlockItem(CHRONODIAL_BLOCK.get(), new Item.Properties()));

    public static final DeferredBlock<Chronosphere> CHRONOSPHERE_BLOCK = BLOCKS.register("chronosphere",
            () -> new Chronosphere(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE).strength(4.5F, 8.0F).sound(SoundType.AMETHYST).requiresCorrectToolForDrops().lightLevel(state -> 5)));

    public static final DeferredItem<Item> CHRONOSPHERE_ITEM = ITEMS.register("chronosphere",
            () -> new BlockItem(CHRONOSPHERE_BLOCK.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TimeMachineBlockEntity>> TIME_MACHINE_BLOCK_ENTITY = BLOCK_ENTITIES.register("time_machine",
            () -> BlockEntityType.Builder.of(TimeMachineBlockEntity::new, TIME_MACHINE_BLOCK.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ChronoProjectorBlockEntity>> CHRONO_PROJECTOR_BLOCK_ENTITY = BLOCK_ENTITIES.register("chrono_projector",
            () -> BlockEntityType.Builder.of(ChronoProjectorBlockEntity::new, CHRONO_PROJECTOR_BLOCK.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ChronodialBlockEntity>> CHRONODIAL_BLOCK_ENTITY = BLOCK_ENTITIES.register("chronodial",
            () -> BlockEntityType.Builder.of(ChronodialBlockEntity::new, CHRONODIAL_BLOCK.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ChronosphereBlockEntity>> CHRONOSPHERE_BLOCK_ENTITY = BLOCK_ENTITIES.register("chronosphere",
            () -> BlockEntityType.Builder.of(ChronosphereBlockEntity::new, CHRONOSPHERE_BLOCK.get()).build(null));

    public static final DeferredHolder<MenuType<?>, MenuType<TimeMachineMenu>> TIME_MACHINE_MENU = MENUS.register("time_machine",
            () -> IMenuTypeExtension.create(TimeMachineMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<ChronosphereMenu>> CHRONOSPHERE_MENU = MENUS.register("chronosphere",
            () -> IMenuTypeExtension.create(ChronosphereMenu::new));

    public static void init(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        MENUS.register(modEventBus);
    }

    static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) {
            event.accept(TIME_MACHINE_ITEM);
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(TEMPORAL_ANCHOR_ITEM);
            event.accept(CHRONO_RECORD_ITEM);
            event.accept(PORTABLE_CHRONO_MARKER_ITEM);
            event.accept(TEMPORAL_GLUE_ITEM);
        }
        if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) {
            event.accept(CHRONO_PROJECTOR_ITEM);
            event.accept(CHRONODIAL_ITEM);
            event.accept(CHRONOSPHERE_ITEM);
        }
    }

    static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, TIME_MACHINE_BLOCK_ENTITY.get(),
                (be, side) -> be.getEnergyStorage());
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, CHRONO_PROJECTOR_BLOCK_ENTITY.get(),
                (be, side) -> be.getEnergyStorage());
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, CHRONO_PROJECTOR_BLOCK_ENTITY.get(),
                (be, side) -> be.getItemHandler());
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, CHRONODIAL_BLOCK_ENTITY.get(),
                (be, side) -> be.getEnergyStorage());
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, CHRONOSPHERE_BLOCK_ENTITY.get(),
                (be, side) -> be.getEnergyStorage());
    }

}
