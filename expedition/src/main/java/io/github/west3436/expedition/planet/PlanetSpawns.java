package io.github.west3436.expedition.planet;

import io.github.west3436.expedition.api.planet.Planet;
import io.github.west3436.expedition.api.planet.PlanetMob;
import io.github.west3436.expedition.api.planet.PlanetMobs;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.random.WeightedRandomList;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.biome.MobSpawnSettings.SpawnerData;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * What spawns on each planet (#18): its {@code mobs} table, by mob category, added to what the
 * dimension's biomes spawn, or in place of it.
 *
 * <ul>
 *   <li><b>Natural spawning</b> asks NeoForge's {@link LevelEvent.PotentialSpawns} for each
 *       category at each spot. In a planet's dimension, a category the file lists gets the
 *       planet's mobs added to the biome's, or, with {@code replace_existing}, the planet's alone.
 *       A category the file leaves out keeps the biome's, so {@code "ambient": []} is how a file
 *       says no bats.
 *   <li><b>A new chunk's creatures</b>, which vanilla spawns from the biome's list directly, without
 *       that event, get the same {@code creature} list ({@link #creaturesForNewChunk}, from a mixin
 *       on {@code NaturalSpawner}). How often a new chunk has any stays the biome's.
 * </ul>
 *
 * <p>The lists are built once for each load of the planets, and the same entries are given every
 * time, as vanilla needs: it picks an entry, then checks the spot's list still holds it.
 *
 * <p>Any thread: natural spawning runs on the server thread, but a new chunk's creatures spawn
 * where the chunk generates. The planets are an immutable snapshot, replaced whole by each load.
 */
public final class PlanetSpawns {
    /** One planet's lists. */
    private record Lists(
            boolean replaceExisting, Map<MobCategory, List<SpawnerData>> spawns, Set<EntityType<?>> natives) {}

    /** The lists of one load of the planets, by dimension. */
    private record Snapshot(PlanetLoader.Result source, Map<ResourceKey<Level>, Lists> byDimension) {}

    private static volatile Snapshot snapshot = new Snapshot(PlanetLoader.Result.EMPTY, Map.of());

    private PlanetSpawns() {}

    /**
     * Listens on the game bus.
     *
     * @param gameBus NeoForge's game event bus
     */
    static void register(IEventBus gameBus) {
        gameBus.addListener(PlanetSpawns::onPotentialSpawns);
    }

    /**
     * Whether an entity type is one of a planet's own mobs: listed in any category of the {@code
     * mobs} table of the planet whose dimension this is. Any thread.
     *
     * @param dimension the dimension
     * @param type the entity type
     * @return whether it is native there
     */
    public static boolean isNative(ResourceKey<Level> dimension, EntityType<?> type) {
        Lists lists = current().byDimension().get(dimension);
        return lists != null && lists.natives().contains(type);
    }

    /**
     * What a category spawns at a spot in a dimension, given what its biome spawns there. Any
     * thread.
     *
     * @param dimension the dimension
     * @param category the mob category
     * @param biome the biome's list there
     * @return the list the planet makes of it, or the biome's own list where the dimension isn't a
     *     planet or its file leaves the category out
     */
    public static List<SpawnerData> spawns(
            ResourceKey<Level> dimension, MobCategory category, List<SpawnerData> biome) {
        Lists lists = current().byDimension().get(dimension);
        List<SpawnerData> planet = lists == null ? null : lists.spawns().get(category);
        if (planet == null) {
            return biome;
        }
        if (lists.replaceExisting()) {
            return planet;
        }
        List<SpawnerData> both = new ArrayList<>(biome);
        both.addAll(planet);
        return Collections.unmodifiableList(both);
    }

    /**
     * The creatures a new chunk spawns as it generates, given its biome's. Called from the mixin on
     * {@code NaturalSpawner.spawnMobsForChunkGeneration}, on the thread the chunk generates on.
     *
     * @param level the level the chunk is generating in
     * @param biome the biome's {@code creature} list
     * @return the planet's list, or the biome's own where the planet doesn't change it
     */
    public static WeightedRandomList<SpawnerData> creaturesForNewChunk(
            ServerLevelAccessor level, WeightedRandomList<SpawnerData> biome) {
        List<SpawnerData> list = spawns(level.getLevel().dimension(), MobCategory.CREATURE, biome.unwrap());
        return list == biome.unwrap() ? biome : WeightedRandomList.create(list);
    }

    private static void onPotentialSpawns(LevelEvent.PotentialSpawns event) {
        ResourceKey<Level> dimension = dimension(event.getLevel());
        if (dimension == null) {
            return;
        }
        List<SpawnerData> biome = List.copyOf(event.getSpawnerDataList());
        List<SpawnerData> list = spawns(dimension, event.getMobCategory(), biome);
        if (list == biome) {
            return;
        }
        biome.forEach(event::removeSpawnerData);
        list.forEach(event::addSpawnerData);
    }

    @Nullable
    private static ResourceKey<Level> dimension(LevelAccessor level) {
        if (level instanceof Level l) {
            return l.dimension();
        }
        if (level instanceof ServerLevelAccessor s) {
            return s.getLevel().dimension();
        }
        return null;
    }

    /** The lists for the server's planets as they are now, built again after each load. */
    private static Snapshot current() {
        PlanetLoader.Result planets = PlanetRegistry.server();
        Snapshot now = snapshot;
        if (now.source() == planets) {
            return now;
        }
        synchronized (PlanetSpawns.class) {
            now = snapshot;
            if (now.source() != planets) {
                now = build(planets);
                snapshot = now;
            }
            return now;
        }
    }

    private static Snapshot build(PlanetLoader.Result planets) {
        Map<ResourceKey<Level>, Lists> byDimension = new HashMap<>();
        for (Planet planet : planets.planets().values()) {
            PlanetMobs mobs = planet.mobs();
            if (mobs.spawns().isEmpty()) {
                continue;
            }
            Map<MobCategory, List<SpawnerData>> spawns = new EnumMap<>(MobCategory.class);
            Set<EntityType<?>> natives = new HashSet<>();
            mobs.spawns().forEach((category, list) -> {
                List<SpawnerData> entries = new ArrayList<>();
                for (PlanetMob mob : list) {
                    entry(mob).ifPresent(entry -> {
                        entries.add(entry);
                        natives.add(entry.type);
                    });
                }
                spawns.put(category, List.copyOf(entries));
            });
            byDimension.put(planet.dimension(), new Lists(mobs.replaceExisting(), spawns, Set.copyOf(natives)));
        }
        return new Snapshot(planets, Map.copyOf(byDimension));
    }

    /**
     * A mob's spawn entry. None for a type that isn't registered, which the planet's load has
     * already reported, or one of the {@code misc} category, which can't spawn naturally and
     * which a spawn entry would turn into a pig.
     */
    private static Optional<SpawnerData> entry(PlanetMob mob) {
        return BuiltInRegistries.ENTITY_TYPE
                .getOptional(mob.type())
                .filter(type -> type.getCategory() != MobCategory.MISC)
                .map(type -> new SpawnerData(type, mob.weight(), mob.minGroup(), mob.maxGroup()));
    }
}
