package io.github.west3436.expedition.worldgen;

import com.mojang.serialization.MapCodec;
import io.github.west3436.expedition.ExpeditionMod;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The world generation types the mod registers, which dimension files name. */
public final class ExpeditionWorldgen {
    private static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, ExpeditionMod.MOD_ID);

    /** {@code expedition:planet}: {@link PlanetChunkGenerator}. */
    public static final Supplier<MapCodec<PlanetChunkGenerator>> PLANET =
            CHUNK_GENERATORS.register("planet", () -> PlanetChunkGenerator.CODEC);

    private ExpeditionWorldgen() {}

    /**
     * Registers the types.
     *
     * @param modBus the mod's event bus
     */
    public static void register(IEventBus modBus) {
        CHUNK_GENERATORS.register(modBus);
    }
}
