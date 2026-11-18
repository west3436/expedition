package io.github.west3436.expedition.planet;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.api.planet.PlanetGravity;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

/**
 * A planet's gravity on its living entities (#16), as {@link PlanetGravity} describes: three
 * transient modifiers, all of operation {@code add_multiplied_total}, so a gravity {@code g}
 * multiplies each attribute's value:
 *
 * <ul>
 *   <li>{@link PlanetGravity#MODIFIER_ID} on {@link Attributes#GRAVITY}, by {@code g}
 *   <li>{@link #SAFE_FALL_ID} on {@link Attributes#SAFE_FALL_DISTANCE}, by {@code 1 / g}: a jump
 *       goes {@code 1 / g} times as high, so landing from one never hurts
 *   <li>{@link #FALL_DAMAGE_ID} on {@link Attributes#FALL_DAMAGE_MULTIPLIER}, by {@code g}
 * </ul>
 *
 * <p>Together a fall of {@code h} blocks hurts as much as one of {@code g × h} on the Overworld:
 * the same impact. The modifiers are set as an entity joins a server level (spawned, loaded from
 * its chunk, or arriving from another dimension), removed as it leaves one, and set again for every
 * entity after a {@code /reload}. A level whose gravity is 1 leaves none. Being transient, they are
 * never saved with the entity. The attributes are synced, so clients see them; things without
 * attributes are scaled by {@code EntityGravityMixin}.
 */
public final class PlanetGravityEvents {
    /** {@code expedition:planet_safe_fall_distance}: the planet's modifier on the safe fall distance. */
    public static final ResourceLocation SAFE_FALL_ID =
            ResourceLocation.fromNamespaceAndPath(ExpeditionMod.MOD_ID, "planet_safe_fall_distance");

    /** {@code expedition:planet_fall_damage}: the planet's modifier on the fall damage multiplier. */
    public static final ResourceLocation FALL_DAMAGE_ID =
            ResourceLocation.fromNamespaceAndPath(ExpeditionMod.MOD_ID, "planet_fall_damage");

    /**
     * The safe fall distance's largest multiple, for a gravity of 0 or nearly 0: past the
     * attribute's own limit, so such a fall never hurts.
     */
    private static final double MAX_SAFE_FALL_FACTOR = 1_000.0;

    private PlanetGravityEvents() {}

    /**
     * Listens on the game bus.
     *
     * @param gameBus NeoForge's game event bus
     */
    public static void register(IEventBus gameBus) {
        gameBus.addListener(PlanetGravityEvents::onJoin);
        gameBus.addListener(PlanetGravityEvents::onLeave);
    }

    private static void onJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof LivingEntity living
                && !event.getLevel().isClientSide()) {
            apply(living, PlanetGravity.of(event.getLevel()));
        }
    }

    private static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof LivingEntity living
                && !event.getLevel().isClientSide()) {
            apply(living, PlanetGravity.NORMAL);
        }
    }

    /**
     * Sets every living entity's modifiers again from its level's gravity, after the planets have
     * changed.
     *
     * @param server the server
     */
    public static void refresh(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            double gravity = PlanetGravity.of(level);
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof LivingEntity living) {
                    apply(living, gravity);
                }
            }
        }
    }

    /**
     * Sets an entity's modifiers for a gravity, or removes them for a gravity of 1.
     *
     * @param entity the entity
     * @param gravity the gravity, as a multiple of the Overworld's, 0 or more
     */
    public static void apply(LivingEntity entity, double gravity) {
        set(entity, Attributes.GRAVITY, PlanetGravity.MODIFIER_ID, gravity);
        double safeFall = gravity > 1.0 / MAX_SAFE_FALL_FACTOR ? 1.0 / gravity : MAX_SAFE_FALL_FACTOR;
        set(entity, Attributes.SAFE_FALL_DISTANCE, SAFE_FALL_ID, safeFall);
        set(entity, Attributes.FALL_DAMAGE_MULTIPLIER, FALL_DAMAGE_ID, gravity);
    }

    /** Puts a modifier on an attribute that multiplies its value, or takes it off for a factor of 1. */
    private static void set(LivingEntity entity, Holder<Attribute> attribute, ResourceLocation id, double factor) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        if (factor == 1.0) {
            instance.removeModifier(id);
        } else {
            instance.addOrUpdateTransientModifier(
                    new AttributeModifier(id, factor - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }
}
