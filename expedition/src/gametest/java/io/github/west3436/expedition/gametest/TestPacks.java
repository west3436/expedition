package io.github.west3436.expedition.gametest;

import io.github.west3436.expedition.ExpeditionMod;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddPackFindersEvent;

/**
 * The datapacks the GameTests run with, always on, from {@code datapacks/} in the GameTest source
 * set. Like the rest of the source set, they never reach the mod jar. The event is a mod-bus one,
 * so FML subscribes the class to the mod bus.
 *
 * <ul>
 *   <li>{@code immovable_test} puts reinforced deepslate in {@code expedition:immovable}, which the
 *       mod itself ships empty.
 *   <li>{@code planet_test} holds the test planet, {@code expedition_test:kepler}: a datapack-only
 *       dimension of that id ({@code data/expedition_test/dimension/kepler.json}) and its planet
 *       file ({@code data/expedition_test/expedition/planet/kepler.json}). {@link TestDimensions}
 *       makes the dimension a level on the GameTest server. It also holds {@code
 *       expedition_test:generated}, a planet whose dimension is made by {@code expedition:planet},
 *       with its biome and planet file. The biome is in vanilla's igloo and standard ruined-portal
 *       biome tags, so {@link PlanetStructureTests} has structures to filter there.
 * </ul>
 */
@EventBusSubscriber(modid = ExpeditionMod.MOD_ID)
public final class TestPacks {
    private TestPacks() {}

    /**
     * Adds the packs.
     *
     * @param event the event
     */
    @SubscribeEvent
    public static void onAddPackFinders(AddPackFindersEvent event) {
        add(event, "immovable_test", "Expedition GameTests: immovable blocks");
        add(event, "planet_test", "Expedition GameTests: the test planet");
    }

    private static void add(AddPackFindersEvent event, String pack, String name) {
        event.addPackFinders(
                ResourceLocation.fromNamespaceAndPath(ExpeditionMod.MOD_ID, "datapacks/" + pack),
                PackType.SERVER_DATA,
                Component.literal(name),
                PackSource.BUILT_IN,
                true,
                Pack.Position.TOP);
    }
}
