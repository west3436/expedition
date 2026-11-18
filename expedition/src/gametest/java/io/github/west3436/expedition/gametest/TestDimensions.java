package io.github.west3436.expedition.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import io.github.west3436.expedition.ExpeditionMod;
import java.io.IOException;
import java.io.Reader;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.WorldData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

/**
 * Makes the test datapacks' dimensions, and the mod's own, levels on the GameTest server.
 *
 * <p>A real server makes a level of every dimension its datapacks hold, but vanilla's GameTest
 * server builds its world from the flat preset alone and drops datapack dimensions. So before the
 * tests start, this reads each {@code data/expedition_test/dimension/*.json}, and each of the mod's
 * own {@code data/expedition/dimension/*.json} such as Space, the way a real server
 * would and adds a level for it, as the server's own {@code createLevels} adds the Nether: with the
 * Overworld's seed, its level data and random sequences, and a world border that follows the
 * Overworld's. Tests then reach it with {@code server.getLevel}, as any other level.
 *
 * <p>Only the GameTest server is touched: a dev client or server makes these levels itself.
 */
@EventBusSubscriber(modid = ExpeditionMod.MOD_ID)
public final class TestDimensions {
    /** The namespace of the test datapacks' dimensions. */
    public static final String NAMESPACE = "expedition_test";

    /** The test planet's dimension, from the {@code planet_test} datapack. */
    public static final ResourceKey<Level> KEPLER =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(NAMESPACE, "kepler"));

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final FileToIdConverter DIMENSIONS = FileToIdConverter.json("dimension");

    private TestDimensions() {}

    /**
     * Adds the levels, after the server's own and before the tests start.
     *
     * @param event the event
     */
    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        if (!(server instanceof GameTestServer)) {
            return;
        }
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        Map<ResourceLocation, Resource> files = new TreeMap<>();
        for (String namespace : List.of(ExpeditionMod.MOD_ID, NAMESPACE)) {
            files.putAll(DIMENSIONS.listMatchingResourcesFromNamespace(server.getResourceManager(), namespace));
        }
        for (Map.Entry<ResourceLocation, Resource> file : files.entrySet()) {
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, DIMENSIONS.fileToId(file.getKey()));
            if (server.getLevel(key) != null) {
                continue;
            }
            JsonElement json;
            try (Reader reader = file.getValue().openAsReader()) {
                json = JsonParser.parseReader(reader);
            } catch (IOException e) {
                throw new IllegalStateException("Couldn't read the test dimension " + file.getKey(), e);
            }
            LevelStem stem = LevelStem.CODEC
                    .parse(ops, json)
                    .getOrThrow(why -> new IllegalStateException("The test dimension " + key.location() + ": " + why));
            add(server, key, stem);
            LOGGER.info("Added the test dimension {} to the GameTest server", key.location());
        }
    }

    @SuppressWarnings("deprecation") // forgeGetWorldMap: how the server's own levels map is reached
    private static void add(MinecraftServer server, ResourceKey<Level> key, LevelStem stem) {
        WorldData data = server.getWorldData();
        ServerLevel overworld = server.overworld();
        LevelStorageSource.LevelStorageAccess storage =
                ObfuscationReflectionHelper.getPrivateValue(MinecraftServer.class, server, "storageSource");
        ServerLevel level = new ServerLevel(
                server,
                Util.backgroundExecutor(),
                storage,
                new DerivedLevelData(data, data.overworldData()),
                key,
                stem,
                NO_PROGRESS,
                data.isDebugWorld(),
                BiomeManager.obfuscateSeed(data.worldGenOptions().seed()),
                List.of(),
                false,
                overworld.getRandomSequences());
        overworld
                .getWorldBorder()
                .addListener(new BorderChangeListener.DelegateBorderChangeListener(level.getWorldBorder()));
        server.forgeGetWorldMap().put(key, level);
        server.markWorldsDirty();
        NeoForge.EVENT_BUS.post(new LevelEvent.Load(level));
    }

    private static final ChunkProgressListener NO_PROGRESS = new ChunkProgressListener() {
        @Override
        public void updateSpawnPos(ChunkPos center) {}

        @Override
        public void onStatusChange(ChunkPos chunkPos, @Nullable ChunkStatus chunkStatus) {}

        @Override
        public void start() {}

        @Override
        public void stop() {}
    };
}
