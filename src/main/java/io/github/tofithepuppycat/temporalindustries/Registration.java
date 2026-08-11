package io.github.tofithepuppycat.temporalindustries;

import static io.github.tofithepuppycat.temporalindustries.TemporalIndustries.MODID;
import io.github.tofithepuppycat.temporalindustries.block.ChronoProjector;
import io.github.tofithepuppycat.temporalindustries.block.TimeMachine;
import io.github.tofithepuppycat.temporalindustries.block.entity.ChronoProjectorBlockEntity;
import io.github.tofithepuppycat.temporalindustries.block.entity.TimeMachineBlockEntity;
import io.github.tofithepuppycat.temporalindustries.item.ChronoRecorderItem;
import io.github.tofithepuppycat.temporalindustries.item.TemporalAnchorItem;
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

    public static final DeferredItem<Item> CHRONO_RECORDER_ITEM = ITEMS.register("chrono_recorder",
            () -> new ChronoRecorderItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TimeMachineBlockEntity>> TIME_MACHINE_BLOCK_ENTITY = BLOCK_ENTITIES.register("time_machine",
            () -> BlockEntityType.Builder.of(TimeMachineBlockEntity::new, TIME_MACHINE_BLOCK.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ChronoProjectorBlockEntity>> CHRONO_PROJECTOR_BLOCK_ENTITY = BLOCK_ENTITIES.register("chrono_projector",
            () -> BlockEntityType.Builder.of(ChronoProjectorBlockEntity::new, CHRONO_PROJECTOR_BLOCK.get()).build(null));

    public static final DeferredHolder<MenuType<?>, MenuType<TimeMachineMenu>> TIME_MACHINE_MENU = MENUS.register("time_machine",
            () -> IMenuTypeExtension.create(TimeMachineMenu::new));

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
            event.accept(CHRONO_RECORDER_ITEM);
        }
        if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) {
            event.accept(CHRONO_PROJECTOR_ITEM);
        }
    }

    static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, TIME_MACHINE_BLOCK_ENTITY.get(),
                (be, side) -> be.getEnergyStorage());
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, CHRONO_PROJECTOR_BLOCK_ENTITY.get(),
                (be, side) -> be.getEnergyStorage());
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, CHRONO_PROJECTOR_BLOCK_ENTITY.get(),
                (be, side) -> be.getItemHandler());
    }

}
