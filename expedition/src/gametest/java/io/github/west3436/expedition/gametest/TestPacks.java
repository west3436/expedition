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
 * The datapack the GameTests run with, always on, from {@code datapacks/immovable_test} in the
 * GameTest source set: it puts reinforced deepslate in {@code expedition:immovable}, which the mod
 * itself ships empty. Like the rest of the source set, it never reaches the mod jar. The event is a
 * mod-bus one, so FML subscribes the class to the mod bus.
 */
@EventBusSubscriber(modid = ExpeditionMod.MOD_ID)
public final class TestPacks {
    private TestPacks() {}

    /**
     * Adds the pack.
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
    }
}
