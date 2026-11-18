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
 *   <li>{@code planet_test} adds {@code expedition:planet_test}, a dimension made by {@code
 *       expedition:planet}. A GameTest server builds its world from the {@code minecraft:flat} world
 *       preset and reads no {@code dimension/} files, so the pack overrides that preset: the
 *       Overworld, the Nether and the End as vanilla has them, and the planet beside them.
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
        event.addPackFinders(
                ResourceLocation.fromNamespaceAndPath(ExpeditionMod.MOD_ID, "datapacks/immovable_test"),
                PackType.SERVER_DATA,
                Component.literal("Expedition GameTests: immovable blocks"),
                PackSource.BUILT_IN,
                true,
                Pack.Position.TOP);
        event.addPackFinders(
                ResourceLocation.fromNamespaceAndPath(ExpeditionMod.MOD_ID, "datapacks/planet_test"),
                PackType.SERVER_DATA,
                Component.literal("Expedition GameTests: a planet dimension"),
                PackSource.BUILT_IN,
                true,
                Pack.Position.TOP);
    }
}
