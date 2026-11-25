package io.github.west3436.expedition.gametest;

import com.mojang.serialization.Lifecycle;
import io.github.west3436.expedition.ExpeditionMod;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A world with Expedition's dimensions isn't "experimental": vanilla asks for a backup before it
 * opens a world whose baked dimensions aren't stable, and the hand test of 0.1.0 found that every
 * Expedition world asked. These bake the server's own dimensions as a world's settings would be, and
 * read the lifecycle the warning reads.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StableDimensionTests {
    private StableDimensionTests() {}

    /** The three vanilla dimensions with space, transit and the Moon are stable. */
    @GameTest(template = "empty")
    public static void expeditions_dimensions_are_stable(GameTestHelper h) {
        Map<ResourceKey<LevelStem>, LevelStem> stems = vanillaStems(h);
        for (String path : new String[] {"space", "transit", "moon"}) {
            ResourceKey<LevelStem> key = stem(ExpeditionMod.MOD_ID, path);
            stems.put(key, live(h, key));
        }
        Lifecycle lifecycle = bake(stems);
        h.assertTrue(
                lifecycle == Lifecycle.stable(),
                "a world with Expedition's dimensions is " + lifecycle + ", which asks for a backup");
        h.succeed();
    }

    /**
     * A planet pack's dimension that uses Expedition's planet generator is stable too, in its own
     * namespace: expedition_test:generated.
     */
    @GameTest(template = "empty")
    public static void a_planet_packs_dimension_is_stable(GameTestHelper h) {
        Map<ResourceKey<LevelStem>, LevelStem> stems = vanillaStems(h);
        ResourceKey<LevelStem> generated = stem(TestDimensions.NAMESPACE, "generated");
        stems.put(generated, live(h, generated));
        Lifecycle lifecycle = bake(stems);
        h.assertTrue(lifecycle == Lifecycle.stable(), "a world with a planet pack's dimension is " + lifecycle);
        h.succeed();
    }

    /**
     * Any other extra dimension still makes a world experimental, as in vanilla: here a second
     * Overworld under another name.
     */
    @GameTest(template = "empty")
    public static void another_extra_dimension_is_still_experimental(GameTestHelper h) {
        Map<ResourceKey<LevelStem>, LevelStem> stems = vanillaStems(h);
        LevelStem overworld = stems.get(LevelStem.OVERWORLD);
        stems.put(stem("expedition_test", "second_overworld"), new LevelStem(overworld.type(), overworld.generator()));
        Lifecycle lifecycle = bake(stems);
        h.assertTrue(lifecycle != Lifecycle.stable(), "a world with an extra vanilla-style dimension is stable");
        h.succeed();
    }

    /**
     * A dimension as the server runs it. The GameTest server's own dimension registry holds only
     * vanilla's, so Expedition's are read from their levels.
     */
    private static LevelStem live(GameTestHelper h, ResourceKey<LevelStem> key) {
        ServerLevel level = h.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION, key.location()));
        if (level == null) {
            throw new GameTestAssertException(key.location() + " isn't a level");
        }
        return new LevelStem(
                level.dimensionTypeRegistration(), level.getChunkSource().getGenerator());
    }

    private static Registry<LevelStem> stems(GameTestHelper h) {
        return h.getLevel().getServer().registryAccess().registryOrThrow(Registries.LEVEL_STEM);
    }

    private static Map<ResourceKey<LevelStem>, LevelStem> vanillaStems(GameTestHelper h) {
        Map<ResourceKey<LevelStem>, LevelStem> stems = new LinkedHashMap<>();
        for (ResourceKey<LevelStem> key : new ResourceKey[] {LevelStem.OVERWORLD, LevelStem.NETHER, LevelStem.END}) {
            stems.put(key, stems(h).getOrThrow(key));
        }
        return stems;
    }

    private static ResourceKey<LevelStem> stem(String namespace, String path) {
        return ResourceKey.create(Registries.LEVEL_STEM, ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    /** Bakes the dimensions with no others in the registry, as a new world's settings are. */
    private static Lifecycle bake(Map<ResourceKey<LevelStem>, LevelStem> stems) {
        Registry<LevelStem> none = new MappedRegistry<>(Registries.LEVEL_STEM, Lifecycle.stable()).freeze();
        return new WorldDimensions(stems).bake(none).lifecycle();
    }
}
