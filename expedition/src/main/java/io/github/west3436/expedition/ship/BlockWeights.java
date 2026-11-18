package io.github.west3436.expedition.ship;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import io.github.west3436.expedition.ExpeditionMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;

/**
 * What each block weighs toward a ship's mass: the {@code expedition:block_weight} data map on
 * blocks.
 *
 * <p><b>The rules.</b> Every block that isn't air counts at its weight, whatever its shape: slabs,
 * fences, torches and plants included. A block the data map doesn't list weighs {@link #DEFAULT},
 * 1. Fluid blocks ({@link LiquidBlock}: water, lava and modded fluids) weigh 0 whatever the map says,
 * since a ship's mass is its solid blocks only, and a waterlogged block weighs what the block alone
 * does. Cargo and the contents of tanks add nothing.
 *
 * <p><b>The file.</b> A datapack sets weights in {@code data/<namespace>/data_maps/block/block_weight.json},
 * by block id or by block tag, with a weight of 0 or more:
 *
 * <pre>{@code
 * {
 *   "values": {
 *     "#minecraft:planks": 0.5,
 *     "minecraft:iron_block": 4
 *   }
 * }
 * }</pre>
 *
 * <p>Later entries win: within a file, an entry below overrides one above it for the blocks both
 * name, and a pack loaded later overrides one loaded earlier. So a general tag goes first and the
 * exceptions after it. A pack can drop Expedition's values with {@code "replace": true}, or take
 * blocks out with {@code "remove"}, as with any NeoForge data map.
 *
 * <p><b>The scale.</b> Expedition's own file, {@code data/expedition/data_maps/block/block_weight.json},
 * puts stone, dirt and most solid blocks at 1, the default. Wood, wool, glass and plants are lighter;
 * metal and ore blocks are heavier, with iron at 4, gold at 8 and netherite at 10; obsidian is 3.
 * Partial blocks keep it simple: a slab is half of 1, stairs and walls three quarters, a torch a
 * tenth.
 */
public final class BlockWeights {
    /** What a block weighs if the data map doesn't list it. */
    public static final double DEFAULT = 1.0;

    /** The heaviest weight a file may give, which keeps a ship's mass finite. */
    public static final double MAX = 1_000_000.0;

    /** A weight: a number from 0 to {@link #MAX}. */
    public static final Codec<Double> WEIGHT = Codec.DOUBLE.validate(weight -> weight >= 0 && weight <= MAX
            ? DataResult.success(weight)
            : DataResult.error(() -> "a block's weight must be 0 to " + MAX + ", not " + weight));

    /** {@code expedition:block_weight}: what each block weighs. Not sent to clients. */
    public static final DataMapType<Block, Double> BLOCK_WEIGHT = DataMapType.builder(
                    ResourceLocation.fromNamespaceAndPath(ExpeditionMod.MOD_ID, "block_weight"),
                    Registries.BLOCK,
                    WEIGHT)
            .build();

    private BlockWeights() {}

    /**
     * Registers the data map.
     *
     * @param modBus the mod's event bus
     */
    public static void register(IEventBus modBus) {
        modBus.addListener(RegisterDataMapTypesEvent.class, event -> event.register(BLOCK_WEIGHT));
    }

    /**
     * What a block weighs toward a ship's mass.
     *
     * <p>Thread: any, once datapacks have loaded on this side. On a client, which isn't sent the data
     * map, every block but air and fluids weighs {@link #DEFAULT}.
     *
     * @param state the block
     * @return 0 for air and fluid blocks; else its weight in the data map, or {@link #DEFAULT}
     */
    public static double weight(BlockState state) {
        if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
            return 0;
        }
        Double weight = state.getBlockHolder().getData(BLOCK_WEIGHT);
        return weight != null ? weight : DEFAULT;
    }
}
