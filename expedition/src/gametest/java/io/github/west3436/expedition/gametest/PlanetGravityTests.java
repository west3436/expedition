package io.github.west3436.expedition.gametest;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.api.planet.PlanetGravity;
import io.github.west3436.ss13.api.gravity.Gravity;
import io.github.west3436.ss13.gravity.GravityEntities;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A planet's gravity (#16), on the test planet {@code expedition_test:kepler}, whose file gives a
 * gravity of 0.38: a mob there carries the gravity modifier, with its falls scaled to match, and
 * loses it on leaving for the Overworld; a level that isn't a planet, or a planet of normal gravity,
 * gives none; things without attributes fall at 0.38 of their usual gravity; and a {@code /reload}
 * sets the modifiers again.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PlanetGravityTests {
    private static final double KEPLER_GRAVITY = 0.38;

    private static final double EPSILON = 1e-9;

    /** Long enough for a forced chunk to come to entity ticking on a busy GameTest server. */
    private static final int TIMEOUT = 600;

    private static final ResourceKey<Level> GENERATED = ResourceKey.create(
            Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(TestDimensions.NAMESPACE, "generated"));

    private PlanetGravityTests() {}

    // ---- The tests ----------------------------------------------------------------------

    /**
     * A pig that joins kepler carries a modifier of 0.38 − 1 on its gravity, a safe fall distance of
     * 3 / 0.38 and a fall damage multiplier of 0.38. Sent to the Overworld, it arrives without them,
     * and the one it left behind has lost them too.
     */
    @GameTest(template = "empty", timeoutTicks = TIMEOUT)
    public static void a_mob_on_a_planet_carries_its_gravity_and_loses_it_on_leaving(GameTestHelper h) {
        ServerLevel kepler = level(h, TestDimensions.KEPLER);
        Pig pig = pigIn(h, kepler, 0);
        onceTracked(h, kepler, pig, () -> {
            AttributeModifier modifier = pig.getAttribute(Attributes.GRAVITY).getModifier(Gravity.MODIFIER_ID);
            h.assertTrue(modifier != null, "the pig on kepler has no gravity modifier");
            h.assertValueEqual(modifier.operation(), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, "its operation");
            near(h, modifier.amount(), KEPLER_GRAVITY - 1, "its amount");
            near(h, pig.getGravity(), 0.08 * KEPLER_GRAVITY, "the pig's gravity");
            near(h, pig.getAttributeValue(Attributes.SAFE_FALL_DISTANCE), 3 / KEPLER_GRAVITY, "its safe fall");
            near(h, pig.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER), KEPLER_GRAVITY, "its fall damage");

            ServerLevel overworld = h.getLevel();
            Vec3 there = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 2, 1)));
            Entity moved = pig.changeDimension(
                    new DimensionTransition(overworld, there, Vec3.ZERO, 0, 0, DimensionTransition.DO_NOTHING));
            if (!(moved instanceof Pig arrived) || arrived.level() != overworld) {
                throw new GameTestAssertException("the pig didn't arrive in the Overworld: " + moved);
            }
            try {
                assertNoModifiers(h, arrived, "the pig in the Overworld");
                near(h, arrived.getGravity(), 0.08, "the pig's gravity in the Overworld");
                assertNoModifiers(h, pig, "the pig left on kepler");
            } finally {
                arrived.discard();
            }
        });
    }

    /** A pig in the Overworld, the Nether, or a planet whose gravity is normal, carries nothing. */
    @GameTest(template = "empty")
    public static void a_level_of_normal_gravity_gives_no_modifier(GameTestHelper h) {
        for (ResourceKey<Level> key : List.of(Level.OVERWORLD, Level.NETHER, GENERATED)) {
            ServerLevel level = level(h, key);
            h.assertValueEqual(PlanetGravity.of(level), PlanetGravity.NORMAL, key.location() + "'s gravity");
            Pig pig = pigIn(h, level, 1);
            try {
                assertNoModifiers(h, pig, "a pig in " + key.location());
            } finally {
                pig.discard();
            }
        }
        h.assertValueEqual(PlanetGravity.of(level(h, TestDimensions.KEPLER)), KEPLER_GRAVITY, "kepler's gravity");
        h.succeed();
    }

    /** A dropped item and an arrow on kepler fall at 0.38 of their gravity in the Overworld. */
    @GameTest(template = "empty")
    public static void things_without_attributes_fall_with_the_planets_gravity(GameTestHelper h) {
        ServerLevel kepler = level(h, TestDimensions.KEPLER);
        ServerLevel overworld = h.getLevel();
        ItemEntity keplerItem = new ItemEntity(kepler, 0, 80, 0, new ItemStack(Items.STONE));
        ItemEntity item = new ItemEntity(overworld, 0, 80, 0, new ItemStack(Items.STONE));
        near(h, keplerItem.getGravity(), item.getGravity() * KEPLER_GRAVITY, "an item's gravity");
        h.assertTrue(item.getGravity() > 0, "an item has no gravity in the Overworld");
        Arrow keplerArrow = new Arrow(EntityType.ARROW, kepler);
        Arrow arrow = new Arrow(EntityType.ARROW, overworld);
        near(h, keplerArrow.getGravity(), arrow.getGravity() * KEPLER_GRAVITY, "an arrow's gravity");
        h.succeed();
    }

    /**
     * After a {@code /reload}, every living entity's modifiers are set again from its level: one on
     * kepler that lost them gets them back, and one in the Overworld that wrongly had them loses
     * them.
     */
    @GameTest(template = "empty", timeoutTicks = TIMEOUT)
    public static void a_reload_sets_the_gravity_again(GameTestHelper h) {
        ServerLevel kepler = level(h, TestDimensions.KEPLER);
        Pig onKepler = pigIn(h, kepler, 2);
        Pig inOverworld = pigIn(h, h.getLevel(), 0);
        onceTracked(h, kepler, onKepler, () -> {
            try {
                GravityEntities.apply(onKepler, PlanetGravity.NORMAL);
                GravityEntities.apply(inOverworld, KEPLER_GRAVITY);
                assertNoModifiers(h, onKepler, "the pig on kepler, stripped");

                GravityEntities.refresh(h.getLevel().getServer());
                near(h, onKepler.getGravity(), 0.08 * KEPLER_GRAVITY, "the pig on kepler's gravity");
                assertNoModifiers(h, inOverworld, "the pig in the Overworld");
            } finally {
                inOverworld.discard();
            }
        });
    }

    // ---- Helpers ------------------------------------------------------------------------

    /**
     * A pig without AI, added to a level as a spawned one is: on the test's floor in the Overworld,
     * or high in chunk {@code chunk, 0} of another level, a chunk of each test's own.
     */
    private static Pig pigIn(GameTestHelper h, ServerLevel level, int chunk) {
        Pig pig = EntityType.PIG.create(level);
        if (pig == null) {
            throw new GameTestAssertException("no pig");
        }
        BlockPos at =
                level == h.getLevel() ? h.absolutePos(new BlockPos(1, 2, 1)) : new BlockPos(16 * chunk + 8, 100, 8);
        level.getChunk(at);
        pig.moveTo(Vec3.atBottomCenterOf(at));
        pig.setNoAi(true);
        h.assertTrue(
                level.addFreshEntity(pig),
                "the pig wasn't added to " + level.dimension().location());
        return pig;
    }

    /**
     * Runs the checks once the level tracks the entity, as it does every entity of a loaded chunk
     * that a player could see: only then is its leaving an event, and only then is it among the
     * level's entities. The entity's chunk is forced until then, and the entity is discarded after.
     */
    private static void onceTracked(GameTestHelper h, ServerLevel level, Entity entity, Runnable checks) {
        ChunkPos chunk = entity.chunkPosition();
        level.setChunkForced(chunk.x, chunk.z, true);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(
                        level.getEntity(entity.getUUID()) == entity,
                        entity + " isn't tracked yet (removed: " + entity.getRemovalReason() + ")"))
                .thenExecute(() -> {
                    try {
                        checks.run();
                    } finally {
                        entity.discard();
                        level.setChunkForced(chunk.x, chunk.z, false);
                    }
                })
                .thenSucceed();
    }

    private static void assertNoModifiers(GameTestHelper h, LivingEntity entity, String what) {
        assertNone(h, entity, Attributes.GRAVITY, Gravity.MODIFIER_ID, what);
        assertNone(h, entity, Attributes.SAFE_FALL_DISTANCE, Gravity.SAFE_FALL_ID, what);
        assertNone(h, entity, Attributes.FALL_DAMAGE_MULTIPLIER, Gravity.FALL_DAMAGE_ID, what);
    }

    private static void assertNone(
            GameTestHelper h, LivingEntity entity, Holder<Attribute> attribute, ResourceLocation id, String what) {
        h.assertTrue(
                entity.getAttribute(attribute).getModifier(id) == null,
                what + " carries " + id + " on " + attribute.getRegisteredName());
    }

    private static void near(GameTestHelper h, double actual, double expected, String what) {
        h.assertTrue(Math.abs(actual - expected) < EPSILON, what + " is " + actual + ", not " + expected);
    }

    private static ServerLevel level(GameTestHelper h, ResourceKey<Level> key) {
        ServerLevel level = h.getLevel().getServer().getLevel(key);
        if (level == null) {
            throw new GameTestAssertException("no " + key.location());
        }
        return level;
    }
}
