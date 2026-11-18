package io.github.west3436.expedition.registry;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.ship.ShuttleComputerBlock;
import io.github.west3436.expedition.ship.ShuttleComputerBlockEntity;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The mod's blocks, their items and their block entities. */
public final class ExpeditionBlocks {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ExpeditionMod.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ExpeditionMod.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ExpeditionMod.MOD_ID);

    /** {@code expedition:shuttle_computer}: a ship's brain, which holds its name, owner and box. */
    public static final DeferredBlock<ShuttleComputerBlock> SHUTTLE_COMPUTER = BLOCKS.register(
            "shuttle_computer",
            () -> new ShuttleComputerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(3.5F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.METAL)
                    .lightLevel(state -> 7)));

    /** The Shuttle Computer's item. */
    public static final DeferredItem<BlockItem> SHUTTLE_COMPUTER_ITEM =
            ITEMS.registerSimpleBlockItem(SHUTTLE_COMPUTER, new Item.Properties().stacksTo(1));

    /** The Shuttle Computer's block entity. */
    @SuppressWarnings("DataFlowIssue") // The type's data fixer is null: the mod has none.
    public static final Supplier<BlockEntityType<ShuttleComputerBlockEntity>> SHUTTLE_COMPUTER_ENTITY =
            BLOCK_ENTITY_TYPES.register(
                    "shuttle_computer",
                    () -> BlockEntityType.Builder.of(ShuttleComputerBlockEntity::new, SHUTTLE_COMPUTER.get())
                            .build(null));

    private ExpeditionBlocks() {}

    /**
     * Registers the blocks, items and block entities, and puts the items in a creative tab.
     *
     * @param modBus the mod's event bus
     */
    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITY_TYPES.register(modBus);
        modBus.addListener(ExpeditionBlocks::onBuildTabs);
    }

    private static void onBuildTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(SHUTTLE_COMPUTER_ITEM);
        }
    }
}
