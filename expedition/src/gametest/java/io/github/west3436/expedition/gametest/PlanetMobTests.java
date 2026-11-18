package io.github.west3436.expedition.gametest;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.api.planet.Planet;
import io.github.west3436.expedition.api.planet.PlanetMobs;
import io.github.west3436.expedition.planet.NativeAir;
import io.github.west3436.expedition.planet.PlanetAir;
import io.github.west3436.expedition.planet.PlanetLoader;
import io.github.west3436.expedition.planet.PlanetRegistry;
import io.github.west3436.expedition.planet.PlanetSpawns;
import io.github.west3436.zas.api.atmosphere.AtmosphereState;
import io.github.west3436.zas.api.atmosphere.Atmospheres;
import io.github.west3436.zas.api.event.HazardTickEvent;
import io.github.west3436.zas.api.life.Hazard;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedRandomList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings.SpawnerData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A planet's mobs (#18). The test planet {@code expedition_test:kepler} replaces its biome's
 * spawns ({@code minecraft:snowy_plains}) with strays as its monsters and goats as its creatures,
 * and lists nothing else, so its other categories stay the biome's. Its air (#15) is 8.5 kPa of
 * methane and nitrogen, at 150 to 210 K: no O2, too thin and too cold for anything from the
 * Overworld.
 *
 * <p>These use ZAS³'s simulation only (its hazards, breathing and spawn check are in it, not in
 * its content), so they pass with {@code -Pcontent=off}.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PlanetMobTests {
    private static final long NOON = 6000;

    private PlanetMobTests() {}

    /**
     * Natural spawning on kepler, as {@code NaturalSpawner} asks for it: the biome's list, then
     * NeoForge's potential-spawns event. Kepler's creatures are its goats and not the biome's
     * rabbits, its monsters its strays, and the categories its file leaves out the biome's.
     */
    @GameTest(template = "empty", batch = "planet_mobs")
    public static void kepler_spawns_its_listed_mobs(GameTestHelper h) {
        ServerLevel kepler = kepler(h);
        BlockPos pos = new BlockPos(8, 64, 8);

        List<SpawnerData> biomeCreatures = biomeList(kepler, MobCategory.CREATURE, pos);
        h.assertTrue(
                types(biomeCreatures).contains(EntityType.RABBIT),
                "the biome's creatures, to be replaced, are " + types(biomeCreatures));
        List<SpawnerData> creatures = spawns(kepler, MobCategory.CREATURE, pos);
        h.assertValueEqual(types(creatures), List.of(EntityType.GOAT), "kepler's creatures");
        SpawnerData goat = creatures.getFirst();
        h.assertTrue(
                goat.getWeight().asInt() == 5 && goat.minCount == 1 && goat.maxCount == 2,
                "the goats' weight and group");
        h.assertTrue(
                spawns(kepler, MobCategory.CREATURE, pos).getFirst() == goat,
                "the goats' entry isn't the same from one spot to the next, which vanilla needs");

        List<SpawnerData> monsters = spawns(kepler, MobCategory.MONSTER, pos);
        h.assertValueEqual(types(monsters), List.of(EntityType.STRAY), "kepler's monsters");
        h.assertTrue(monsters.getFirst().minCount == 1 && monsters.getFirst().maxCount == 3, "the strays' group");

        List<SpawnerData> biomeAmbient = biomeList(kepler, MobCategory.AMBIENT, pos);
        h.assertValueEqual(
                types(spawns(kepler, MobCategory.AMBIENT, pos)),
                types(biomeAmbient),
                "a category kepler's file leaves out");

        ServerLevel overworld = h.getLevel().getServer().overworld();
        h.assertValueEqual(
                types(spawns(overworld, MobCategory.CREATURE, pos)),
                types(biomeList(overworld, MobCategory.CREATURE, pos)),
                "the Overworld's creatures");
        h.succeed();
    }

    /**
     * Without {@code replace_existing}, a planet's mobs are added to the biome's. The planets the
     * spawns follow are the server's as they are now, so this swaps in a kepler that adds, as a
     * {@code /reload} would, and puts the server's own back after.
     */
    @GameTest(template = "empty", batch = "planet_mobs_reload")
    public static void a_planet_without_replace_existing_adds_to_the_biome(GameTestHelper h) {
        ServerLevel kepler = kepler(h);
        BlockPos pos = new BlockPos(8, 64, 8);
        PlanetLoader.Result loaded = PlanetRegistry.server();
        Map<ResourceLocation, Planet> planets = new HashMap<>(loaded.planets());
        Planet planet = planets.values().stream()
                .filter(p -> p.dimension().equals(TestDimensions.KEPLER))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no test planet"));
        planets.put(
                planet.id(),
                withMobs(planet, new PlanetMobs(false, planet.mobs().spawns())));
        try {
            PlanetRegistry.setServer(
                    new PlanetLoader.Result(planets, loaded.files(), loaded.problems(), loaded.read()));
            List<EntityType<?>> expected = new ArrayList<>(types(biomeList(kepler, MobCategory.CREATURE, pos)));
            expected.add(EntityType.GOAT);
            h.assertValueEqual(
                    types(spawns(kepler, MobCategory.CREATURE, pos)), expected, "the biome's creatures and kepler's");
        } finally {
            PlanetRegistry.setServer(loaded);
        }
        h.assertValueEqual(
                types(spawns(kepler, MobCategory.CREATURE, pos)),
                List.of(EntityType.GOAT),
                "kepler's creatures put back");
        h.succeed();
    }

    /**
     * A new chunk's creatures, which vanilla spawns from the biome's list without the event, are
     * kepler's goats: the biome has none, so a goat comes only from the planet's list. (The biome's
     * rabbits and polar bears can't stand on kepler's gravel anyway; that they're gone from the list
     * is {@link #kepler_spawns_its_listed_mobs}'s.) The goats spawn although ZAS³ keeps a mob from
     * spawning where the air would kill it: kepler's air is theirs. Runs vanilla's own
     * chunk-generation spawning on one chunk until a few have spawned, at noon, since a goat needs
     * light to spawn.
     */
    @GameTest(template = "empty", batch = "planet_mobs", timeoutTicks = 200)
    public static void keplers_new_chunks_spawn_its_creatures(GameTestHelper h) {
        ServerLevel kepler = kepler(h);
        ServerLevel overworld = h.getLevel().getServer().overworld();
        ChunkPos chunk = new ChunkPos(4, 4);
        long before = overworld.getDayTime();
        h.startSequence()
                .thenExecute(() -> {
                    kepler.setChunkForced(chunk.x, chunk.z, true);
                    overworld.setDayTime(NOON);
                })
                .thenWaitUntil(() -> h.assertTrue(
                        kepler.isPositionEntityTicking(chunk.getMiddleBlockPosition(64)), "the chunk isn't loaded yet"))
                .thenIdle(2)
                .thenExecute(() -> {
                    AABB area = new AABB(
                            chunk.getMinBlockX(),
                            kepler.getMinBuildHeight(),
                            chunk.getMinBlockZ(),
                            chunk.getMaxBlockX() + 1,
                            kepler.getMaxBuildHeight(),
                            chunk.getMaxBlockZ() + 1);
                    Set<UUID> there = kepler.getEntitiesOfClass(Mob.class, area).stream()
                            .map(Entity::getUUID)
                            .collect(Collectors.toSet());
                    Holder<Biome> biome = kepler.getBiome(chunk.getMiddleBlockPosition(64));
                    List<Mob> spawned = new ArrayList<>();
                    try {
                        // One random for every try: consecutive seeds start out alike.
                        RandomSource random = RandomSource.create(18);
                        for (int i = 0; i < 2000 && spawned.size() < 4; i++) {
                            NaturalSpawner.spawnMobsForChunkGeneration(kepler, biome, chunk, random);
                            for (Mob mob : kepler.getEntitiesOfClass(Mob.class, area)) {
                                if (there.add(mob.getUUID())) {
                                    spawned.add(mob);
                                }
                            }
                        }
                        h.assertFalse(spawned.isEmpty(), "kepler's new chunk spawned no creatures");
                        h.assertTrue(
                                spawned.stream().allMatch(m -> m.getType() == EntityType.GOAT),
                                "kepler's new chunk spawned "
                                        + spawned.stream()
                                                .map(m -> EntityType.getKey(m.getType())
                                                        .toString())
                                                .toList());
                    } finally {
                        spawned.forEach(Entity::discard);
                        overworld.setDayTime(before);
                        kepler.setChunkForced(chunk.x, chunk.z, false);
                    }
                })
                .thenSucceed();
    }

    /**
     * Kepler's goat is unharmed in kepler's air while a pig beside it is harmed. Both are checked by
     * ZAS³ every 2 s by default, so this waits for the pig's first damage and for the goat to have
     * been spared at least one hazard, whatever the check interval, and listens to every hazard tick
     * either gets, cancelled or not. Also that a goat in the Overworld isn't adapted, nor a pig on
     * kepler, and that a breach's wind spares no one.
     */
    @GameTest(template = "empty", batch = "planet_mobs", timeoutTicks = 1200)
    public static void keplers_goat_lives_in_its_air_and_a_pig_doesnt(GameTestHelper h) {
        ServerLevel kepler = kepler(h);
        ChunkPos chunk = new ChunkPos(-4, -4);
        h.assertTrue(PlanetSpawns.isNative(TestDimensions.KEPLER, EntityType.GOAT), "the goat isn't kepler's");
        h.assertFalse(PlanetSpawns.isNative(TestDimensions.KEPLER, EntityType.PIG), "the pig is kepler's");
        h.assertTrue(PlanetAir.appliesTo(TestDimensions.KEPLER), "kepler's air isn't the planet's");
        h.assertFalse(NativeAir.ofTheAir(Hazard.AIRFLOW_IMPACT), "a native is spared a breach's wind");
        Mob abroad = h.spawnWithNoFreeWill(EntityType.GOAT, new BlockPos(1, 2, 1));
        h.assertFalse(NativeAir.adapted(abroad), "a goat in the Overworld is adapted to its air");
        abroad.discard();

        Map<LivingEntity, List<HazardTickEvent>> heard = new HashMap<>();
        Consumer<HazardTickEvent> listener = e -> {
            List<HazardTickEvent> mine = heard.get(e.entity());
            if (mine != null) {
                mine.add(e);
            }
        };
        Mob[] mobs = new Mob[2];
        h.startSequence()
                .thenExecute(() -> {
                    kepler.setChunkForced(chunk.x, chunk.z, true);
                })
                .thenWaitUntil(() -> h.assertTrue(
                        kepler.isPositionEntityTicking(chunk.getMiddleBlockPosition(64)), "the chunk isn't loaded yet"))
                .thenExecute(() -> {
                    mobs[0] = place(h, kepler, EntityType.GOAT, chunk.getMinBlockX() + 4, chunk.getMinBlockZ() + 4);
                    mobs[1] = place(h, kepler, EntityType.PIG, chunk.getMinBlockX() + 11, chunk.getMinBlockZ() + 11);
                    heard.put(mobs[0], new ArrayList<>());
                    heard.put(mobs[1], new ArrayList<>());
                    NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, HazardTickEvent.class, listener);
                })
                .thenWaitUntil(() -> {
                    h.assertValueEqual(Atmospheres.of(mobs[0]).state(), AtmosphereState.AMBIENT, "the goat's air");
                    h.assertTrue(NativeAir.adapted(mobs[0]), "kepler's goat isn't adapted to kepler's air");
                    h.assertFalse(NativeAir.adapted(mobs[1]), "a pig is adapted to kepler's air");
                    h.assertTrue(lost(mobs[1]) > 0, "the pig isn't harmed yet");
                    h.assertFalse(heard.get(mobs[0]).isEmpty(), "no hazard has come to the goat yet");
                })
                .thenExecute(() -> {
                    try {
                        List<HazardTickEvent> goat = heard.get(mobs[0]);
                        List<HazardTickEvent> pig = heard.get(mobs[1]);
                        h.assertTrue(
                                goat.stream().allMatch(HazardTickEvent::isCanceled),
                                "a hazard wasn't spared the goat: " + hazards(goat));
                        h.assertTrue(
                                goat.stream().anyMatch(e -> e.hazard() == Hazard.LOW_PRESSURE),
                                "the goat wasn't spared kepler's low pressure: " + hazards(goat));
                        h.assertValueEqual(lost(mobs[0]), 0f, "the goat's lost health");
                        h.assertTrue(mobs[0].isAlive(), "the goat died");
                        h.assertTrue(
                                pig.stream().noneMatch(HazardTickEvent::isCanceled),
                                "a hazard was spared the pig: " + hazards(pig));
                    } finally {
                        NeoForge.EVENT_BUS.unregister(listener);
                        for (Mob mob : mobs) {
                            mob.discard();
                        }
                        kepler.setChunkForced(chunk.x, chunk.z, false);
                    }
                })
                .thenSucceed();
    }

    private static ServerLevel kepler(GameTestHelper h) {
        ServerLevel kepler = h.getLevel().getServer().getLevel(TestDimensions.KEPLER);
        if (kepler == null) {
            throw new IllegalStateException("the test dimension isn't a level");
        }
        return kepler;
    }

    /** What a level's biome spawns of a category at a spot, before the event. */
    private static List<SpawnerData> biomeList(ServerLevel level, MobCategory category, BlockPos pos) {
        return level.getChunkSource()
                .getGenerator()
                .getMobsAt(level.getBiome(pos), level.structureManager(), category, pos)
                .unwrap();
    }

    /** What a level spawns of a category at a spot, as natural spawning asks for it. */
    private static List<SpawnerData> spawns(ServerLevel level, MobCategory category, BlockPos pos) {
        return EventHooks.getPotentialSpawns(
                        level, category, pos, WeightedRandomList.create(biomeList(level, category, pos)))
                .unwrap();
    }

    private static List<EntityType<?>> types(List<SpawnerData> list) {
        return list.stream().<EntityType<?>>map(d -> d.type).toList();
    }

    private static Set<String> hazards(List<HazardTickEvent> events) {
        Set<String> names = new HashSet<>();
        for (HazardTickEvent e : events) {
            names.add(e.hazard() + (e.isCanceled() ? " (spared)" : ""));
        }
        return names;
    }

    private static float lost(LivingEntity e) {
        return e.getMaxHealth() - e.getHealth();
    }

    /** A mob with no AI standing on kepler's ground. */
    private static <T extends Mob> T place(GameTestHelper h, ServerLevel level, EntityType<T> type, int x, int z) {
        T mob = type.create(level);
        h.assertTrue(mob != null, "couldn't create a " + type);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        mob.moveTo(Vec3.atBottomCenterOf(new BlockPos(x, y, z)));
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        level.addFreshEntity(mob);
        return mob;
    }

    private static Planet withMobs(Planet p, PlanetMobs mobs) {
        return new Planet(
                p.id(),
                p.dimension(),
                p.space(),
                p.surface(),
                p.atmosphere(),
                p.gravity(),
                p.ores(),
                p.oreDensity(),
                mobs,
                p.structures(),
                p.access());
    }
}
